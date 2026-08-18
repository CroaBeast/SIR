package com.bitaspire.sir;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import me.croabeast.common.gui.ChestBuilder;
import me.croabeast.takion.character.SmallCaps;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class ExtensionMenu<I extends Information, E extends SIRExtension<I>> {

    private final SIRApi api;
    private final String pluralLabel;
    private final String typeLabel;
    private final Material summaryMaterial;
    private final Function<E, I> information;
    private final BiConsumer<E, Boolean> stateUpdater;
    private final ExtensionConfigEditor<E> configEditor;

    ExtensionMenu(SIRApi api,
                  String pluralLabel,
                  String typeLabel,
                  Material summaryMaterial,
                  Function<E, I> information,
                  BiConsumer<E, Boolean> stateUpdater,
                  ExtensionConfigEditor<E> configEditor) {
        this.api = api;
        this.pluralLabel = pluralLabel;
        this.typeLabel = typeLabel;
        this.summaryMaterial = summaryMaterial;
        this.information = information;
        this.stateUpdater = stateUpdater;
        this.configEditor = configEditor;
    }

    @NotNull
    ChestBuilder build(List<E> extensions) {
        int rows = MenuVisuals.mainRowsFor(extensions.size());
        int totalPages = MenuVisuals.pageCount(extensions.size(), MenuVisuals.MAIN_ITEMS_PER_PAGE);
        String title = "&8" + SmallCaps.toSmallCaps("Loaded SIR " + pluralLabel + ":");
        ChestBuilder menu = ChestBuilder.of(api.getPlugin(), rows, title);

        MenuVisuals.addFrame(
                menu,
                api.getPlugin(),
                rows,
                totalPages,
                summaryMaterial,
                "SIR " + pluralLabel,
                "&7Loaded " + pluralLabel.toLowerCase() + ": &f" + extensions.size(),
                "&7Left-click a " + typeLabel.toLowerCase() + " to toggle it.",
                "&7Right-click opens the config editor when available."
        );
        MenuVisuals.addPageControls(menu, api.getPlugin(), rows, totalPages);

        if (extensions.isEmpty()) {
            menu.addSingleItem(
                    0, 4, 1,
                    MenuVisuals.emptyItem(api.getPlugin(), "No " + pluralLabel,
                            "No " + typeLabel.toLowerCase() + " jars are loaded right now."),
                    pane -> pane.setPriority(Pane.Priority.LOW)
            );
            return menu;
        }

        for (int index = 0; index < extensions.size(); index++) {
            int page = index / MenuVisuals.MAIN_ITEMS_PER_PAGE;
            Slot slot = MenuVisuals.mainSlot(index % MenuVisuals.MAIN_ITEMS_PER_PAGE);
            if (slot == null) continue;

            E extension = extensions.get(index);
            MenuToggleable.Button button = extension.getButton();
            if (button == null) continue;

            button.setEnabledItem(buildItem(extension, true));
            button.setDisabledItem(buildItem(extension, false));
            configureButton(extension, button);
            menu.addPane(page, slot, button);
        }

        return menu;
    }

    void openConfig(@NotNull InventoryClickEvent event, @NotNull List<E> extensions) {
        event.setCancelled(true);
        E extension = clickedExtension(event, extensions);
        if (extension != null && configEditor.hasConfig(extension)) {
            configEditor.open(extension, event.getWhoClicked());
            return;
        }

        api.getSender().setTargets(event.getWhoClicked())
                .setLogger(!(event.getWhoClicked() instanceof Player))
                .send("<P> &cCould not resolve a configurable " + typeLabel.toLowerCase() + ".");
    }

    private GuiItem buildItem(E extension, boolean enabled) {
        String secondaryAction = configEditor.hasConfig(extension) ? "open config" : null;
        return MenuVisuals.toggleItem(
                api.getPlugin(),
                information.apply(extension),
                enabled,
                typeLabel,
                "toggle " + typeLabel.toLowerCase(),
                secondaryAction
        );
    }

    private void configureButton(E extension, MenuToggleable.Button button) {
        button.allowToggle(false);
        button.setOnClick(ignored -> event -> {
            event.setCancelled(true);
            if (event.isRightClick()) {
                if (configEditor.hasConfig(extension)) configEditor.open(extension, event.getWhoClicked());
                return;
            }

            if (event.isLeftClick()) stateUpdater.accept(extension, !extension.isEnabled());
        });
    }

    @Nullable
    private E clickedExtension(InventoryClickEvent event, List<E> extensions) {
        int rawSlot = event.getRawSlot();
        int x = rawSlot % 9;
        int y = rawSlot / 9;
        if (rawSlot < 0 || x < 1 || x > 7 || y < 1 || y > 4) return null;

        int index = ((y - 1) * MenuVisuals.MAIN_ITEMS_PER_ROW) + (x - 1);
        return index >= 0 && index < extensions.size() ? extensions.get(index) : null;
    }
}
