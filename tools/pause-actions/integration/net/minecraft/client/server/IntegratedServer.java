package net.minecraft.client.server;

import java.util.concurrent.Executor;

/** Only the executor and world-name reads used by the actual publisher. */
public final class IntegratedServer implements Executor {
    public WorldData getWorldData() { return new WorldData(); }
    public void execute(Runnable action) { action.run(); }

    public static final class WorldData {
        public String getLevelName() { return "Bridge fixture world"; }
    }
}
