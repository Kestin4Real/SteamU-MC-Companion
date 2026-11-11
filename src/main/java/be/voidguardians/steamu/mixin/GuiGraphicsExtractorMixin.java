package be.voidguardians.steamu.mixin;

import be.voidguardians.steamu.render.CompanionRenderer;
import be.voidguardians.steamu.ui.DeckScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Screens drawn on the Deck see the Deck's GUI size (e.g. for tooltip placement). */
@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsExtractorMixin {
	@Inject(method = "guiWidth", at = @At("HEAD"), cancellable = true)
	private void steamu$deckWidth(CallbackInfoReturnable<Integer> cir) {
		if (CompanionRenderer.INSTANCE.isDeckExtractor(this) && DeckScreens.INSTANCE.isOpen())
			cir.setReturnValue(DeckScreens.INSTANCE.guiWidth());
	}

	@Inject(method = "guiHeight", at = @At("HEAD"), cancellable = true)
	private void steamu$deckHeight(CallbackInfoReturnable<Integer> cir) {
		if (CompanionRenderer.INSTANCE.isDeckExtractor(this) && DeckScreens.INSTANCE.isOpen())
			cir.setReturnValue(DeckScreens.INSTANCE.guiHeight());
	}
}
