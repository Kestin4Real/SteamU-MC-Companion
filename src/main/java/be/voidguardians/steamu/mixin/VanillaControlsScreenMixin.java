package be.voidguardians.steamu.mixin;

import be.voidguardians.steamu.ui.ControlsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Steam Deck Controls..." button to Options → Controls. */
@Mixin(net.minecraft.client.gui.screens.options.controls.ControlsScreen.class)
public abstract class VanillaControlsScreenMixin extends OptionsSubScreen {
	private VanillaControlsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void steamu$addDeckControls(CallbackInfo ci) {
		list.addBig(Button.builder(Component.translatable("steamu_companion.controls.open"),
			b -> minecraft.gui.setScreen(new ControlsScreen(this))).width(310).build());
	}
}
