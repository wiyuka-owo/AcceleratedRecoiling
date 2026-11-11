package com.wiyuka.acceleratedrecoiling.natives.realtime.compat;

import net.neoforged.fml.loading.LoadingModList;

public final class SableCompat {
    public static final boolean LOADED = LoadingModList.get().getModFileById("sable") != null;

    private SableCompat() {
    }
}
