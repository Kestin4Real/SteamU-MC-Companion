package be.voidguardians.steamu.test;

import be.voidguardians.steamu.config.CompanionConfig;
import be.voidguardians.steamu.ui.ControlsScreen;
import be.voidguardians.steamu.ui.DeckScreens;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scripted session for checking the Deck UI end to end. Each phase logs a
 * STEAMU_PHASE line with the Deck-pixel positions of what to touch, waits
 * STEAMU_PHASE_SECONDS (default 12) so a person or script can act on the
 * Deck, then logs the resulting state.
 *
 *   xp:       stats screen with levels and points, for a screenshot
 *   menu:     pause menu driven with the Deck's controller
 *   remap:    SteamU Controls screen, rebind Attack with a Deck button
 *   unbind:   Options > Controls button opens it, Esc unbinds (automatic)
 *   crafting: X-style inventory on the Deck with oak logs in the hotbar
 *   chest:    right-clicked chest (with diamonds) opens on the Deck
 *
 * STEAMU_TEST_PHASES picks which phases run (comma separated).
 *
 * Run with steamu-host and a Deck (or `steamu-deck --fake-idle --windowed`).
 */
public class CompanionClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("steamu_companion_test");

	@Override
	public void runTest(ClientGameTestContext context) {
		String env = System.getenv("STEAMU_PHASE_SECONDS");
		int phaseTicks = (env != null ? Integer.parseInt(env) : 12) * 20;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			var server = world.getServer();
			server.runCommand("gamemode survival @a");
			server.runCommand("clear @a");
			server.runCommand("give @a oak_log 4");         // hotbar 0
			server.runCommand("give @a cobblestone 32");    // hotbar 1
			server.runCommand("give @a iron_helmet");
			server.runCommand("xp add @a 7 levels");
			server.runCommand("xp add @a 40 points");
			context.waitTicks(40);
			String phases = System.getenv().getOrDefault("STEAMU_TEST_PHASES", "xp,menu,remap,unbind,crafting,chest");

			// --- XP bar / companion overview ----------------------------------
			if (phases.contains("xp")) {
				LOG.info("STEAMU_PHASE xp");
				context.waitTicks(phaseTicks / 3);
			}

			// --- Controller navigation in a PC menu ---------------------------
			if (phases.contains("menu")) {
				context.runOnClient(mc -> mc.pauseGame(false));
				context.waitTicks(10);
				context.runOnClient(mc -> LOG.info("STEAMU_PHASE menu pcScreen={}", screenName(mc)));
				context.waitTicks(phaseTicks);
				context.runOnClient(mc -> LOG.info("STEAMU_RESULT menu pcScreen={}", screenName(mc)));
			}

			// --- Remapping with the Deck --------------------------------------
			if (phases.contains("remap")) {
				context.runOnClient(mc -> mc.gui.setScreen(new ControlsScreen(null)));
				context.waitTicks(10);
				context.runOnClient(mc -> LOG.info("STEAMU_PHASE remap pcScreen={} attack={}", screenName(mc),
					CompanionConfig.get().binding(CompanionConfig.Action.ATTACK, 0)));
				context.waitTicks(phaseTicks);
				context.runOnClient(mc -> {
					LOG.info("STEAMU_RESULT remap pcScreen={} attack={}", screenName(mc),
						CompanionConfig.get().binding(CompanionConfig.Action.ATTACK, 0));
					CompanionConfig.get().resetBindings();
				});
			}

			// --- Options -> Controls button, and Esc unbinding (automatic) ----
			if (phases.contains("unbind")) {
				context.runOnClient(mc -> mc.gui.setScreen(
					new net.minecraft.client.gui.screens.options.controls.ControlsScreen(null, mc.options)));
				context.waitTicks(5);
				context.runOnClient(mc -> {
					Button open = findButton(mc.gui.screen(), "Steam Deck Controls...");
					LOG.info("STEAMU_RESULT unbind vanillaButton={}", open != null);
					if (open != null) open.onPress(enter());
				});
				context.waitTicks(5);
				context.runOnClient(mc -> {
					String before = String.valueOf(CompanionConfig.get().binding(CompanionConfig.Action.ATTACK, 0));
					// The first slot button is Attack, slot 0.
					Button slot = findButton(mc.gui.screen(), CompanionConfig.get().binding(CompanionConfig.Action.ATTACK, 0).label());
					if (slot != null) slot.onPress(enter());
					boolean capturing = mc.gui.screen() instanceof ControlsScreen c && c.isCapturing();
					mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 27, 0));
					LOG.info("STEAMU_RESULT unbind screen={} capturing={} attack {} -> {}", screenName(mc), capturing,
						before, CompanionConfig.get().binding(CompanionConfig.Action.ATTACK, 0));
					CompanionConfig.get().resetBindings();
					CompanionConfig.get().save();
					mc.gui.setScreen(null);
				});
			}
			if (!phases.contains("crafting") && !phases.contains("chest")) return;

			// --- Phase 1: crafting on the Deck --------------------------------
			context.runOnClient(mc -> {
				DeckScreens.INSTANCE.toggleInventory(mc);
			});
			context.waitTicks(10);
			context.runOnClient(mc -> {
				DeckScreens d = DeckScreens.INSTANCE;
				LOG.info("STEAMU_PHASE crafting open={} pcScreen={} logs={} craft={} result={} shift={} close={}",
					d.current() == null ? "none" : d.current().getClass().getSimpleName(),
					mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(),
					px(d.slotCenterPixels(InventoryMenu.USE_ROW_SLOT_START)),
					px(d.slotCenterPixels(InventoryMenu.CRAFT_SLOT_START)),
					px(d.slotCenterPixels(InventoryMenu.RESULT_SLOT)),
					px(d.shiftButtonPixels()), px(d.closeButtonPixels()));
			});
			context.waitTicks(phaseTicks);
			context.runOnClient(mc -> LOG.info("STEAMU_RESULT crafting deckOpen={} planks={} logs={}",
				DeckScreens.INSTANCE.isOpen(), count(mc, "oak_planks"), count(mc, "oak_log")));

			// --- Phase 2: a chest opens on the Deck ---------------------------
			context.runOnClient(mc -> {
				if (DeckScreens.INSTANCE.isOpen()) DeckScreens.INSTANCE.close(mc);
			});
			BlockPos chest = context.computeOnClient(mc -> mc.player.blockPosition().relative(mc.player.getDirection(), 2));
			server.runCommand("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " chest");
			server.runCommand("item replace block " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " container.0 with diamond 7");
			server.runCommand("item replace block " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " container.1 with gold_ingot 10");
			context.waitTicks(10);
			context.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false)));
			context.waitTicks(10);
			context.runOnClient(mc -> {
				DeckScreens d = DeckScreens.INSTANCE;
				int playerHotbar2 = mc.player.containerMenu.slots.size() - 9 + 2; // chest menu: chest slots, inventory, hotbar
				LOG.info("STEAMU_PHASE chest open={} pcScreen={} diamonds={} gold={} hotbar2={} shift={} close={}",
					d.current() == null ? "none" : d.current().getClass().getSimpleName(),
					mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(),
					px(d.slotCenterPixels(0)), px(d.slotCenterPixels(1)),
					px(d.slotCenterPixels(playerHotbar2)), px(d.shiftButtonPixels()), px(d.closeButtonPixels()));
			});
			context.waitTicks(phaseTicks);
			context.runOnClient(mc -> LOG.info("STEAMU_RESULT chest deckOpen={} containerClosed={} pcScreen={} diamonds={} gold={}",
				DeckScreens.INSTANCE.isOpen(), mc.player.containerMenu == mc.player.inventoryMenu,
				mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(),
				count(mc, "diamond"), count(mc, "gold_ingot")));
		}
	}

	private static KeyEvent enter() {
		return new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0);
	}

	private static Button findButton(GuiEventListener root, String label) {
		if (root instanceof Button b && b.getMessage().getString().equals(label)) return b;
		if (root instanceof ContainerEventHandler c)
			for (GuiEventListener child : c.children()) {
				Button found = findButton(child, label);
				if (found != null) return found;
			}
		return null;
	}

	private static String screenName(Minecraft mc) {
		return mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName();
	}

	private static String px(int[] p) {
		return p == null ? "-" : p[0] + "," + p[1];
	}

	private static int count(Minecraft mc, String id) {
		Player p = mc.player;
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && s.getItem().toString().endsWith(id)) n += s.getCount();
		}
		return n;
	}
}
