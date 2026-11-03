package link.e4steam.steam;

import com.codedisaster.steamworks.*;
import java.util.*;
import java.util.concurrent.*;

/** In-memory transport boundary; never initializes Steam or opens a socket. */
public final class SteamRuntime implements AutoCloseable {
    public static SteamRuntime INSTANCE;
    public final LobbyManager lobbyManager = new LobbyManager();
    public HostRegistration hostRegistration;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "bridge-proof-steam");
        thread.setDaemon(true);
        return thread;
    });

    public static SteamRuntime get() { return INSTANCE; }
    public void awaitReady() { }
    public CompletableFuture<Object> submitSteamTask(Callable<Object> action) {
        return CompletableFuture.supplyAsync(() -> {
            try { return action.call(); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, worker);
    }
    public void close() { worker.shutdownNow(); }
    public record HostRegistration(Object owner, int localPort) { }

    private static void steamThread() {
        if (!Thread.currentThread().getName().equals("bridge-proof-steam")) {
            throw new AssertionError("Native service boundary called outside its worker");
        }
    }

    public static final class LobbyManager {
        public final Friends friends = new Friends();
        public final Matchmaking matchmaking = new Matchmaking();
        public Object hostLobbyOwner;
        public long hostLobbyId;
    }
    public static final class Friends {
        public final List<Long> ids = new ArrayList<>();
        public final Map<Long, String> connects = new HashMap<>();
        public int getFriendCount(SteamFriends.FriendFlags flags) { steamThread(); return ids.size(); }
        public SteamID getFriendByIndex(int index, SteamFriends.FriendFlags flags) {
            steamThread(); return SteamID.createFromNativeHandle(ids.get(index));
        }
        public void requestFriendRichPresence(SteamID friend) { steamThread(); }
        public String getFriendRichPresence(SteamID friend, String key) {
            steamThread(); return connects.get(SteamNativeHandle.getNativeHandle(friend));
        }
        public String getFriendPersonaName(SteamID friend) {
            throw new AssertionError("Actual world/player metadata must be used");
        }
    }
    public static final class Matchmaking {
        public final Map<Long, Map<String, String>> data = new ConcurrentHashMap<>();
        public final Map<Long, Long> owners = new ConcurrentHashMap<>();
        public final List<String> writes = new CopyOnWriteArrayList<>();
        public void requestLobbyData(SteamID lobby) { steamThread(); }
        public String getLobbyData(SteamID lobby, String key) {
            steamThread();
            return data.getOrDefault(SteamNativeHandle.getNativeHandle(lobby), Map.of()).getOrDefault(key, "");
        }
        public SteamID getLobbyOwner(SteamID lobby) {
            steamThread();
            return SteamID.createFromNativeHandle(owners.getOrDefault(SteamNativeHandle.getNativeHandle(lobby), 0L));
        }
        public boolean setLobbyData(SteamID lobby, String key, String value) {
            steamThread();
            data.get(SteamNativeHandle.getNativeHandle(lobby)).put(key, value);
            writes.add(key);
            return true;
        }
    }
}
