package com.codedisaster.steamworks;

public class SteamNativeHandle {
    private final long value;
    protected SteamNativeHandle(long value) { this.value = value; }
    public static long getNativeHandle(SteamNativeHandle handle) { return handle.value; }
}
