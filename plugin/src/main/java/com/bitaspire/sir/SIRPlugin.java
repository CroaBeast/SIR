package com.bitaspire.sir;

import com.bitaspire.sir.file.Config;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.AccessLevel;
import me.croabeast.common.MetricsLoader;
import me.croabeast.common.util.Exceptions;
import me.croabeast.file.ConfigurableFile;
import me.croabeast.scheduler.GlobalScheduler;
import com.bitaspire.sir.command.CommandManager;
import com.bitaspire.sir.command.CommandProvider;
import com.bitaspire.sir.command.SIRCommand;
import com.bitaspire.sir.chat.ProcessorManager;
import com.bitaspire.sir.module.ModuleManager;
import com.bitaspire.sir.module.SIRModule;
import com.bitaspire.sir.user.UserManager;
import me.croabeast.takion.TakionLib;
import me.croabeast.takion.message.MessageSender;
import me.croabeast.vnc.VNC;
import me.croabeast.vault.chat.ChatProvider;
import me.croabeast.vault.economy.EconomyProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Getter
public final class SIRPlugin extends JavaPlugin implements SIRApi {

    private GlobalScheduler scheduler;
    private ChatProvider chat;
    private EconomyProvider economy;
    private BaseLibrary library;
    private Config configuration;

    private ModuleManagerImpl moduleManager;
    private BaseUserManager userManager;
    private CommandManagerImpl commandManager;
    private ProcessorManagerImpl processorManager;
    @Getter(AccessLevel.NONE)
    private StartupDiagnostics startupDiagnostics;

    private ConfigurableFile commandLang;

    @SneakyThrows
    public void onEnable() {
        Timer timer = Timer.create();
        Api.api = this;

        scheduler = GlobalScheduler.getScheduler(this);
        chat = ChatProvider.detect();
        economy = EconomyProvider.detect();

        configuration = new ConfigImpl(this);
        startupDiagnostics = StartupDiagnostics.create(this);
        processorManager = new ProcessorManagerImpl(this);
        moduleManager = new ModuleManagerImpl(this, startupDiagnostics);
        commandManager = new CommandManagerImpl(this, startupDiagnostics);

        (new Listener() {
            @EventHandler
            private void onPluginEnable(PluginEnableEvent event) {
                String name = event.getPlugin().getName();
                moduleManager.retryDeferredModules(name, false);
                commandManager.retryDeferredProviders(name, false);
                commandManager.getSynchronizer().sync();
            }
        }).register();

        library = new BaseLibrary(this);

        userManager = new BaseUserManager(this);
        userManager.register();

        commandLang = new ConfigurableFile(this, "commands", "lang");
        commandLang.saveDefaults();

        try {
            MigrationService service = new MigrationService(this);
            MigrationService.Result result = service.migrateSir();
            if (result.isOk()) {
                getLogger().info("SIR legacy migration completed from " + result.getPath() + ".");
                getLogger().info("Migrated " + result.getUsers() + " users, "
                        + result.getConfigs() + " configs, "
                        + result.getModuleStates() + " module states, "
                        + result.getCommandStates() + " command states.");

                if (result.getBackupPath() != null)
                    getLogger().info("Legacy data backup stored at " + result.getBackupPath() + ".");
            }
        } catch (Exception exception) {
            getLogger().warning("SIR legacy migration failed: " + exception.getMessage());
        }

        PluginCommand command = getCommand("sir");
        if (command != null)
            try {
                MainCommand main = new MainCommand(this);
                command.setExecutor(main);
                command.setTabCompleter(main);
            } catch (Exception ignored) {}

        library.getGameRuleManager().load();

        moduleManager.loadAll();
        commandManager.loadAll();

        moduleManager.saveStates();
        commandManager.saveStates();

        library.reload();

        StartupReport report = createStartupReport(timer.current());
        new DelayLogger()
                .add(true, report.consoleLines.toArray(new String[0]))
                .sendLines();
        startupDiagnostics.write(
                report.summaryLines,
                report.moduleLines,
                report.commandLines,
                report.integrationLines,
                report.jsonLines
        );

        System.setProperty("bstats.relocatecheck", "false");
        MetricsLoader.initialize(this, 25264)
                .addSimplePie("hasPAPI", Exceptions.isPluginEnabled("PlaceholderAPI"))
                .addSimplePie("hasInteractive", Exceptions.isPluginEnabled("InteractiveChat"))
                .addDrillDownPie(
                        "chatPlugins", "Chat Plugins",
                        chat.getPlugin() != null ? chat.getPlugin().getName() : "None/Other"
                )
                .addDrillDownPie(
                        "economyPlugins", "Economy Plugins",
                        economy.getPlugin() != null ? economy.getPlugin().getName() : "None/Other"
                );
    }

    private StartupReport createStartupReport(long loadingTime) {
        StartupReport report = new StartupReport();

        report.consoleLines.addAll(headerLines());
        report.summaryLines.addAll(headerLines());

        appendRuntime(report);
        appendModules(report);
        appendCommands(report);
        appendIntegrations(report);
        appendStatus(report, loadingTime);
        appendJson(report, loadingTime);

        return report;
    }

    private void appendRuntime(StartupReport report) {
        addConsole(report, section("Runtime"));
        addConsole(report, row(MAIN + "[+]", "Server", LABEL, Bukkit.getName() + " " + VNC.SERVER_RAW_VERSION, VALUE));
        addConsole(report, row(MAIN + "[+]", "Java", LABEL, String.valueOf(VNC.JAVA_VERSION), VALUE));
    }

    private void appendModules(StartupReport report) {
        int total = moduleManager.getModules().size();
        int enabled = (int) moduleManager.getModules().stream().filter(SIRModule::isEnabled).count();
        int disabled = total - enabled;
        int skipped = moduleManager.getStartupSkippedCount();
        int failed = moduleManager.getStartupFailedCount();
        int registered = (int) moduleManager.getModules().stream().filter(SIRModule::isRegistered).count();
        List<String> updatedJars = moduleManager.getStartupUpdatedJars();

        addConsole(report, divider());
        addConsole(report, section("Modules"));
        addConsole(report, row(MAIN + "[+]", "Enabled",  LABEL, count(enabled,  "module"), VALUE));
        addConsole(report, row(MUTED + "[-]", "Disabled", LABEL, count(disabled, "module"), MUTED));
        addConsole(report, row(MUTED + "[-]", "Skipped",  WARN,  count(skipped,  "module"), MUTED));
        addConsole(report, failed == 0
                ? row(MAIN  + "[+]", "Failed", LABEL, count(0,      "module"), VALUE)
                : row(ERROR + "[!]", "Failed", ERROR, count(failed,  "module"), ERROR));
        if (!updatedJars.isEmpty())
            addConsole(report, row(INFO + "[i]", "Updated", LABEL, count(updatedJars.size(), "jar"), INFO));

        report.moduleLines.add("Loaded: " + total);
        report.moduleLines.add("Enabled: " + enabled);
        report.moduleLines.add("Disabled: " + disabled);
        report.moduleLines.add("Registered: " + registered);
        report.moduleLines.add("Skipped: " + skipped);
        report.moduleLines.add("Failed: " + failed);
        addNamedSection(report.moduleLines, "Skipped Modules", moduleManager.getStartupSkippedNames());
        addNamedSection(report.moduleLines, "Failed Modules", moduleManager.getStartupFailedNames());

        if (!updatedJars.isEmpty()) {
            report.moduleLines.add("");
            report.moduleLines.add("Updated Jars");
            report.moduleLines.addAll(updatedJars);
        }

        if (!moduleManager.getModules().isEmpty()) {
            report.moduleLines.add("");
            report.moduleLines.add("Loaded Modules");
            moduleManager.getModules().forEach(module -> report.moduleLines.add(
                    module.getName() + ": " +
                            (module.isEnabled() ? "enabled" : "disabled") +
                            " / " +
                            (module.isRegistered() ? "registered" : "unregistered")
            ));
        }
    }

    private void appendCommands(StartupReport report) {
        int moduleProviders = (int) moduleManager.getModules().stream()
                .filter(module -> module instanceof CommandProvider)
                .count();
        int standaloneProviders = commandManager.getProviderNames().size();
        int totalProviders = moduleProviders + standaloneProviders;
        int totalCommands = commandManager.getCommands().size();
        int enabledCommands = (int) commandManager.getCommands().stream()
                .filter(SIRCommand::isEnabled)
                .count();
        int skipped = commandManager.getStartupSkippedCount();
        int failed = commandManager.getStartupFailedCount();
        List<String> updatedJars = commandManager.getStartupUpdatedJars();

        addConsole(report, divider());
        addConsole(report, section("Commands"));
        addConsole(report, row(MAIN  + "[+]", "Registered", LABEL, count(totalCommands,  "command"),  VALUE));
        addConsole(report, row(MAIN  + "[+]", "Providers",  LABEL, count(totalProviders, "provider"), VALUE));
        addConsole(report, row(MUTED + "[-]", "Skipped",    WARN,  count(skipped,        "provider"), MUTED));
        addConsole(report, failed == 0
                ? row(MAIN  + "[+]", "Failed", LABEL, count(0,     "provider"), VALUE)
                : row(ERROR + "[!]", "Failed", ERROR, count(failed, "provider"), ERROR));
        if (!updatedJars.isEmpty())
            addConsole(report, row(INFO + "[i]", "Updated", LABEL, count(updatedJars.size(), "jar"), INFO));

        report.commandLines.add("Module Providers: " + moduleProviders);
        report.commandLines.add("Standalone Providers: " + standaloneProviders);
        report.commandLines.add("Total Providers: " + totalProviders);
        report.commandLines.add("Registered Commands: " + totalCommands);
        report.commandLines.add("Enabled Commands: " + enabledCommands);
        report.commandLines.add("Skipped Providers: " + skipped);
        report.commandLines.add("Failed Providers: " + failed);
        addNamedSection(report.commandLines, "Skipped Providers", commandManager.getStartupSkippedNames());
        addNamedSection(report.commandLines, "Failed Providers", commandManager.getStartupFailedNames());

        if (!updatedJars.isEmpty()) {
            report.commandLines.add("");
            report.commandLines.add("Updated Jars");
            report.commandLines.addAll(updatedJars);
        }

        if (moduleProviders > 0) {
            report.commandLines.add("");
            report.commandLines.add("Providers: Modules");
            moduleManager.getModules().stream()
                    .filter(module -> module instanceof CommandProvider)
                    .forEach(module -> {
                        CommandProvider provider = (CommandProvider) module;
                        report.commandLines.add(module.getName() + ": " + count(provider.getCommands().size(), "command"));
                    });
        }

        if (!commandManager.getProviderNames().isEmpty()) {
            report.commandLines.add("");
            report.commandLines.add("Providers: Standalone");
            commandManager.getProviderNames().forEach(name -> report.commandLines.add(
                    name + ": " + count(commandManager.getProviderCommands(name).size(), "command")
            ));
        }

        if (!commandManager.getCommands().isEmpty()) {
            report.commandLines.add("");
            report.commandLines.add("Commands");
            commandManager.getCommands().forEach(command -> report.commandLines.add(
                    command.getName() + ": " + (command.isEnabled() ? "enabled" : "disabled")
            ));
        }
    }

    private void appendIntegrations(StartupReport report) {
        boolean papi = Exceptions.isPluginEnabled("PlaceholderAPI");
        boolean interactive = Exceptions.isPluginEnabled("InteractiveChat");
        boolean chatHook = chat.getPlugin() != null;
        boolean economyHook = economy.getPlugin() != null;
        int active = countEnabled(papi, interactive, chatHook, economyHook);
        int missing = 4 - active;

        addConsole(report, divider());
        addConsole(report, section("Integrations"));
        addConsole(report, row(MAIN  + "[+]", "Active",  LABEL, count(active,  "hook"),          VALUE));
        addConsole(report, row(MUTED + "[-]", "Missing", LABEL, count(missing, "optional"), MUTED));

        addIntegration(report, "PlaceholderAPI: " + status(papi));
        addIntegration(report, "InteractiveChat: " + status(interactive));
        addIntegration(report, "Chat provider: " + pluginName(chat.getPlugin()));
        addIntegration(report, "Economy provider: " + pluginName(economy.getPlugin()));
    }

    private void appendStatus(StartupReport report, long loadingTime) {
        addConsole(report, divider());
        addConsole(report, section("Status"));
        addConsole(report, row(MAIN  + "[+]", "Startup",      LABEL, "complete",                       VALUE));
        addConsole(report, row(INFO + "[i]", "Loading time", LABEL, formatDuration(loadingTime),      INFO));

        if (startupDiagnostics != null && startupDiagnostics.hasDetails())
            addConsole(report, row(INFO + "[i]", "Details", LABEL, startupDiagnostics.getDetailPath(), INFO));

        addConsole(report, divider());
    }

    private void appendJson(StartupReport report, long loadingTime) {
        int totalModules = moduleManager.getModules().size();
        int enabledModules = (int) moduleManager.getModules().stream().filter(SIRModule::isEnabled).count();

        int skippedModules = moduleManager.getStartupSkippedCount();
        int failedModules = moduleManager.getStartupFailedCount();

        int totalProviders = (int) moduleManager.getModules().stream()
                .filter(module -> module instanceof CommandProvider)
                .count() + commandManager.getProviderNames().size();
        int totalCommands = commandManager.getCommands().size();

        int skippedProviders = commandManager.getStartupSkippedCount();
        int failedProviders = commandManager.getStartupFailedCount();

        report.jsonLines.add("{");
        report.jsonLines.add("  \"plugin\": \"SIR\",");
        report.jsonLines.add("  \"version\": \"" + json(getDescription().getVersion()) + "\",");
        report.jsonLines.add("  \"server\": \"" + json(Bukkit.getName() + " " + VNC.SERVER_CLASSIC_VERSION) + "\",");
        report.jsonLines.add("  \"java\": \"" + json(String.valueOf(VNC.JAVA_VERSION)) + "\",");
        report.jsonLines.add("  \"startupMs\": " + loadingTime + ",");
        report.jsonLines.add("  \"modules\": {");
        report.jsonLines.add("    \"loaded\": " + totalModules + ",");
        report.jsonLines.add("    \"enabled\": " + enabledModules + ",");
        report.jsonLines.add("    \"disabled\": " + (totalModules - enabledModules) + ",");
        report.jsonLines.add("    \"skipped\": " + skippedModules + ",");
        report.jsonLines.add("    \"failed\": " + failedModules);
        report.jsonLines.add("  },");
        report.jsonLines.add("  \"commands\": {");
        report.jsonLines.add("    \"providers\": " + totalProviders + ",");
        report.jsonLines.add("    \"registered\": " + totalCommands + ",");
        report.jsonLines.add("    \"skipped\": " + skippedProviders + ",");
        report.jsonLines.add("    \"failed\": " + failedProviders);
        report.jsonLines.add("  }");
        report.jsonLines.add("}");
    }

    private List<String> headerLines() {
        List<String> lines = new ArrayList<>();
        lines.add(MAIN + "===================================");
        lines.add(MAIN + ",d88P\"\\  888  888\"~,");
        lines.add(MAIN + "8888     888  888   \\");
        lines.add(MAIN + "`Y88b    888  888   █");
        lines.add(MAIN + " `Y88b,  888  888   /");
        lines.add(MAIN + "   8888  888  888-=\"  &7" + getDescription().getVersion());
        lines.add(MAIN + "\\_d88P'  888  888  \\_ &7by CroaBeast");
        lines.add(MAIN + "===================================");
        return lines;
    }

    private void addConsole(StartupReport report, String line) {
        report.consoleLines.add(line);
        report.summaryLines.add(line);
    }

    private void addIntegration(StartupReport report, String line) {
        report.integrationLines.add(line);
        if (startupDiagnostics != null) startupDiagnostics.integration(line);
    }

    private void addNamedSection(List<String> lines, String title, List<String> values) {
        if (values == null || values.isEmpty()) return;

        lines.add("");
        lines.add(title);
        lines.addAll(values);
    }

    private String section(String name) {
        return "&7» " + MAIN + name;
    }

    private String divider() {
        return MUTED + "-----------------------------------";
    }

    private String row(String marker, String label, String labelCode, String value, String valueCode) {
        return marker + " " + labelCode + dotted(label) + " " + valueCode + value;
    }

    private String dotted(String label) {
        StringBuilder builder = new StringBuilder(label);
        if (builder.length() < 15) builder.append(' ');
        while (builder.length() < 15) builder.append('.');
        return builder.toString();
    }

    private String count(int amount, String singular) {
        return amount + " " + singular + (amount == 1 ? "" : "s");
    }

    private int countEnabled(boolean... values) {
        int amount = 0;
        for (boolean value : values) if (value) amount++;
        return amount;
    }

    private String status(boolean enabled) {
        return enabled ? "enabled" : "missing";
    }

    private String pluginName(Plugin plugin) {
        return plugin == null ? "none" : plugin.getName();
    }

    private String formatDuration(long millis) {
        return millis < 1000L ? millis + "ms" : String.format(Locale.US, "%.2fs", millis / 1000D);
    }

    private String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final String MAIN  = "&a"; // bright green  - [+], headers, banner
    private static final String VALUE = "&f"; // white         - positive values
    private static final String LABEL = "&7"; // gray          - general labels and text
    private static final String MUTED = "&8"; // dark gray     - [-], dividers, disabled
    private static final String WARN  = "&e"; // yellow        - skipped
    private static final String INFO  = "&9"; // aqua          - [i] values (time, path)
    private static final String ERROR = "&c"; // red           - [!] failed > 0

    private static final class StartupReport {
        private final List<String> consoleLines = new ArrayList<>();
        private final List<String> summaryLines = new ArrayList<>();
        private final List<String> moduleLines = new ArrayList<>();
        private final List<String> commandLines = new ArrayList<>();
        private final List<String> integrationLines = new ArrayList<>();
        private final List<String> jsonLines = new ArrayList<>();
    }

    @Override
    public void onDisable() {
        Timer timer = Timer.create();

        DelayLogger logger = new DelayLogger();
        logger.add(true, headerLines().toArray(new String[0]));

        setManagerQuietConsole();

        if (userManager != null)
            userManager.shutdown();

        if (commandManager != null)
            commandManager.saveStates();
        if (moduleManager != null)
            moduleManager.saveStates();

        if (commandManager != null)
            commandManager.unloadAll();
        if (moduleManager != null)
            moduleManager.unloadAll();

        logger.add(true,
                row(MAIN  + "[+]", "Shutdown",      LABEL, "complete",                      VALUE),
                row(INFO  + "[i]", "Shutdown time", LABEL, formatDuration(timer.current()), INFO),
                divider()
        ).sendLines();

        HandlerList.unregisterAll(this);

        Api.api = null;
    }

    void setManagerQuietConsole() {
        commandManager.setQuietConsole(true);
        moduleManager.setQuietConsole(true);
    }

    @NotNull
    public TakionLib getLibrary() {
        return library;
    }

    @NotNull
    public MessageSender getSender() {
        return library.getSender();
    }

    @NotNull
    public Plugin getPlugin() {
        return this;
    }

    @NotNull
    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    @NotNull
    public CommandManager getCommandManager() {
        return commandManager;
    }

    @NotNull
    public UserManager getUserManager() {
        return userManager;
    }

    @NotNull
    public ProcessorManager getProcessorManager() {
        return processorManager;
    }

    @Override
    public void reload() {
        Timer timer = Timer.create();
        if (startupDiagnostics != null) startupDiagnostics.beginReload();

        try {
            commandLang.reload();

            configuration = new ConfigImpl(this);

            commandManager.saveStates();
            moduleManager.saveStates();

            commandManager.unloadAll();
            moduleManager.unloadAll();

            moduleManager.loadAll();
            commandManager.loadAll();

            library.reload();
        } finally {
            if (startupDiagnostics != null)
                startupDiagnostics.writeReload(timer.current());
        }
    }
}
