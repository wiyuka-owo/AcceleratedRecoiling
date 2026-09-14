package com.wiyuka.acceleratedrecoiling.client;

import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.NativeInterface;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class ConfigScreen extends Screen {
    private static final String[] BACKENDS = {"AUTO", "GPU", "FFM", "JNI", "JAVA_SIMD", "JAVA", "JAVA_VANILLA"};

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    private final boolean snapEnableEntityCollision;
    private final boolean snapEnableEntityGetterOptimization;
    private final int snapMaxCollision;
    private final int snapGridSize;
    private final int snapDensityWindow;
    private final int snapDensityThreshold;
    private final int snapMaxThreads;
    private final String snapBackend;

    private CycleButton<Boolean> enableEntityCollisionButton;
    private CycleButton<Boolean> enableEntityGetterOptimizationButton;
    private EditBox maxCollisionBox;
    private EditBox gridSizeBox;
    private EditBox densityWindowBox;
    private EditBox densityThresholdBox;
    private EditBox maxThreadsBox;
    private CycleButton<String> backendButton;

    public ConfigScreen(Screen parent) {
        super(Component.literal("Accelerated Recoiling"));
        this.parent = parent;
        this.snapEnableEntityCollision = FoldConfig.enableEntityCollision;
        this.snapEnableEntityGetterOptimization = FoldConfig.enableEntityGetterOptimization;
        this.snapMaxCollision = FoldConfig.maxCollision;
        this.snapGridSize = FoldConfig.gridSize;
        this.snapDensityWindow = FoldConfig.densityWindow;
        this.snapDensityThreshold = FoldConfig.densityThreshold;
        this.snapMaxThreads = FoldConfig.maxThreads;
        this.snapBackend = FoldConfig.backend;
    }

    @Override
    protected void init() {
        this.layout.addTitleHeader(this.title, this.font);

        GridLayout grid = new GridLayout().rowSpacing(6).columnSpacing(8);
        GridLayout.RowHelper rows = grid.createRowHelper(2);

        this.enableEntityCollisionButton = CycleButton.onOffBuilder(FoldConfig.enableEntityCollision)
                .create(0, 0, 310, 20, Component.literal("Entity collision"), (button, value) -> {
                });
        rows.addChild(this.enableEntityCollisionButton, 2);

        this.enableEntityGetterOptimizationButton = CycleButton.onOffBuilder(FoldConfig.enableEntityGetterOptimization)
                .create(0, 0, 310, 20, Component.literal("Entity getter optimization"), (button, value) -> {
                });
        rows.addChild(this.enableEntityGetterOptimizationButton, 2);

        this.backendButton = CycleButton.<String>builder(Component::literal)
                .withValues(BACKENDS)
                .withInitialValue(normalizeBackend(FoldConfig.backend))
                .create(0, 0, 310, 20, Component.literal("Backend"), (button, value) -> {
                });
        rows.addChild(this.backendButton, 2);

        this.maxCollisionBox = intBox(FoldConfig.maxCollision);
        addLabeled(rows, "maxCollision", this.maxCollisionBox);
        this.gridSizeBox = intBox(FoldConfig.gridSize);
        addLabeled(rows, "gridSize", this.gridSizeBox);
        this.densityWindowBox = intBox(FoldConfig.densityWindow);
        addLabeled(rows, "densityWindow", this.densityWindowBox);
        this.densityThresholdBox = intBox(FoldConfig.densityThreshold);
        addLabeled(rows, "densityThreshold", this.densityThresholdBox);
        this.maxThreadsBox = intBox(FoldConfig.maxThreads);
        addLabeled(rows, "maxThreads", this.maxThreadsBox);

        this.layout.addToContents(grid);

        LinearLayout footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> cancel()).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> done()).width(150).build());
        this.layout.addToFooter(footer);

        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        cancel();
    }

    private void addLabeled(GridLayout.RowHelper rows, String name, EditBox box) {
        rows.addChild(new StringWidget(150, 20, Component.literal(name), this.font).alignLeft());
        rows.addChild(box);
    }

    private EditBox intBox(int value) {
        EditBox box = new EditBox(this.font, 150, 20, Component.empty());
        box.setValue(Integer.toString(value));
        box.setFilter(text -> text.isEmpty() || text.matches("-?\\d*"));
        return box;
    }

    private void done() {
        FoldConfig.enableEntityCollision = this.enableEntityCollisionButton.getValue();
        FoldConfig.enableEntityGetterOptimization = this.enableEntityGetterOptimizationButton.getValue();
        FoldConfig.maxCollision = parseInt(this.maxCollisionBox, this.snapMaxCollision);
        FoldConfig.gridSize = parseInt(this.gridSizeBox, this.snapGridSize);
        FoldConfig.densityWindow = parseInt(this.densityWindowBox, this.snapDensityWindow);
        FoldConfig.densityThreshold = parseInt(this.densityThresholdBox, this.snapDensityThreshold);
        FoldConfig.maxThreads = parseInt(this.maxThreadsBox, this.snapMaxThreads);
        String newBackend = this.backendButton.getValue();
        boolean backendChanged = !newBackend.equalsIgnoreCase(this.snapBackend);
        FoldConfig.backend = newBackend;
        FoldConfig.saveConfig();
        NativeInterface.applyConfig();
        if (backendChanged && NativeInterface.isInitialized()) {
            NativeInterface.destroy();
        }
        this.minecraft.setScreen(this.parent);
    }

    private void cancel() {
        FoldConfig.enableEntityCollision = this.snapEnableEntityCollision;
        FoldConfig.enableEntityGetterOptimization = this.snapEnableEntityGetterOptimization;
        FoldConfig.maxCollision = this.snapMaxCollision;
        FoldConfig.gridSize = this.snapGridSize;
        FoldConfig.densityWindow = this.snapDensityWindow;
        FoldConfig.densityThreshold = this.snapDensityThreshold;
        FoldConfig.maxThreads = this.snapMaxThreads;
        FoldConfig.backend = this.snapBackend;
        this.minecraft.setScreen(this.parent);
    }

    private static int parseInt(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String normalizeBackend(String raw) {
        if (raw == null || raw.isBlank()) {
            return "AUTO";
        }
        String normalized = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        for (String backend : BACKENDS) {
            if (backend.equals(normalized)) {
                return backend;
            }
        }
        for (NativeInterface.BackendType type : NativeInterface.BackendType.values()) {
            if (type.getDisplayName().equalsIgnoreCase(raw.trim()) || type.name().equals(normalized)) {
                return type.name();
            }
        }
        return "AUTO";
    }
}
