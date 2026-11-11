package be.voidguardians.steamu.mixin;

import be.voidguardians.steamu.render.CompanionRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the companion GUI pass right after the game's own GUI pass. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;endFrame()V", shift = At.Shift.AFTER)
	)
	private void steamu$renderCompanion(CallbackInfo ci) {
		CompanionRenderer.INSTANCE.renderFrame((GameRenderer) (Object) this);
	}
}
