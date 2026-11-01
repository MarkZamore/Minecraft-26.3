package lanmc.pauseactions.mixin;

import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Changes only the action of the existing native button, preserving its identity. */
@Mixin(value = Button.class, remap = false)
public interface ButtonActionAccessor {
    @Accessor(value = "onPress", remap = false)
    Button.OnPress lanmc$getOnPress();

    @Mutable
    @Accessor(value = "onPress", remap = false)
    void lanmc$setOnPress(Button.OnPress action);
}
