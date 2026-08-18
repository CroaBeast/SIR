package com.bitaspire.sir;

import lombok.SneakyThrows;
import me.croabeast.command.TabBuilder;
import me.croabeast.file.ConfigurableFile;
import com.bitaspire.sir.command.CommandManager;
import com.bitaspire.sir.command.ProviderInformation;
import com.bitaspire.sir.module.ModuleManager;
import com.bitaspire.sir.module.SIRModule;
import me.croabeast.takion.message.MessageSender;
import me.croabeast.vnc.VNC;
import org.apache.commons.lang.SystemUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class MainCommand implements TabExecutor {

    private static final String PERMISSION_PREFIX = "sir.admin.";
    private static final String WILD_CARD = PERMISSION_PREFIX + "*";

    private static final List<String> SUB_COMMANDS = Arrays.asList("modules", "about", "reload", "help", "commands", "support", "migrate");
    private static final List<String> STATE_ARGUMENTS = Arrays.asList("enable", "enabled", "disable", "disabled", "toggle", "on", "off", "true", "false");

    private final SIRPlugin main;
    private final ConfigurableFile lang;

    @SneakyThrows
    MainCommand(SIRPlugin main) {
        this.main = main;
        this.lang = new ConfigurableFile(main, "commands" + File.separator + "main", "lang");
        lang.saveDefaults();
    }

    private class CommandDisplayer extends MessageSender {

        private CommandDisplayer(MessageSender sender) {
            super(sender);
        }

        private CommandDisplayer(CommandSender sender) {
            super(main.getSender());
            setLogger(!(sender instanceof Player)).setTargets(sender);
        }

        @NotNull
        public MessageSender copy() {
            return new CommandDisplayer(this);
        }

        @Override
        public boolean send(String... strings) {
            if (strings.length != 1)
                throw new NullPointerException("Needs only a single path");

            return super.send(lang.toStringList("lang." + strings[0]));
        }
    }

    boolean isProhibited(CommandSender sender, String permission) {
        if (main.getUserManager().hasPermission(sender, permission))
            return false;

        main.getSender().addPlaceholder("{perm}", permission)
                .addPlaceholder("{permission}", permission)
                .send(main.getCommandLang().toStringList("lang.no-permission"));
        return true;
    }

    private Boolean resolveState(String value, boolean current) {
        if (value == null) return !current;

        switch (value.toLowerCase(Locale.ENGLISH)) {
            case "enable":
            case "enabled":
            case "on":
            case "true":
                return true;
            case "disable":
            case "disabled":
            case "off":
            case "false":
                return false;
            case "toggle":
                return !current;
            default:
                return null;
        }
    }

    private boolean handleLegacyModules(CommandSender s, String[] args) {
        ModuleManager moduleManager = main.getModuleManager();

        MessageSender sender = main.getSender()
                .setTargets(s)
                .setLogger(!(s instanceof Player));

        if (args.length < 2)
            return sender.send(
                    "<P> &7Usage: &f/sir modules <module> [enable|disable|toggle]",
                    "<P> &7Available: &f" + String.join(", ", moduleManager.getModuleNames())
            );

        String moduleName = moduleManager.getModules().stream()
                .map(SIRModule::getName)
                .filter(name -> name.equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        if (moduleName == null)
            return sender.send("<P> &cModule not found: &f" + args[1]);

        boolean current = moduleManager.isEnabled(moduleName);
        Boolean next = resolveState(args.length > 2 ? args[2] : null, current);
        if (next == null)
            return sender.send("<P> &cInvalid state. Use: enable, disable, toggle.");

        moduleManager.updateEnabled(moduleName, next);
        moduleManager.saveStates();

        return sender.send(
                "<P> &7Module &f" + moduleName + " &7is now " + (next ? "&aenabled" : "&cdisabled") + "&7."
        );
    }

    private boolean handleLegacyCommands(CommandSender s, String[] args) {
        CommandManager commandManager = main.getCommandManager();
        MessageSender sender = main.getSender()
                .setTargets(s)
                .setLogger(!(s instanceof Player));

        if (args.length < 2)
            return sender.send(
                    "<P> &7Usage: &f/sir commands <provider> [enable|disable|toggle]",
                    "<P> &7Available: &f" + String.join(", ", commandManager.getProviderNames())
            );

        ProviderInformation info = commandManager.getInformation(args[1]);
        if (info == null)
            return sender.send("<P> &cCommand provider not found: &f" + args[1]);

        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ENGLISH) : null;
        if ("override".equals(mode))
            return sender.send("<P> &cThis option is only available on &fSIR+&c.");

        boolean current = commandManager.isProviderEnabled(info.getName());
        Boolean next = resolveState(mode, current);
        if (next == null)
            return sender.send("<P> &cInvalid state. Use: enable, disable, toggle.");

        if (!commandManager.updateProviderEnabled(info.getName(), next, true))
            return sender.send("<P> &cFailed to update provider state.");

        commandManager.saveStates();
        return sender.send(
                "<P> &7Provider &f" + info.getName() + " &7is now " + (next ? "&aenabled" : "&cdisabled") + "&7."
        );
    }

    private boolean handleMigration(String[] args, MessageSender displayer) {
        if (args.length < 2) return displayer.send("migrate.help");

        String source = args[1];
        if (!source.equalsIgnoreCase("Essentials") && !source.equalsIgnoreCase("SIR"))
            return displayer.addPlaceholder("{source}", source).send("migrate.unknown");

        MigrationService service = new MigrationService(main);
        try {
            String displaySource = source.equalsIgnoreCase("SIR") ? "SIR" : "Essentials";
            displayer.addPlaceholder("{source}", displaySource).send("migrate.start");

            MigrationService.Result result = source.equalsIgnoreCase("SIR")
                    ? service.migrateSir()
                    : service.migrateEssentialsX();
            if (!result.isOk())
                return displayer
                        .addPlaceholder("{path}", result.getPath())
                        .send("migrate.no-data");

            String backupPath = result.getBackupPath() == null ? "N/A" : result.getBackupPath();
            if (!result.getExtraBackups().isEmpty())
                backupPath = backupPath.equals("N/A")
                        ? String.join(", ", result.getExtraBackups())
                        : backupPath + ", " + String.join(", ", result.getExtraBackups());

            return displayer
                    .addPlaceholder("{users}", result.getUsers())
                    .addPlaceholder("{ignore-users}", result.getIgnoreUsers())
                    .addPlaceholder("{ignored}", result.getIgnoredEntries())
                    .addPlaceholder("{muted-users}", result.getMutedUsers())
                    .addPlaceholder("{nick-users}", result.getNickUsers())
                    .addPlaceholder("{skipped}", result.getSkipped())
                    .addPlaceholder("{expired}", result.getExpiredMutes())
                    .addPlaceholder("{invalid}", result.getInvalidUsers())
                    .addPlaceholder("{configs}", result.getConfigs())
                    .addPlaceholder("{module-states}", result.getModuleStates())
                    .addPlaceholder("{command-states}", result.getCommandStates())
                    .addPlaceholder("{backup}", backupPath)
                    .send("migrate.done");
        } catch (Exception exception) {
            return displayer.addPlaceholder("{error}", exception.getMessage())
                    .send("migrate.error");
        }
    }

    boolean sendFallback(MessageSender displayer) {
        return displayer.addPlaceholder("{version}", main.getDescription().getVersion()).send("help");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (isProhibited(sender, "sir.admin") || isProhibited(sender, WILD_CARD))
            return false;

        CommandDisplayer displayer = new CommandDisplayer(sender);
        MessageSender mainSender = main.getSender();

        if (args.length < 1) return sendFallback(displayer);

        String first;
        if (!SUB_COMMANDS.contains(first = args[0].toLowerCase(Locale.ENGLISH)))
            return sendFallback(displayer);

        if (isProhibited(sender, PERMISSION_PREFIX + first)) return false;

        Player player = sender instanceof Player ? (Player) sender : null;
        switch (first) {
            case "about":
                if (args.length != 1) return sendFallback(displayer);

                return mainSender.setTargets(player).setLogger(false).send(
                        "",
                        " &eSIR &7- &f" + main.getDescription().getVersion() + "&7:",
                        "   &8- &7Server Software: &f" + Bukkit.getName() + " " + VNC.SERVER_CLASSIC_VERSION,
                        "   &8- &7Author: &fCroaBeast",
                        "   &8- &7Brand: &fBitAspire by ZeroToil",
                        "   &8- &7Java Version: &f" + SystemUtils.JAVA_VERSION,
                        ""
                );

            case "modules":
                if (args.length == 1 && VNC.isAtLeast("1.14")) {
                    if (player == null)
                        return mainSender.send("&cThis command is only for players.");

                    main.getModuleManager().getMenu().showGui(player);
                    return true;
                }

                return handleLegacyModules(sender, args);

            case "commands":
                if (args.length == 1 && VNC.isAtLeast("1.14")) {
                    if (player == null)
                        return mainSender.send("&cThis command is only for players.");

                    main.getCommandManager().getMenu().showGui(player);
                    return true;
                }

                return handleLegacyCommands(sender, args);

            case "reload":
                if (args.length != 1) return sendFallback(displayer);

                Timer timer = Timer.create();
                lang.reload();
                main.reload();
                return displayer.addPlaceholder("{time}", timer.current()).send("reload");

            case "support":
                return args.length == 1 ?
                        displayer.addPlaceholder("{link}", "https://discord.gg/s9YFGMrjyF").send("support") :
                        sendFallback(displayer);

            case "migrate": return handleMigration(args, displayer);

            case "help":
            default: return sendFallback(displayer);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        TabBuilder builder = new TabBuilder().setPermissionPredicate(main.getUserManager()::hasPermission);

        for (String arg : SUB_COMMANDS)
            builder.addArgument(0, (s, a) -> main.getUserManager().hasPermission(s, PERMISSION_PREFIX + arg), arg);

        ModuleManager moduleManager = main.getModuleManager();
        CommandManager commandManager = main.getCommandManager();

        builder.addArguments(1, (s, a) -> a[0].equalsIgnoreCase("modules"), moduleManager.getModuleNames());
        builder.addArguments(1, (s, a) -> a[0].equalsIgnoreCase("commands"), commandManager.getProviderNames());
        builder.addArguments(2, (s, a) -> a[0].equalsIgnoreCase("modules") || a[0].equalsIgnoreCase("commands"), STATE_ARGUMENTS);

        builder.addArguments(1, (s, a) -> a[0].equalsIgnoreCase("migrate"), Arrays.asList("Essentials", "SIR"));
        return builder.build(sender, args);
    }
}
