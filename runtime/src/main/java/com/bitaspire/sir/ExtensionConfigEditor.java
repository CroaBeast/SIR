package com.bitaspire.sir;

import com.bitaspire.sir.chat.ChatProcessor;
import com.github.stefvanschie.inventoryframework.gui.GuiComponent;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.AnvilGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import me.croabeast.common.gui.ButtonBuilder;
import me.croabeast.common.gui.ChestBuilder;
import me.croabeast.common.gui.ItemCreator;
import me.croabeast.prismatic.PrismaticAPI;
import me.croabeast.takion.character.SmallCaps;
import me.croabeast.takion.logger.LogLevel;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ExtensionConfigEditor<E extends SIRExtension<?>> {

    private final SIRApi api;
    private final String extensionType;
    private final String parentLabel;
    private final Supplier<ChestBuilder> parentMenu;
    private final Consumer<E> reloader;

    private final Map<UUID, BookEditSession<E>> pendingBookEdits = new HashMap<>();
    private final Map<UUID, StringEditSession<E>> pendingStringEdits = new HashMap<>();

    ExtensionConfigEditor(SIRApi api,
                          String extensionType,
                          String parentLabel,
                          Supplier<ChestBuilder> parentMenu,
                          Consumer<E> reloader) {
        this.api = api;
        this.extensionType = extensionType;
        this.parentLabel = parentLabel;
        this.parentMenu = parentMenu;
        this.reloader = reloader;

        new BookEditListener().register();
        new StringEditListener().register();
        api.getProcessorManager().register(new StringEditProcessor());
    }

    boolean hasConfig(E extension) {
        return configFile(extension).isFile() || hasBundledConfig(extension);
    }

    void open(@NotNull E extension, @NotNull HumanEntity viewer) {
        open(extension, viewer, null);
    }

    private void open(@NotNull E extension, @NotNull HumanEntity viewer, @Nullable String rootPath) {
        File configFile = ensureConfigFile(extension);
        if (configFile == null) {
            sendMessage(viewer, "This " + extensionType + " does not have a config.yml file.");
            return;
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection section = rootPath == null ? configuration : configuration.getConfigurationSection(rootPath);
        if (section == null) {
            sendMessage(viewer, "This config section no longer exists.");
            return;
        }

        List<String> keys = new ArrayList<>(section.getKeys(false));
        keys.removeIf(StringUtils::isBlank);
        keys.sort(String.CASE_INSENSITIVE_ORDER);

        int itemsPerPage = MenuVisuals.CENTER_ITEMS_PER_PAGE;
        int rows = centerRows(keys.size());
        int totalPages = MenuVisuals.pageCount(keys.size(), itemsPerPage);
        String title = "&8" + SmallCaps.toSmallCaps(extension.getName() + " Config"
                + (rootPath == null ? ":" : " - " + rootPath + ":"));
        ChestBuilder menu = ChestBuilder.of(api.getPlugin(), rows, title);
        MenuVisuals.addFrame(
                menu,
                api.getPlugin(),
                rows,
                totalPages,
                Material.WRITABLE_BOOK,
                extension.getName() + " Config",
                "&7Section: &f" + (rootPath == null ? "root" : rootPath),
                "&7Keys: &f" + keys.size(),
                "&7Use entries to edit " + extensionType + " settings."
        );

        if (keys.isEmpty()) {
            menu.addSingleItem(
                    0, 4, 1,
                    MenuVisuals.emptyItem(api.getPlugin(), "No Options", "This section has no editable keys."),
                    pane -> pane.setPriority(Pane.Priority.LOW)
            );
        }

        for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index);
            String path = rootPath == null ? key : rootPath + "." + key;
            Object value = section.get(key);

            int page = index / itemsPerPage;
            Slot slot = centerSlot(index % itemsPerPage);
            if (slot == null) continue;

            if (value instanceof Boolean) {
                boolean enabled = configuration.getBoolean(path);
                menu.addPane(page, slot, ButtonBuilder
                        .of(api.getPlugin(), slot, enabled)
                        .setItem(MenuVisuals.booleanItem(api.getPlugin(), key, true), true)
                        .setItem(MenuVisuals.booleanItem(api.getPlugin(), key, false), false)
                        .modify(button -> button.allowToggle(false))
                        .setAction(button -> click -> {
                            click.setCancelled(true);
                            configuration.set(path, !button.isEnabled());
                            save(configuration, configFile);
                            reload(extension);
                            open(extension, click.getWhoClicked(), rootPath);
                        })
                        .getValue());
            } else if (value instanceof List) {
                List<String> values = configuration.getStringList(path);
                menu.addSingleItem(
                        page, slot.getX(9), slot.getY(9),
                        MenuVisuals.configItem(
                                api.getPlugin(),
                                Material.WRITABLE_BOOK,
                                key,
                                "String list",
                                "Edit the list of values for this setting.",
                                Arrays.asList("&7Values: &f" + values.size(), "&7Path: &f" + path),
                                "open book editor",
                                click -> {
                                    click.setCancelled(true);
                                    openListEditor(extension, configFile, path, values, click.getWhoClicked(), rootPath);
                                }),
                        pane -> pane.setPriority(Pane.Priority.LOW)
                );
            } else if (value instanceof ConfigurationSection) {
                ConfigurationSection child = section.getConfigurationSection(key);
                int childCount = child == null ? 0 : child.getKeys(false).size();
                menu.addSingleItem(
                        page, slot.getX(9), slot.getY(9),
                        MenuVisuals.configItem(
                                api.getPlugin(),
                                Material.MAP,
                                key,
                                "Section",
                                "Browse and edit the settings in this section.",
                                Arrays.asList("&7Keys: &f" + childCount, "&7Path: &f" + path),
                                "open section",
                                click -> {
                                    click.setCancelled(true);
                                    open(extension, click.getWhoClicked(), path);
                                }),
                        pane -> pane.setPriority(Pane.Priority.LOW)
                );
            } else {
                String current = configuration.getString(path, "");
                menu.addSingleItem(
                        page, slot.getX(9), slot.getY(9),
                        MenuVisuals.configItem(
                                api.getPlugin(),
                                Material.PAPER,
                                key,
                                "Value",
                                "Edit the value of this setting.",
                                Arrays.asList("&7Current: &f" + MenuVisuals.preview(current), "&7Path: &f" + path),
                                "edit value",
                                click -> {
                                    click.setCancelled(true);
                                    openStringEditor(extension, configFile, path, current, click.getWhoClicked(), rootPath);
                                }),
                        pane -> pane.setPriority(Pane.Priority.LOW)
                );
            }
        }

        MenuVisuals.addPageControls(menu, api.getPlugin(), rows, totalPages);
        for (int page = 0; page < totalPages; page++) {
            if (rootPath != null) {
                String parentPath = rootPath.contains(".")
                        ? rootPath.substring(0, rootPath.lastIndexOf('.'))
                        : null;
                menu.addSingleItem(
                        page, 0, 0,
                        MenuVisuals.back(api.getPlugin(), "Previous Section",
                                "&7Return to the previous config section.", event -> {
                                    event.setCancelled(true);
                                    open(extension, event.getWhoClicked(), parentPath);
                                }),
                        pane -> pane.setPriority(Pane.Priority.LOW)
                );
            } else {
                menu.addSingleItem(
                        page, 0, 0,
                        MenuVisuals.back(api.getPlugin(), parentLabel,
                                "&7Return to the " + parentLabel.toLowerCase() + " menu.", event -> {
                                    event.setCancelled(true);
                                    parentMenu.get().showGui(event.getWhoClicked());
                                }),
                        pane -> pane.setPriority(Pane.Priority.LOW)
                );
            }
        }

        menu.showGui(viewer);
    }

    private void openStringEditor(E extension,
                                  File configFile,
                                  String key,
                                  String current,
                                  HumanEntity viewer,
                                  String rootPath) {
        if (!(viewer instanceof Player)) return;

        Player player = (Player) viewer;
        try {
            AnvilGui anvilGui = new AnvilGui(PrismaticAPI.colorize("&8" + key), api.getPlugin());
            anvilGui.setCost((short) 0);

            GuiComponent input = anvilGui.getFirstItemComponent();
            OutlinePane inputPane = new OutlinePane(1, 1);
            inputPane.addItem(ItemCreator.of(new ItemStack(Material.PAPER))
                    .modifyName("&f" + (StringUtils.isBlank(current) ? "<empty>" : current))
                    .create());
            input.addPane(Slot.fromXY(0, 0), inputPane);

            GuiComponent result = anvilGui.getResultComponent();
            GuiItem resultItem = ItemCreator.of(Material.LIME_STAINED_GLASS_PANE)
                    .modifyName("&a&lSave")
                    .modifyLore("&7Click to save the value.")
                    .setAction(click -> {
                        click.setCancelled(true);
                        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configFile);
                        configuration.set(key, coerce(anvilGui.getRenameText()));
                        save(configuration, configFile);
                        reload(extension);
                        open(extension, click.getWhoClicked(), rootPath);
                    })
                    .create(api.getPlugin());
            OutlinePane resultPane = new OutlinePane(1, 1);
            resultPane.addItem(resultItem);
            result.addPane(Slot.fromXY(0, 0), resultPane);

            anvilGui.show(viewer);
        } catch (NoClassDefFoundError error) {
            startStringChatEditor(extension, configFile, key, current, player, rootPath);
        }
    }

    private void startStringChatEditor(E extension,
                                       File configFile,
                                       String key,
                                       String current,
                                       Player player,
                                       String rootPath) {
        pendingStringEdits.put(player.getUniqueId(), new StringEditSession<>(extension, configFile, key, rootPath));
        player.closeInventory();
        player.sendMessage(PrismaticAPI.colorize("&7Type the new value for &f" + key + "&7."));
        player.sendMessage(PrismaticAPI.colorize("&7Current: &f"
                + (StringUtils.isBlank(current) ? "<empty>" : current)));
        player.sendMessage(PrismaticAPI.colorize("&7Type &c'cancel' &7to abort."));
    }

    private void openListEditor(E extension,
                                File configFile,
                                String key,
                                List<String> values,
                                HumanEntity viewer,
                                String rootPath) {
        if (!(viewer instanceof Player)) return;

        Player player = (Player) viewer;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return;

        meta.setTitle(PrismaticAPI.colorize("&8" + key));
        meta.setAuthor(player.getName());
        meta.setPages(buildBookPages(values));
        book.setItemMeta(meta);

        pendingBookEdits.put(player.getUniqueId(), new BookEditSession<>(extension, configFile, key, rootPath));
        player.openBook(book);
    }

    private void reload(E extension) {
        reloader.accept(extension);
    }

    private File configFile(E extension) {
        return new File(extension.getDataFolder(), "config.yml");
    }

    private boolean hasBundledConfig(E extension) {
        ClassLoader cl = extension.getClassLoader();
        if (cl instanceof URLClassLoader)
            return ((URLClassLoader) cl).findResource("config.yml") != null;
        return cl.getResource("config.yml") != null;
    }

    @Nullable
    private File ensureConfigFile(E extension) {
        File file = configFile(extension);
        if (file.isFile()) return file;

        if (!hasBundledConfig(extension)) return null;

        try {
            extension.saveResource("config.yml", false);
        } catch (IllegalArgumentException e) {
            api.getLibrary().getLogger().log(LogLevel.ERROR,
                    "Failed to save default config for " + extensionType + " '" + extension.getName() + "'.");
            e.printStackTrace();
            return null;
        }

        return file.isFile() ? file : null;
    }

    private void save(YamlConfiguration configuration, File configFile) {
        try {
            configuration.save(configFile);
        } catch (Exception e) {
            api.getLibrary().getLogger().log(LogLevel.ERROR,
                    "Failed to save config file: " + configFile.getPath());
            e.printStackTrace();
        }
    }

    private void sendMessage(@NotNull HumanEntity viewer, @NotNull String message) {
        api.getSender().setTargets(viewer)
                .setLogger(!(viewer instanceof Player))
                .send("<P> &c" + message);
    }

    private static int centerRows(int itemCount) {
        int rowsOfItems = (itemCount + 6) / 7;
        return Math.max(1, Math.min(4, rowsOfItems)) + 2;
    }

    @Nullable
    private static Slot centerSlot(int index) {
        int row = index / 7;
        return row >= 4 ? null : Slot.fromXY(1 + (index % 7), 1 + row);
    }

    private static List<String> buildBookPages(List<String> values) {
        List<String> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder("# One value per line");
        int lineCount = 1;

        for (String value : values) {
            if (lineCount >= 12) {
                pages.add(current.toString());
                current = new StringBuilder();
                lineCount = 0;
            }
            if (lineCount > 0) current.append("\n");
            current.append(value == null ? "" : value);
            lineCount++;
        }

        pages.add(current.toString());
        return pages;
    }

    private static List<String> parseBookValues(List<String> pages) {
        List<String> values = new ArrayList<>();
        for (String page : pages) {
            if (page == null) continue;
            for (String line : page.split("\n")) {
                if (StringUtils.isBlank(line) || line.startsWith("#")) continue;
                values.add(line.trim());
            }
        }
        return values;
    }

    private static Object coerce(String text) {
        if (text == null) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        return text;
    }

    private boolean handleStringEdit(Player player, String message) {
        StringEditSession<E> session = pendingStringEdits.remove(player.getUniqueId());
        if (session == null) return false;

        api.getScheduler().runTask(() -> {
            if ("cancel".equalsIgnoreCase(message)) {
                player.sendMessage(PrismaticAPI.colorize("&cEdit cancelled."));
                open(session.extension, player, session.rootPath);
                return;
            }

            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(session.configFile);
            configuration.set(session.key, coerce(message));
            save(configuration, session.configFile);
            reload(session.extension);
            open(session.extension, player, session.rootPath);
        });

        return true;
    }

    private final class BookEditListener extends Listener {
        @EventHandler
        void onEditBook(PlayerEditBookEvent event) {
            BookEditSession<E> session = pendingBookEdits.remove(event.getPlayer().getUniqueId());
            if (session == null) return;

            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(session.configFile);
            configuration.set(session.key, parseBookValues(event.getNewBookMeta().getPages()));
            save(configuration, session.configFile);

            api.getScheduler().runTask(() -> {
                reload(session.extension);
                open(session.extension, event.getPlayer(), session.rootPath);
            });
        }
    }

    private final class StringEditListener extends Listener {
        @EventHandler
        void onChatEdit(AsyncPlayerChatEvent event) {
            if (api.getProcessorManager().isModernPipelineActive()) return;

            if (handleStringEdit(event.getPlayer(), event.getMessage()))
                event.setCancelled(true);
        }
    }

    private final class StringEditProcessor implements ChatProcessor {
        @Override
        public int getPriority() {
            return -10000;
        }

        @Override
        public void process(@NotNull ChatProcessor.Context context) {
            if (!handleStringEdit(context.getPlayer(), context.getMessage())) return;
            context.cancel();
        }
    }

    private static final class BookEditSession<E> {
        final E extension;
        final File configFile;
        final String key;
        final String rootPath;

        BookEditSession(E extension, File configFile, String key, String rootPath) {
            this.extension = extension;
            this.configFile = configFile;
            this.key = key;
            this.rootPath = rootPath;
        }
    }

    private static final class StringEditSession<E> {
        final E extension;
        final File configFile;
        final String key;
        final String rootPath;

        StringEditSession(E extension, File configFile, String key, String rootPath) {
            this.extension = extension;
            this.configFile = configFile;
            this.key = key;
            this.rootPath = rootPath;
        }
    }
}
