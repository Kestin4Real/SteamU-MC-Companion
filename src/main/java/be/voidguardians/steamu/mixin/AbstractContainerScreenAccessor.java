package be.voidguardians.steamu.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Size of a container screen's panel, to pick a GUI scale that fits the Deck. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("imageWidth")
	int steamu$imageWidth();

	@Accessor("imageHeight")
	int steamu$imageHeight();

	@Accessor("leftPos")
	int steamu$leftPos();

	@Accessor("topPos")
	int steamu$topPos();
}
