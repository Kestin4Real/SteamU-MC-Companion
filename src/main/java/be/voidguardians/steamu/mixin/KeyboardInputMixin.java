package be.voidguardians.steamu.mixin;

import be.voidguardians.steamu.input.ControllerHandler;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Analog movement from the Deck's left stick (instead of WASD's all-or-nothing). */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void steamu$stickMovement(CallbackInfo ci) {
		ControllerHandler controller = ControllerHandler.INSTANCE;
		if (!controller.overridesMovement()) return;
		float left = controller.moveLeft(), forward = controller.moveForward();
		this.moveVector = new Vec2(left, forward);
		// Direction flags are also sent to the server and gate sprinting.
		Input k = this.keyPresses;
		this.keyPresses = new Input(k.forward() || forward > 0.3f, k.backward() || forward < -0.3f,
			k.left() || left > 0.3f, k.right() || left < -0.3f, k.jump(), k.shift(), k.sprint());
	}
}
