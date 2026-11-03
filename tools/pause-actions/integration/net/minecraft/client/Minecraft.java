package net.minecraft.client;

import java.util.concurrent.Executor;
import net.minecraft.client.server.IntegratedServer;

/** Recording game boundary: no game singleton, renderer or native library. */
public final class Minecraft implements Executor {
    public static final Minecraft INSTANCE = new Minecraft();
    public IntegratedServer current;

    public static Minecraft getInstance() { return INSTANCE; }
    public IntegratedServer getSingleplayerServer() { return current; }
    public User getUser() { return new User(); }
    public void execute(Runnable action) { action.run(); }

    public static final class User {
        public String getName() { return "Bridge fixture player"; }
    }
}
