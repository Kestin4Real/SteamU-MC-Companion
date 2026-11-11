package be.voidguardians.steamu.mixin;

import be.voidguardians.steamu.render.CompanionRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Points the companion's private GuiRenderer at its own target and window
 * size. The game's GuiRenderer is never affected: both wrappers only act
 * while CompanionRenderer is rendering with that exact instance.
 */
@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
	@WrapOperation(
		method = {"draw", "preparePictureInPicture", "getGuiScaleInvalidatingItemAtlasIfChanged", "enableScissor"},
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/GameRenderState;windowRenderState:Lnet/minecraft/client/renderer/state/WindowRenderState;")
	)
	private WindowRenderState steamu$windowState(GameRenderState state, Operation<WindowRenderState> original) {
		if (CompanionRenderer.isCompanion((GuiRenderer) (Object) this)) return CompanionRenderer.INSTANCE.window();
		return original.call(state);
	}

	@WrapOperation(
		method = "draw",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")
	)
	private RenderTarget steamu$renderTarget(GameRenderer gameRenderer, Operation<RenderTarget> original) {
		if (CompanionRenderer.isCompanion((GuiRenderer) (Object) this)) return CompanionRenderer.INSTANCE.target();
		return original.call(gameRenderer);
	}
}
