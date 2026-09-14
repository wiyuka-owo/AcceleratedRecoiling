package com.wiyuka.acceleratedrecoiling.client;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = AcceleratedRecoiling.MODID, dist = Dist.CLIENT)
public class AcceleratedRecoilingClient {
    public AcceleratedRecoilingClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ConfigScreen(parent));
    }
}
