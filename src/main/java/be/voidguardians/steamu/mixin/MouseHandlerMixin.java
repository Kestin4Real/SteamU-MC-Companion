package be.voidguardians.steamu.mixin;

import be.voidguardians.steamu.input.ControllerHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the Deck's controls once per frame, right where mouse look is applied. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
	private void steamu$controller(CallbackInfo ci) {
		ControllerHandler.INSTANCE.frame(Minecraft.getInstance());
	}
}
