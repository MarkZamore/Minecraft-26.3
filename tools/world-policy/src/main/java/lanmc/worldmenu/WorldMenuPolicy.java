package lanmc.worldmenu;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** This pack's menu policy, applied after the native screen and LAN initialization. */
@Mod(value = WorldMenuPolicy.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = WorldMenuPolicy.MOD_ID, value = Dist.CLIENT)
public final class WorldMenuPolicy {
    public static final String MOD_ID = "lanmc_world_menu";
    private static final String WORLD_OPTIONS_KEY = "options.worldOptions.button";

    public WorldMenuPolicy() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof WorldOptionsScreen worldOptions) {
            // LAN publication runs during native init. Its failure must not leave
            // world controls exposed; onClose returns to the parent without applying.
            if (Minecraft.getInstance().gui.screen() == worldOptions) {
                worldOptions.onClose();
            }
            return;
        }

        if (!(event.getScreen() instanceof PauseScreen)) {
            return;
        }

        // The event exposes a live unmodifiable view. Removal also updates rendering,
        // narration and focus through Screen.removeWidget, so take a snapshot first.
        for (var listener : List.copyOf(event.getListenersList())) {
            if (listener instanceof Button button
                    && button.getMessage().getContents() instanceof TranslatableContents contents
                    && WORLD_OPTIONS_KEY.equals(contents.getKey())) {
                event.removeListener(button);
            }
        }
    }
}
