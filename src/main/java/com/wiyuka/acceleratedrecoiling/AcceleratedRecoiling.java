package com.wiyuka.acceleratedrecoiling;

import com.mojang.logging.LogUtils;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AcceleratedRecoiling.MODID)
public class AcceleratedRecoiling {
    public static final String MODID = "acceleratedrecoiling";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AcceleratedRecoiling(IEventBus modEventBus, ModContainer modContainer) {
        FoldConfig.loadConfig();
    }
}
