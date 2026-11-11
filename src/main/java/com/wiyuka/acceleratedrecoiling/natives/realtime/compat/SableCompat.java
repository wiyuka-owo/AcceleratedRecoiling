package com.wiyuka.acceleratedrecoiling.natives.realtime.compat;

import net.fabricmc.loader.api.FabricLoader;

public final class SableCompat {
    public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("sable");

    private SableCompat() {
    }
}
