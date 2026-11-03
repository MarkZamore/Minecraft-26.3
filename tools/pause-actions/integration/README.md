# Pack bridge to installed launcher publisher

From the pack root, with the platform's JDK selected in `JAVA_HOME`:

```text
python -B tools/pause-actions/verify_discovery_bridge.py --jar <packaged-pause-actions.jar> --agent <actual-portable-identity-adapter.jar> --cache-dir <native-verification-cache> --report <private-report.json>
```

The tool extracts `portable-identity-hooks.jar` from the supplied actual agent
and appends it to bootstrap search in two fresh JVMs. It does not run the agent's
premain or compile replacement hooks. The production `SteamWorldDiscovery.class`
is loaded from the supplied pack JAR. Both loaded class hashes, the whole agent,
embedded hooks and pack JAR are recorded in the report. Genuine Mojang logging
and SLF4J API libraries come from the official, hash-checked native build inputs.

`Minecraft`, `IntegratedServer` and Steam services in this directory are explicit
in-memory boundaries. The actual publisher reads their live player/world values
and writes metadata through the fake Steam worker. The actual `Poll.readWorlds`
first omits the compatible lobby without metadata, then lists one world after
the packaged bridge causes the three expected metadata writes. Wrong protocol,
Minecraft version and owner remain rejected. Core transport metadata is not
changed. No fixture class is shipped in the mod JAR.

The second execution changes both the fake transport resource and its matching
launcher property to a different version string. This tests that the pack bridge
has no transport-version pin. It does not certify a future e4steam interface.

This supplemental test does not start a game main, renderer, real Steam API,
socket, world or peer connection. It does not prove mod-loader discovery, a
rendered multiplayer list or discovery between two actual participants. Keep
`verify_native.py` and the separate game/LAN acceptance checks.
