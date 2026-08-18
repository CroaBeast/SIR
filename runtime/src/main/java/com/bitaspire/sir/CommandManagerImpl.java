package com.bitaspire.sir;

import com.bitaspire.sir.command.CommandFile;
import com.bitaspire.sir.command.CommandManager;
import com.bitaspire.sir.command.CommandProvider;
import com.bitaspire.sir.command.ProviderInformation;
import com.bitaspire.sir.command.SIRCommand;
import com.bitaspire.sir.command.SettingsService;
import com.bitaspire.sir.command.StandaloneProvider;
import com.bitaspire.sir.module.ModuleManager;
import com.bitaspire.sir.module.SIRModule;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.croabeast.command.Synchronizer;
import me.croabeast.common.gui.ChestBuilder;
import me.croabeast.scheduler.GlobalScheduler;
import me.croabeast.scheduler.GlobalTask;
import me.croabeast.takion.logger.LogLevel;
import org.apache.commons.lang.StringUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

class CommandManagerImpl extends BaseManager<ProviderInformation> implements CommandManager {

    private final RuntimeDiagnostics startupDiagnostics;
    private final ModuleManager moduleManager;
    private final CommandRegistry registry;
    private final CommandStateStore stateStore;
    private final CommandMenu menu;

    @Getter
    private final Synchronizer synchronizer;

    private final Map<String, LoadedProvider> providers = new LinkedHashMap<>();
    private final Map<String, DeferredProvider> deferredProviders = new LinkedHashMap<>();
    private final Set<SIRModule> processedModules = Collections.newSetFromMap(new IdentityHashMap<>());

    CommandManagerImpl(SIRApi api, RuntimeDiagnostics startupDiagnostics) {
        super(api);
        this.startupDiagnostics = startupDiagnostics;
        moduleManager = api.getModuleManager();
        registry = new CommandRegistry(this);
        stateStore = new CommandStateStore();
        menu = new CommandMenu(api);
        synchronizer = new Synchronizer() {

            private GlobalTask task;

            private void cancel0(boolean reassign) {
                if (task == null) return;

                task.cancel();
                if (reassign) task = null;
            }

            @Override
            public void sync() {
                if (!api.getPlugin().isEnabled()) {
                    cancel();
                    return;
                }

                GlobalScheduler scheduler = api.getScheduler();
                scheduler.runTask(() -> {
                    cancel0(false);
                    task = scheduler.runTaskLater(() -> {
                        task = null;
                        Synchronizer.syncCommands();
                    }, 1L);
                });
            }

            @Override
            public void cancel() {
                cancel0(true);
            }
        };
    }

    @RequiredArgsConstructor
    static final class LoadedProvider {
        final ProviderLoader loader;
        final CommandProvider provider;
        final ProviderInformation information;
    }

    static final class DeferredProvider {
        final File jarFile;
        final String[] dependencies;

        DeferredProvider(File jarFile, String[] dependencies) {
            this.jarFile = jarFile;
            this.dependencies = dependencies;
        }
    }

    @RequiredArgsConstructor
    private static final class CommandContext {
        final SIRCommand command;
        final String nameKey;
        final ConfigurationSection section;
        final boolean depends;
        final String parentName;
        final boolean override;
    }

    static String key(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Override String extensionFolder() { return "commands"; }
    @Override String extensionType() { return "command"; }
    @Override String ymlFileName() { return "commands.yml"; }
    @Override ProviderInformation parseInformation(YamlConfiguration config) { return new ProviderInformation(config); }
    @Override void onBundledJarExtracted(File jar) { load(jar, false); }

    @Override
    void log(LogLevel level, String... messages) {
        if (startupDiagnostics != null && startupDiagnostics.isCollecting()) {
            startupDiagnostics.command(level, messages);
            if (startupDiagnostics.notLogToConsole(level)) return;
        }
        super.log(level, messages);
    }

    private ProviderInformation readFile(InputStream stream, String source) {
        if (stream == null) {
            return null;
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new ProviderInformation(YamlConfiguration.loadConfiguration(reader));
        } catch (Exception e) {
            startupFailures.add(source);
            log(LogLevel.ERROR, "Failed to read commands.yml from " + source);
            e.printStackTrace();
            return null;
        }
    }

    private void registerProvider(CommandProvider provider, ProviderInformation information, boolean syncCommands) {
        if (provider == null || information == null) return;

        CommandStateStore.ProviderState state = ensureProviderState(information.getName());
        Set<SIRCommand> declaredCommands = provider.getCommands();
        if (declaredCommands.isEmpty()) {
            startupFailures.add(information.getName());
            log(LogLevel.WARN, "No commands declared for provider '" + information.getName() + "'.");
            return;
        }

        List<CommandContext> pending = new ArrayList<>();
        for (SIRCommand command : declaredCommands) {
            CommandContext context = buildContext(command, information, state);
            if (context == null) continue;

            if (context.depends) pending.add(context);
            else applyAndRegister(context, null, syncCommands);
        }

        for (CommandContext context : pending) {
            SIRCommand parent = getCommand(context.parentName);
            if (parent == null) {
                startupSkipped.add(context.command.getName());
                log(LogLevel.WARN, "Command '" + context.command.getName() + "' depends on '"
                        + context.parentName + "', but it isn't loaded.");
                continue;
            }

            if (!parent.isEnabled()) {
                startupSkipped.add(context.command.getName());
                log(LogLevel.INFO, "Command '" + context.command.getName() + "' depends on disabled command '"
                        + context.parentName + "', skipping.");
                continue;
            }

            applyAndRegister(context, parent, syncCommands);
        }
    }

    @Nullable
    private CommandContext buildContext(SIRCommand command,
                                        ProviderInformation information,
                                        CommandStateStore.ProviderState state) {
        if (command == null) return null;

        String rawName = StringUtils.trimToNull(command.getName());
        if (rawName == null) {
            startupFailures.add(information.getName());
            log(LogLevel.WARN, "Command with empty name found in provider '"
                    + information.getName() + "', skipping.");
            return null;
        }

        String nameKey = key(rawName);
        if (information.hasNoCommand(nameKey)) {
            startupFailures.add(information.getName());
            log(LogLevel.WARN, "Command '" + rawName + "' not declared in commands.yml, skipping.");
            return null;
        }

        ConfigurationSection section = information.getCommandSection(nameKey);
        if (section == null) {
            startupFailures.add(information.getName());
            log(LogLevel.WARN, "Command '" + rawName + "' section is missing in commands.yml, skipping.");
            return null;
        }

        boolean depends = section.getBoolean("depends.enabled")
                && StringUtils.isNotBlank(section.getString("depends.parent"));
        return new CommandContext(
                command,
                nameKey,
                section,
                depends,
                section.getString("depends.parent"),
                resolveOverrideState(state, nameKey, section)
        );
    }

    private void applyAndRegister(CommandContext context, @Nullable SIRCommand parent, boolean syncCommands) {
        context.command.applyFile(new CommandFile(context.nameKey, context.section, parent, context.override));
        registry.register(context.command, syncCommands);
    }

    public void loadFromModule(@NotNull SIRModule module, boolean syncCommands) {
        loadEmbeddedProvider(module, module.getName(), processedModules, syncCommands);
    }

    protected final <T> void loadEmbeddedProvider(@NotNull T owner,
                                                  @NotNull String sourceName,
                                                  @NotNull Set<T> processed,
                                                  boolean syncCommands) {
        if (!processed.add(owner)) return;

        InputStream stream = owner.getClass().getClassLoader().getResourceAsStream("commands.yml");
        ProviderInformation information = readFile(stream, sourceName);
        if (information == null) return;

        if (!(owner instanceof CommandProvider)) {
            log(LogLevel.WARN, "Provider '" + sourceName + "' contains commands.yml but is not a CommandProvider.");
            return;
        }

        CommandStateStore.ProviderState state = ensureProviderState(information.getName());
        if (!state.enabled) {
            log(LogLevel.INFO, "Command provider '" + information.getName()
                    + "' is disabled, skipping registration.");
            return;
        }

        registerProvider((CommandProvider) owner, information, syncCommands);
    }

    private ProviderInformation readExternalCommandsFile(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("commands.yml");
            if (entry == null) {
                startupSkipped.add(jarFile.getName());
                log(LogLevel.WARN, "commands.yml not found in " + jarFile.getName() + ", skipping.");
                return null;
            }

            try (InputStream stream = jar.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new ProviderInformation(YamlConfiguration.loadConfiguration(reader));
            }
        } catch (Exception e) {
            startupFailures.add(jarFile.getName());
            log(LogLevel.ERROR, "Failed to read commands.yml from " + jarFile.getName());
            e.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public void load(File jarFile, boolean syncCommands) {
        log(LogLevel.INFO, "Loading command provider from " + jarFile.getName() + "...");

        ProviderInformation information = readExternalCommandsFile(jarFile);
        if (information == null) return;

        String providerKey = key(information.getName());
        if (providers.containsKey(providerKey)) {
            log(LogLevel.WARN, "Command provider '" + information.getName() + "' already loaded, skipping.");
            return;
        }

        try {
            ProviderLoader loader = new ProviderLoader(api, jarFile.toURI().toURL());
            Class<?> clazz = Class.forName(information.getMain(), true, loader);
            if (!CommandProvider.class.isAssignableFrom(clazz)) {
                startupFailures.add(information.getName());
                log(LogLevel.ERROR, "Main class '" + information.getMain()
                        + "' does not extend CommandProvider, skipping...");
                loader.close();
                return;
            }

            CommandProvider provider = (CommandProvider) clazz.getDeclaredConstructor().newInstance();
            if (provider instanceof StandaloneProvider)
                ((SIRExtension<ProviderInformation>) provider).init(api, loader, information);

            if (provider instanceof PluginDependant) {
                PluginDependant dependant = (PluginDependant) provider;
                if (deferPluginDependants || !dependant.areDependenciesEnabled()) {
                    deferProvider(information.getName(), jarFile, dependant.getDependencies());
                    loader.close();
                    return;
                }
            }

            providers.put(providerKey, new LoadedProvider(loader, provider, information));
            if (!provider.isEnabled()) {
                if (provider instanceof StandaloneProvider)
                    ((SIRExtension<?>) provider).setRegistered(false);

                log(LogLevel.INFO, "Command provider '" + information.getName()
                        + "' is disabled, skipping registration.");
                return;
            }

            if (!provider.register()) {
                startupFailures.add(information.getName());
                log(LogLevel.WARN, "Failed to register command provider '" + information.getName() + "'.");
                unload(provider, syncCommands);
                return;
            }

            if (provider instanceof StandaloneProvider)
                ((SIRExtension<?>) provider).setRegistered(true);

            registerProvider(provider, information, syncCommands);
        } catch (Exception e) {
            startupFailures.add(jarFile.getName());
            log(LogLevel.ERROR, "Failed to load command provider from " + jarFile.getName());
            e.printStackTrace();
        }
    }

    public void loadAll() {
        clearStartupStats();
        deferredProviders.clear();
        loadStates();
        loadBundledJars(api.getConfiguration().loadDefaultJars("commands"));

        moduleManager.getModules().forEach(module -> loadFromModule(module, false));

        File directory = new File(api.getPlugin().getDataFolder(), "commands");
        if (!directory.exists() && !directory.mkdirs()) {
            log(LogLevel.WARN, "Could not create commands directory: " + directory.getPath());
            return;
        }

        File[] jars = directory.listFiles((ignored, name) -> name.endsWith(".jar"));
        if (jars != null) {
            deferPluginDependants = true;
            for (File jar : jars) load(jar, false);
            deferPluginDependants = false;
            retryDeferredProviders(null, false);
        } else {
            log(LogLevel.INFO, "No command providers found in " + directory.getPath());
        }

        recordDeferredProviderRequirements();
        synchronizer.sync();
    }

    private void deferProvider(String providerName, File jarFile, String[] dependencies) {
        String providerKey = key(providerName);
        if (deferredProviders.containsKey(providerKey) || providers.containsKey(providerKey)) return;

        deferredProviders.put(providerKey, new DeferredProvider(jarFile, dependencies));
        log(LogLevel.INFO, "Command provider '" + providerName + "' deferred until its dependencies are ready.");
    }

    public void retryDeferredProviders(String pluginName, boolean syncCommands) {
        if (deferredProviders.isEmpty()) return;

        Iterator<Map.Entry<String, DeferredProvider>> iterator = deferredProviders.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, DeferredProvider> entry = iterator.next();
            DeferredProvider deferred = entry.getValue();
            if (pluginName != null && !matchesDependency(pluginName, deferred.dependencies)) continue;

            load(deferred.jarFile, syncCommands);
            if (providers.containsKey(entry.getKey())) iterator.remove();
        }
    }

    private void recordDeferredProviderRequirements() {
        if (deferredProviders.isEmpty()) return;

        for (Map.Entry<String, DeferredProvider> entry : deferredProviders.entrySet()) {
            startupSkipped.add(entry.getKey());
            if (startupDiagnostics != null)
                startupDiagnostics.commandRequirement(entry.getKey(), entry.getValue().dependencies);
        }
    }

    private boolean matchesDependency(String pluginName, String[] dependencies) {
        if (pluginName == null || dependencies == null) return true;
        for (String dependency : dependencies)
            if (dependency != null && dependency.equalsIgnoreCase(pluginName)) return true;
        return false;
    }

    @NotNull
    public Set<SIRCommand> getCommands() {
        return registry.getAll();
    }

    public SIRCommand getCommand(String name) {
        return registry.get(name);
    }

    public SettingsService getSettingsService() {
        LoadedProvider loaded = getLoadedProvider("SettingsProvider");
        if (loaded == null)
            for (LoadedProvider entry : providers.values())
                if (entry.provider instanceof SettingsService) {
                    loaded = entry;
                    break;
                }

        return loaded != null && loaded.provider instanceof SettingsService
                ? (SettingsService) loaded.provider
                : null;
    }

    @NotNull
    public Collection<CommandProvider> getProviders() {
        return providers.values().stream().map(entry -> entry.provider).collect(Collectors.toList());
    }

    @NotNull
    public Set<String> getProviderNames() {
        Set<String> names = new LinkedHashSet<>();
        for (LoadedProvider provider : providers.values())
            names.add(provider.information.getName());
        return names;
    }

    public ProviderInformation getInformation(String name) {
        LoadedProvider loaded = getLoadedProvider(name);
        return loaded == null ? null : loaded.information;
    }

    @NotNull
    public Set<String> getProviderCommands(String providerName) {
        ProviderInformation information = getInformation(providerName);
        return information == null || information.getCommands().isEmpty()
                ? Collections.emptySet()
                : new LinkedHashSet<>(information.getCommands().keySet());
    }

    public boolean updateProviderEnabled(String providerName, boolean enabled, boolean syncCommands) {
        LoadedProvider loaded = getLoadedProvider(providerName);
        if (loaded == null) return false;

        ProviderInformation information = loaded.information;
        if (isProviderEnabled(information.getName()) == enabled) return true;

        CommandProvider provider = loaded.provider;
        if (provider instanceof StandaloneProvider) {
            StandaloneProvider standalone = (StandaloneProvider) provider;
            MenuToggleable.Button button = standalone.getButton();

            if (button != null) {
                if (button.isEnabled() != enabled && !button.toggleAll()) return false;
            } else {
                if (!(enabled ? provider.register() : provider.unregister())) return false;
                ((SIRExtension<?>) provider).setRegistered(enabled);
            }

            ((SIRExtension<?>) provider).setEnabledState(enabled);
        }

        if (enabled) registerProvider(provider, information, syncCommands);
        else unload(provider, syncCommands);

        setProviderEnabled(information.getName(), enabled);
        return true;
    }

    public Boolean getCommandOverride(String providerName, String commandKey) {
        LoadedProvider loaded = getLoadedProvider(providerName);
        if (loaded == null || StringUtils.isBlank(commandKey)) return null;
        return ensureProviderState(loaded.information.getName()).overrides.get(key(commandKey));
    }

    public boolean updateCommandOverride(String providerName,
                                         String commandKey,
                                         boolean override,
                                         boolean syncCommands) {
        throw new UnsupportedOperationException("Command override management is only available in SIR+");
    }

    @NotNull
    public ChestBuilder getMenu() {
        List<StandaloneProvider> standaloneProviders = providers.values().stream()
                .map(entry -> entry.provider)
                .filter(StandaloneProvider.class::isInstance)
                .map(StandaloneProvider.class::cast)
                .collect(Collectors.toList());
        return menu.build(standaloneProviders);
    }

    public void openOverrideMenu(@NotNull StandaloneProvider provider,
                                 @NotNull InventoryClickEvent event,
                                 boolean syncCommands) {
        event.setCancelled(true);
        api.getSender().setTargets(event.getWhoClicked())
                .setLogger(!(event.getWhoClicked() instanceof Player))
                .send("<P> &cThis option is only available on &fSIR+&c.");
    }

    public boolean isEnabled(String name) {
        SIRCommand command = getCommand(name);
        return command != null && command.isEnabled();
    }

    public void unload(String name, boolean syncCommands) {
        registry.unload(name, syncCommands);
    }

    public void unload(CommandProvider provider, boolean syncCommands) {
        if (provider == null) return;

        for (SIRCommand command : provider.getCommands()) {
            if (command == null || StringUtils.isBlank(command.getName())) continue;
            if (getCommand(command.getName()) != null) unload(command.getName(), syncCommands);
        }

        onEmbeddedProviderUnloaded(provider);
    }

    protected void onEmbeddedProviderUnloaded(CommandProvider provider) {
        if (provider instanceof SIRModule) processedModules.remove(provider);
    }

    protected void clearProcessedEmbeddedProviders() {
        processedModules.clear();
    }

    public void unloadAll() {
        for (String name : new ArrayList<>(registry.getNames()))
            if (registry.contains(name)) unload(name, false);

        for (LoadedProvider entry : providers.values()) {
            try {
                if (entry.provider.unregister() && entry.provider instanceof StandaloneProvider)
                    ((SIRExtension<?>) entry.provider).setRegistered(false);
            } catch (Exception e) {
                log(LogLevel.ERROR, "Failed to unregister command provider '"
                        + entry.information.getName() + "'.");
                e.printStackTrace();
            }

            if (entry.loader == null) continue;
            try {
                entry.loader.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        providers.clear();
        deferredProviders.clear();
        clearProcessedEmbeddedProviders();
        registry.clear();
        synchronizer.sync();
    }

    public void saveStates() {
        File directory = new File(api.getPlugin().getDataFolder(), "commands");
        if (!directory.exists() && !directory.mkdirs()) {
            log(LogLevel.WARN, "Could not create commands directory: " + directory.getPath());
            return;
        }

        File file = new File(directory, "states.yml");
        try {
            stateStore.toConfiguration().save(file);
        } catch (Exception e) {
            log(LogLevel.ERROR, "Failed to save command states to " + file.getPath());
            e.printStackTrace();
        }
    }

    public void setProviderEnabled(String main, boolean enabled) {
        stateStore.setEnabled(main, enabled);
    }

    public boolean isProviderEnabled(String main) {
        return stateStore.isEnabled(main);
    }

    private void loadStates() {
        stateStore.load(new File(api.getPlugin().getDataFolder(),
                "commands" + File.separator + "states.yml"));
    }

    @Nullable
    protected final LoadedProvider getLoadedProvider(String name) {
        String normalized = key(name);
        return normalized.isEmpty() ? null : providers.get(normalized);
    }

    protected final CommandStateStore.ProviderState ensureProviderState(String providerName) {
        return stateStore.ensure(providerName);
    }

    protected final boolean resolveOverrideState(CommandStateStore.ProviderState state,
                                                 String commandKey,
                                                 ConfigurationSection section) {
        return stateStore.resolveOverride(state, commandKey, section);
    }

    @Nullable
    protected final SIRCommand getProviderCommand(CommandProvider provider, String commandKey) {
        if (provider == null || StringUtils.isBlank(commandKey)) return null;

        for (SIRCommand command : provider.getCommands()) {
            if (command != null && commandKey.equalsIgnoreCase(command.getName())) return command;
        }
        return null;
    }

    protected final boolean applyCommandOverride(LoadedProvider loaded,
                                                 String commandKey,
                                                 boolean override,
                                                 boolean syncCommands) {
        if (loaded == null || StringUtils.isBlank(commandKey)) return false;

        String normalized = key(commandKey);
        ConfigurationSection section = loaded.information.getCommandSection(normalized);
        if (section == null) return false;

        ensureProviderState(loaded.information.getName()).overrides.put(normalized, override);
        SIRCommand command = getProviderCommand(loaded.provider, normalized);
        if (command == null) return true;

        SIRCommand parent = null;
        if (section.getBoolean("depends.enabled")) {
            String parentName = section.getString("depends.parent");
            if (StringUtils.isNotBlank(parentName)) parent = getCommand(parentName);
        }

        command.applyFile(new CommandFile(normalized, section, parent, override));
        if (!registry.contains(normalized)) return true;

        try {
            command.unregister(syncCommands);
            command.register(syncCommands);
        } catch (Exception e) {
            log(LogLevel.ERROR, "Failed to re-register command '" + commandKey + "' after override change.");
            e.printStackTrace();
        }
        return true;
    }
}
