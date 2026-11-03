package lanmc.pauseactions;

import com.mojang.logging.LogUtils;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.slf4j.Logger;

/** Optional notification to the launcher's existing asynchronous Steam publisher. */
public final class SteamWorldDiscovery {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PUBLISHER =
            "minecraft.portable.identity.PortableSteamDiscoveryHooks";
    private static boolean warned;

    private SteamWorldDiscovery() {
    }

    public static void published(Minecraft minecraft, IntegratedServer server, int port) {
        try {
            publisher().getMethod("published", Object.class, Object.class, int.class)
                    .invoke(null, minecraft, server, port);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            // Publication has already succeeded. Missing/older launcher support
            // must not undo hosting, change world rules or prevent UI feedback.
            // Do not print world/player names, lobby identifiers or full stacks.
            if (!warned) {
                warned = true;
                Throwable cause = failure instanceof InvocationTargetException invocation
                        ? invocation.getCause() : failure;
                LOGGER.warn("Steam world description notification unavailable ({}); LAN publication remains active",
                        cause.getClass().getSimpleName());
            }
        }
    }

    private static Class<?> publisher() throws ClassNotFoundException {
        // The actual agent appends its hook JAR to bootstrap search. Explicit
        // lookup avoids depending on NeoForge's named-module class routing.
        ClassLoader[] loaders = {null, SteamWorldDiscovery.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()};
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(PUBLISHER, true, loader);
            } catch (ClassNotFoundException absent) {
                // Allow launcher hosts which expose the same Object-only API
                // through their game or system loader instead of bootstrap.
            }
        }
        throw new ClassNotFoundException(PUBLISHER);
    }
}
