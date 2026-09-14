package com.wiyuka.acceleratedrecoiling.listeners;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.natives.NativeInterface;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@EventBusSubscriber(modid = AcceleratedRecoiling.MODID)
public class ServerStop {
    @SubscribeEvent
    public static void onServerStop(ServerStoppingEvent event) {
        NativeInterface.destroy();
    }
}
