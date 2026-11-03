package minecraft.portable.identity;

/** Test-only Object API recorder; never shipped in the component or a game. */
public final class PortableSteamDiscoveryHooks {
    public static int calls;
    public static Object minecraft;
    public static Object server;
    public static int port;
    public static boolean fail;

    private PortableSteamDiscoveryHooks() { }

    public static void published(Object client, Object host, int localPort) {
        calls++;
        minecraft = client;
        server = host;
        port = localPort;
        if (fail) throw new IllegalStateException("fixture description failure");
    }
}
