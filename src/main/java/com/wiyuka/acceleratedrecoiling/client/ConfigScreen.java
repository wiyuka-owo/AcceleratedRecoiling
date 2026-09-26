package com.wiyuka.acceleratedrecoiling.client;

import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class ConfigScreen extends Screen {
    private final Screen parent;
    private HeaderAndFooterLayout layout;
    private boolean enabled = FoldConfig.enableEntityCollision;

    public ConfigScreen(Screen parent) {
        super(Component.literal("Accelerated Recoiling"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(title, font);
        layout.addToContents(CycleButton.onOffBuilder(enabled).create(0, 0, 310, 20,
                Component.translatable("acceleratedrecoiling.config.enabled"), (button, value) -> enabled = value));
        LinearLayout footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
            FoldConfig.enableEntityCollision = enabled;
            FoldConfig.saveConfig();
            onClose();
        }).width(150).build());
        layout.addToFooter(footer);
        layout.visitWidgets(this::addRenderableWidget);
        layout.arrangeElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
