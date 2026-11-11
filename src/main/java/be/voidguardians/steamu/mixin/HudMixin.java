package be.voidguardians.steamu.mixin;

import be.voidguardians.steamu.render.CompanionRenderer;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A screen drawn on the Deck must not flush the PC HUD's deferred subtitles. */
@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractDeferredSubtitles", at = @At("HEAD"), cancellable = true)
	private void steamu$notForDeck(CallbackInfo ci) {
		if (CompanionRenderer.INSTANCE.extracting()) ci.cancel();
	}
}
