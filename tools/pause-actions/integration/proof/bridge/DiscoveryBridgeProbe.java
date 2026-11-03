package proof.bridge;

import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import lanmc.pauseactions.SteamWorldDiscovery;
import link.e4steam.steam.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/** Actual packaged bridge and bootstrap publisher, explicit in-memory services. */
public final class DiscoveryBridgeProbe {
    public static final class Screen { }
    private static final String PREFIX = "minecraft.portable.identity.";
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static String classHash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
    private static int read(SteamRuntime runtime, Method method, Object poll) throws Exception {
        return ((Number) runtime.submitSteamTask(() -> ((Map<?, ?>) method.invoke(poll)).size())
                .get(5, TimeUnit.SECONDS)).intValue();
    }
    public static void main(String[] args) throws Exception {
        String transport = args[0], minecraft = args[1];
        Map.ofEntries(Map.entry("steamDiscoveryEnabled", "true"),
                Map.entry("steamDiscoveryTransportVersion", transport),
                Map.entry("steamDiscoveryMinecraftVersion", minecraft),
                Map.entry("minecraftClasses", Minecraft.class.getName()),
                Map.entry("minecraftGetInstanceMethods", "getInstance"),
                Map.entry("getSingleplayerServerMethods", "getSingleplayerServer"),
                Map.entry("steamDiscoveryGetUserMethods", "getUser"),
                Map.entry("steamDiscoveryUserNameMethods", "getName"),
                Map.entry("getWorldDataMethods", "getWorldData"),
                Map.entry("steamDiscoveryLevelNameMethods", "getLevelName"))
                .forEach((key, value) -> System.setProperty(PREFIX + key, value));
        Class<?> hooks = Class.forName("minecraft.portable.identity.PortableSteamDiscoveryHooks", true, null);
        check(hooks.getClassLoader() == null, "Publisher must come from bootstrap search");
        check(classHash(SteamWorldDiscovery.class).equals(args[2]), "Packaged bridge bytes differ");
        check(classHash(hooks).equals(args[3]), "Actual agent hook bytes differ");
        try (var runtime = new SteamRuntime()) {
            SteamRuntime.INSTANCE = runtime;
            var client = Minecraft.INSTANCE;
            var server = new IntegratedServer();
            client.current = server;
            Object owner = new Object();
            runtime.hostRegistration = new SteamRuntime.HostRegistration(owner, 49441);
            var social = runtime.lobbyManager;
            social.hostLobbyOwner = owner;
            social.hostLobbyId = 201;
            social.friends.ids.add(101L);
            social.friends.connects.put(101L, "e4steam-lobby:201");
            social.matchmaking.owners.put(201L, 101L);
            var values = new ConcurrentHashMap<String, String>();
            values.put("e4steam_protocol", String.valueOf(SteamProtocol.VERSION));
            values.put("e4steam_minecraft", minecraft);
            social.matchmaking.data.put(201L, values);
            Class<?> pollType = Class.forName("minecraft.portable.identity.PortableSteamDiscoveryHooks$Poll", true, null);
            var constructor = pollType.getDeclaredConstructor(Object.class);
            constructor.setAccessible(true);
            Object poll = constructor.newInstance(new Screen());
            Method read = pollType.getDeclaredMethod("readWorlds");
            read.setAccessible(true);
            check(read(runtime, read, poll) == 0, "Compatible lobby without metadata must reproduce the omission");
            check(social.matchmaking.writes.isEmpty(), "Baseline unexpectedly wrote lobby data");
            SteamWorldDiscovery.published(client, server, 49441);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (social.matchmaking.writes.size() != 3) {
                check(System.nanoTime() < deadline, "Packaged bridge did not reach the bootstrap publisher");
                Thread.sleep(5);
            }
            check(social.matchmaking.writes.equals(List.of("lanminecraft_player", "lanminecraft_world", "lanminecraft_display")),
                    "Publisher modified core transport metadata or wrote extra data");
            check(values.get("lanminecraft_player").equals("Bridge fixture player"), "Player not captured from live User");
            check(values.get("lanminecraft_world").equals("Bridge fixture world"), "World not captured from live server");
            check(values.get("lanminecraft_display").equals("1"), "Publication marker missing");
            check(read(runtime, read, poll) == 1, "Actual discovery reader still omitted published world");
            values.put("e4steam_protocol", "wrong");
            check(read(runtime, read, poll) == 0, "Protocol guard was bypassed");
            values.put("e4steam_protocol", String.valueOf(SteamProtocol.VERSION));
            values.put("e4steam_minecraft", "wrong");
            check(read(runtime, read, poll) == 0, "Minecraft version guard was bypassed");
            values.put("e4steam_minecraft", minecraft);
            social.matchmaking.owners.put(201L, 999L);
            check(read(runtime, read, poll) == 0, "Lobby owner guard was bypassed");
            social.matchmaking.owners.put(201L, 101L);
            check(read(runtime, read, poll) == 1, "Compatible owner did not restore discovery");
            System.out.println("DISCOVERY_BRIDGE_PASS {\"before\":0,\"after\":1,\"metadataWrites\":3,"
                    + "\"actualPackagedBridge\":true,\"actualBootstrapHooks\":true,"
                    + "\"protocolVersionOwnerNegatives\":true,\"nativeWorkerConfinement\":true,"
                    + "\"realSteamStarted\":false,\"gameStarted\":false}");
        }
    }
}
