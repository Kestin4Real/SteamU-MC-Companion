package be.voidguardians.steamu;

import be.voidguardians.steamu.input.ControllerHandler;
import be.voidguardians.steamu.net.ScreenClient;
import be.voidguardians.steamu.render.CompanionRenderer;
import be.voidguardians.steamu.ui.DeckScreens;
import be.voidguardians.steamu.config.CompanionConfig;
import be.voidguardians.steamu.ui.ControlsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SteamU Companion: shows your inventory, armor and vitals on a Steam Deck
 * connected through SteamU and lets you manage them by touch while playing.
 */
public class SteamUCompanion implements ClientModInitializer {
	public static final String MOD_ID = "steamu_companion";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		ScreenClient client = new ScreenClient("Minecraft (SteamU Companion)");
		CompanionRenderer.INSTANCE.init(client);
		ControllerHandler.INSTANCE.init(client);

		// Touch arrives on a network thread; apply it on the client thread.
		// Touch arrives on a network thread; it is applied on the client thread,
		// every rendered companion frame and (as a fallback) every tick.
		// "Open SteamU Controls" (unbound by default; also in Mod Menu).
		KeyMapping openControls = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.steamu_companion.open_controls", InputConstants.UNKNOWN.getValue(),
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "controls"))));
		CompanionConfig.get(); // load (or create) config/steamu_companion.json

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openControls.consumeClick()) mc.gui.setScreen(new ControlsScreen(mc.gui.screen()));
			CompanionRenderer.INSTANCE.processTouches(mc);
			DeckScreens.INSTANCE.tick(mc, CompanionRenderer.INSTANCE.deckAvailable());
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> client.shutdown());
		LOG.info("SteamU Companion loaded");
	}
}
