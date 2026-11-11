package be.voidguardians.steamu.ui;

import be.voidguardians.steamu.net.ScreenClient.Touch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/**
 * The Deck screen: health/food/armor/XP, the full inventory, armor and
 * offhand. Touch controls (applied on the client thread):
 *  - drag a slot onto another: move / swap / merge
 *  - tap a hotbar slot: select it
 *  - tap any other slot: quick-move (like shift-click; equips armor)
 * Works while you keep playing: nothing opens on the PC.
 */
public final class CompanionScreen {
	private static final Identifier HEART_CONTAINER = id("hud/heart/container");
	private static final Identifier HEART_FULL = id("hud/heart/full");
	private static final Identifier HEART_HALF = id("hud/heart/half");
	private static final Identifier FOOD_EMPTY = id("hud/food_empty");
	private static final Identifier FOOD_FULL = id("hud/food_full");
	private static final Identifier FOOD_HALF = id("hud/food_half");
	private static final Identifier ARMOR_EMPTY = id("hud/armor_empty");
	private static final Identifier ARMOR_FULL = id("hud/armor_full");
	private static final Identifier ARMOR_HALF = id("hud/armor_half");
	private static final Identifier AIR = id("hud/air");
	private static final Identifier AIR_EMPTY = id("hud/air_empty");
	private static final Identifier XP_BAR_BACKGROUND = id("hud/experience_bar_background");
	private static final Identifier XP_BAR_PROGRESS = id("hud/experience_bar_progress");
	private static final int XP_BAR_WIDTH = 182, XP_BAR_HEIGHT = 5;
	private static final Identifier[] EMPTY_ARMOR = {
		InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
		InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS,
	};

	private static final int BACKGROUND = 0xFF15171C;
	private static final int PANEL = 0xFFC6C6C6;
	private static final int TEXT = 0xFFE8EAF0;
	private static final int TEXT_DIM = 0xFF8A90A0;
	private static final float DRAG_THRESHOLD = 4; // GUI units

	private CompanionLayout layout = new CompanionLayout(1280, 800);

	// Touch state (finger 0 only; extra fingers are ignored)
	private int dragFrom = -1;
	private boolean touching, moved;
	private float touchX, touchY, startX, startY;

	public void setSize(int pixelWidth, int pixelHeight) {
		if (layout.width != pixelWidth / CompanionLayout.GUI_SCALE || layout.height != pixelHeight / CompanionLayout.GUI_SCALE)
			layout = new CompanionLayout(pixelWidth, pixelHeight);
	}

	// --- Drawing -----------------------------------------------------------

	public void draw(GuiGraphicsExtractor g, Minecraft mc) {
		g.fill(0, 0, layout.width, layout.height, BACKGROUND);
		LocalPlayer player = mc.player;
		Font font = mc.font;
		if (player == null) {
			g.centeredText(font, "SteamU Companion", layout.width / 2, layout.height / 2 - 10, TEXT);
			g.centeredText(font, "Join a world to see your inventory", layout.width / 2, layout.height / 2 + 4, TEXT_DIM);
			return;
		}

		drawStats(g, font, player);

		// Inventory panel
		int x0 = layout.armorX - 6, x1 = layout.offhandX + CompanionLayout.SLOT + 6;
		int y0 = layout.invY - 6, y1 = layout.hotbarY + CompanionLayout.SLOT + 6;
		g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF000000);
		g.fill(x0, y0, x1, y1, PANEL);

		InventoryMenu menu = player.inventoryMenu;
		boolean usable = player.containerMenu == menu;
		int selected = CompanionLayout.HOTBAR_START + player.getInventory().getSelectedSlot();
		int hovered = touching ? layout.hit(touchX, touchY) : -1;

		for (int slot = CompanionLayout.ARMOR_START; slot <= CompanionLayout.OFFHAND; slot++) {
			int[] p = layout.slotPos(slot);
			if (p == null) continue;
			drawSlotBackground(g, p[0], p[1], slot == hovered && dragFrom >= 0 && moved);
			ItemStack stack = menu.getSlot(slot).getItem();
			boolean beingDragged = slot == dragFrom && moved;
			if (stack.isEmpty()) {
				if (slot >= CompanionLayout.ARMOR_START && slot < CompanionLayout.INV_START)
					g.blitSprite(RenderPipelines.GUI_TEXTURED, EMPTY_ARMOR[slot - CompanionLayout.ARMOR_START], p[0] + 1, p[1] + 1, 16, 16);
				else if (slot == CompanionLayout.OFFHAND)
					g.blitSprite(RenderPipelines.GUI_TEXTURED, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD, p[0] + 1, p[1] + 1, 16, 16);
			} else if (!beingDragged) {
				g.item(stack, p[0] + 1, p[1] + 1);
				g.itemDecorations(font, stack, p[0] + 1, p[1] + 1);
			}
			if (slot == selected) {
				g.outline(p[0] - 1, p[1] - 1, CompanionLayout.SLOT + 2, CompanionLayout.SLOT + 2, 0xFFFFFFFF);
				g.outline(p[0], p[1], CompanionLayout.SLOT, CompanionLayout.SLOT, 0xFFFFFFFF);
			}
		}

		// Name of the held item
		ItemStack held = player.getInventory().getSelectedItem();
		if (!held.isEmpty())
			g.centeredText(font, held.getHoverName(), layout.width / 2, y1 + 5, TEXT);

		String hint = usable
			? "Drag: move  Tap: select/quick-move  X: crafting"
			: "Close the screen on the PC to manage items";
		g.centeredText(font, hint, layout.width / 2, layout.height - 11, usable ? TEXT_DIM : 0xFFFFB050);

		// Item following the finger while dragging
		if (dragFrom >= 0 && moved) {
			ItemStack stack = menu.getSlot(dragFrom).getItem();
			if (!stack.isEmpty()) {
				g.nextStratum();
				int ix = Math.round(touchX) - 8, iy = Math.round(touchY) - 8;
				g.item(stack, ix, iy);
				g.itemDecorations(font, stack, ix, iy);
			}
		}
	}

	private void drawStats(GuiGraphicsExtractor g, Font font, LocalPlayer player) {
		int left = layout.invX, right = layout.invX + 9 * CompanionLayout.SLOT;

		int y = layout.statsY;

		// Health (first 10 hearts) + absorption as text
		int health = Mth.ceil(player.getHealth());
		int maxHearts = Math.min(10, Mth.ceil(player.getMaxHealth() / 2));
		for (int i = 0; i < maxHearts; i++) {
			int x = left + i * 8;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_CONTAINER, x, y, 9, 9);
			if (i * 2 + 1 < health) g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, x, y, 9, 9);
			else if (i * 2 + 1 == health) g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_HALF, x, y, 9, 9);
		}
		int absorption = Mth.ceil(player.getAbsorptionAmount());
		if (absorption > 0 || player.getMaxHealth() > 20) {
			String extra = (player.getMaxHealth() > 20 ? health + "/" + Mth.ceil(player.getMaxHealth()) : "")
				+ (absorption > 0 ? " +" + absorption : "");
			g.text(font, extra.trim(), left + maxHearts * 8 + 3, y + 1, 0xFFFFD040, true);
		}

		// Food, right-aligned like the vanilla HUD
		int food = player.getFoodData().getFoodLevel();
		for (int i = 0; i < 10; i++) {
			int x = right - 9 - i * 8;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_EMPTY, x, y, 9, 9);
			if (i * 2 + 1 < food) g.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_FULL, x, y, 9, 9);
			else if (i * 2 + 1 == food) g.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_HALF, x, y, 9, 9);
		}

		// Armor (left) and air (right, only when it matters)
		y += 10;
		int armor = player.getArmorValue();
		if (armor > 0) {
			for (int i = 0; i < 10; i++) {
				int x = left + i * 8;
				Identifier sprite = i * 2 + 1 < armor ? ARMOR_FULL : i * 2 + 1 == armor ? ARMOR_HALF : ARMOR_EMPTY;
				g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 9, 9);
			}
		}
		int air = player.getAirSupply(), maxAir = player.getMaxAirSupply();
		if (air < maxAir) {
			int bubbles = Mth.ceil(Math.max(0, air) * 10.0 / maxAir);
			for (int i = 0; i < 10; i++)
				g.blitSprite(RenderPipelines.GUI_TEXTURED, i < bubbles ? AIR : AIR_EMPTY, right - 9 - i * 8, y, 9, 9);
		}

		// Experience bar below health/food/armor (a 50 px gap on the Deck),
		// with the vanilla sprites and the level number centred on it
		// (outlined like the HUD).
		int barX = (layout.width - XP_BAR_WIDTH) / 2, barY = layout.xpBarY;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, XP_BAR_BACKGROUND, barX, barY, XP_BAR_WIDTH, XP_BAR_HEIGHT);
		int progress = (int) (Mth.clamp(player.experienceProgress, 0, 1) * (XP_BAR_WIDTH + 1));
		if (progress > 0)
			g.blitSprite(RenderPipelines.GUI_TEXTURED, XP_BAR_PROGRESS, XP_BAR_WIDTH, XP_BAR_HEIGHT, 0, 0, barX, barY, progress, XP_BAR_HEIGHT);
		if (player.experienceLevel > 0) {
			String level = Integer.toString(player.experienceLevel);
			int lx = (layout.width - font.width(level)) / 2, ly = barY - 4;
			g.text(font, level, lx + 1, ly, 0xFF000000, false);
			g.text(font, level, lx - 1, ly, 0xFF000000, false);
			g.text(font, level, lx, ly + 1, 0xFF000000, false);
			g.text(font, level, lx, ly - 1, 0xFF000000, false);
			g.text(font, level, lx, ly, 0xFF80FF20, false);
		}
	}

	private static void drawSlotBackground(GuiGraphicsExtractor g, int x, int y, boolean highlight) {
		int s = CompanionLayout.SLOT;
		g.fill(x, y, x + s, y + s, 0xFF8B8B8B);
		g.fill(x, y, x + s - 1, y + 1, 0xFF373737); // top
		g.fill(x, y, x + 1, y + s - 1, 0xFF373737); // left
		g.fill(x + 1, y + s - 1, x + s, y + s, 0xFFFFFFFF); // bottom
		g.fill(x + s - 1, y + 1, x + s, y + s, 0xFFFFFFFF); // right
		if (highlight) g.fill(x + 1, y + 1, x + s - 1, y + s - 1, 0x80FFFFFF);
	}

	// --- Touch -------------------------------------------------------------

	public void handleTouch(Touch t, Minecraft mc) {
		if (t.finger() != 0) return;
		float x = t.x() * layout.width, y = t.y() * layout.height;
		LocalPlayer player = mc.player;
		switch (t.type()) {
			case Touch.DOWN -> {
				touching = true;
				moved = false;
				startX = touchX = x;
				startY = touchY = y;
				int slot = layout.hit(x, y);
				dragFrom = player != null && slot >= 0 && !player.inventoryMenu.getSlot(slot).getItem().isEmpty() ? slot : -1;
			}
			case Touch.MOVE -> {
				touchX = x;
				touchY = y;
				if (Math.hypot(x - startX, y - startY) > DRAG_THRESHOLD) moved = true;
			}
			case Touch.UP -> {
				touchX = x;
				touchY = y;
				int target = layout.hit(x, y);
				int start = layout.hit(startX, startY);
				if (player != null && mc.gameMode != null && player.containerMenu == player.inventoryMenu) {
					if (moved && dragFrom >= 0 && target >= 0 && target != dragFrom) move(mc, player, dragFrom, target);
					else if (!moved && start >= 0) tap(mc, player, start);
				}
				touching = moved = false;
				dragFrom = -1;
			}
			default -> {
			}
		}
	}

	private static void tap(Minecraft mc, LocalPlayer player, int slot) {
		if (slot >= CompanionLayout.HOTBAR_START && slot < CompanionLayout.OFFHAND) {
			player.getInventory().setSelectedSlot(slot - CompanionLayout.HOTBAR_START); // synced on the next tick
		} else if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) {
			click(mc, player, slot, ContainerInput.QUICK_MOVE);
		}
	}

	/** Move/swap/merge from one slot to another with plain clicks, like a player would. */
	private static void move(Minecraft mc, LocalPlayer player, int from, int to) {
		InventoryMenu menu = player.inventoryMenu;
		click(mc, player, from, ContainerInput.PICKUP); // pick up the stack
		click(mc, player, to, ContainerInput.PICKUP);   // drop it (swaps if occupied)
		// Whatever is still on the cursor (the swapped item, a merge remainder,
		// or an item the target refused) goes back to the now-empty source.
		if (!menu.getCarried().isEmpty()) click(mc, player, from, ContainerInput.PICKUP);
		if (!menu.getCarried().isEmpty()) {
			// Source got refilled meanwhile (should not happen): put it anywhere.
			for (int slot = CompanionLayout.INV_START; slot < CompanionLayout.OFFHAND && !menu.getCarried().isEmpty(); slot++)
				if (menu.getSlot(slot).getItem().isEmpty()) click(mc, player, slot, ContainerInput.PICKUP);
		}
	}

	private static void click(Minecraft mc, LocalPlayer player, int slot, ContainerInput input) {
		mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, 0, input, player);
	}

	private static Identifier id(String path) {
		return Identifier.withDefaultNamespace(path);
	}
}
