package be.voidguardians.steamu.mixin;

import be.voidguardians.steamu.render.CompanionRenderer;
import be.voidguardians.steamu.ui.DeckScreens;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Container screens (chest, furnace, ...) open on the Deck instead of the PC. */
@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void steamu$routeToDeck(Screen screen, CallbackInfo ci) {
		if (screen != null && DeckScreens.INSTANCE.routeToDeck(screen, CompanionRenderer.INSTANCE.deckAvailable())) ci.cancel();
	}
}
