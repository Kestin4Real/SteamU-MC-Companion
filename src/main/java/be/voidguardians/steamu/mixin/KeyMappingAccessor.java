package be.voidguardians.steamu.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the controller "click" a key binding like a real key press would. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount")
	int steamu$getClickCount();

	@Accessor("clickCount")
	void steamu$setClickCount(int count);
}
