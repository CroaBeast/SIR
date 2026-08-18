package com.bitaspire.sir.command;

import lombok.Getter;
import lombok.experimental.UtilityClass;
import me.croabeast.command.BukkitCommand;
import me.croabeast.command.TabBuilder;
import me.croabeast.common.CollectionBuilder;
import me.croabeast.common.util.ArrayUtils;
import me.croabeast.file.ConfigurableFile;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.user.UserManager;
import me.croabeast.takion.TakionLib;
import me.croabeast.takion.message.MessageSender;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;

/**
 * Base class for all SIR commands.
 *
 * <p> Extends {@code BukkitCommand} with SIR-specific permission checking, language file
 * integration, and sub-command support. Configuration (name, permission, aliases, etc.) is
 * applied at load time via {@link #applyFile(CommandFile)}.
 *
 * <p> Subclasses must implement {@link #getCompletionBuilder()} to provide tab-completion.
 */
public abstract class SIRCommand extends BukkitCommand {

    /** The language file used to resolve message keys for this command. */
    @Getter
    private final ConfigurableFile lang;

    /** Convenience reference to the active {@link SIRApi} instance. */
    protected final SIRApi api;

    /** Convenience reference to the TakionLib instance for messaging/logging. */
    protected final TakionLib lib;

    private CommandFile file;

    /**
     * Constructs a new command with the given name and language file.
     *
     * @param name the command name (no leading slash).
     * @param lang the language file for message resolution.
     */
    public SIRCommand(String name, ConfigurableFile lang) {
        super(SIRApi.instance().getPlugin(), name);

        lib = (api = SIRApi.instance()).getLibrary();
        this.lang = lang;

        setSynchronizer(api.getCommandManager().getSynchronizer());

        setExecuteCheck((s, e) -> Utils.create(s).send("<P> &7Error executing the command " + getName() + ": &c" + e.getLocalizedMessage()));
        setCompleteCheck((s, e) -> Utils.create(s).send("<P> &7Error completing the command " + getName() + ": &c" + e.getLocalizedMessage()));
        setArgumentCheck((s, a) -> Utils.create(this, s).addPlaceholder("{arg}", a).send("wrong-arg", "<P> &cInvalid argument: &f{arg}&c."));
    }

    @ApiStatus.Internal
    public final void applyFile(CommandFile file) {
        this.file = Objects.requireNonNull(file, "Command file cannot be null");
        reloadOptions();
    }

    private void reloadOptions() {
        try {
            super.setName(file.getName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        super.setPermission(file.getPermission());
        super.setDescription(file.getDescription());
        super.setUsage(file.getUsage());
        super.setAliases(file.getAliases());
        super.setPermissionMessage(file.getPermissionMessage());
    }

    /**
     * Returns {@code true}; SIR commands are always considered enabled by default.
     *
     * @return {@code true}.
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Returns whether this command is configured to override an existing Bukkit command.
     *
     * @return {@code true} if the override flag is set in the command file.
     */
    @Override
    public boolean isOverriding() {
        return file != null && file.isOverride();
    }

    /**
     * Returns whether this command has a parent dependency declared in its configuration.
     *
     * @return {@code true} if a valid parent name is set.
     */
    public boolean hasParent() {
        return file != null && file.hasParent();
    }

    /**
     * Returns the parent {@link SIRCommand} this command depends on, if declared.
     *
     * @return the parent command, or {@code null} if none or not found.
     */
    @Nullable
    public SIRCommand getParent() {
        return api.getCommandManager().getCommand(file == null ? null : file.getParentName());
    }

    @Override
    public final boolean testPermissionSilent(@NotNull CommandSender target) {
        final UserManager manager = api.getUserManager();
        return manager.hasPermission(target, getPermission()) ||
                manager.hasPermission(target, getPermission(true));
    }

    @Override
    public final boolean isPermitted(CommandSender sender, boolean log) {
        return testPermissionSilent(sender) || (log && Utils.create(this, sender)
                .addPlaceholder("{perm}", getPermission())
                .addPlaceholder("{permission}", getPermission())
                .send("no-permission", "<P> &cYou do not have permission: &f{perm}&c."));
    }

    /**
     * Sends a "player not found" message to the sender and returns {@code false}.
     *
     * @param sender the command sender to notify.
     * @param name the player name that could not be found.
     * @return always {@code false}.
     */
    protected boolean checkPlayer(CommandSender sender, String name) {
        return Utils.create(this, sender).setLogger(false)
                .addPlaceholder("{target}", name)
                .send("not-player", "<P> &cPlayer not found: &f{target}&c.");
    }

    /**
     * Checks whether the sender has permission to execute the given sub-command.
     *
     * @param sender the command sender.
     * @param name the sub-command name as declared in the command configuration.
     * @param log if {@code true}, sends a "no permission" message when denied.
     * @return {@code true} if permitted.
     */
    protected boolean isSubCommandPermitted(CommandSender sender, String name, boolean log) {
        if (file == null) return isPermitted(sender, log);

        String permission = file.getSubCommands().get(name);
        if (permission == null)
            return isPermitted(sender, log);

        UserManager manager = api.getUserManager();
        if (manager.hasPermission(sender, permission) || manager.hasPermission(sender, getPermission(true)))
            return true;

        return log && Utils.create(this, sender)
                .addPlaceholder("{perm}", permission)
                .addPlaceholder("{permission}", permission)
                .send("no-permission", "<P> &cYou do not have permission: &f{perm}&c.");
    }

    @Override
    public abstract TabBuilder getCompletionBuilder();

    /**
     * Returns a {@link Supplier} that produces tab-completion suggestions for the given sender and arguments.
     *
     * @param sender the command sender requesting completions.
     * @param arguments the current argument array.
     * @return a supplier of completion strings; returns an empty list if no builder is defined.
     */
    @NotNull
    public Supplier<Collection<String>> generateCompletions(CommandSender sender, String[] arguments) {
        TabBuilder builder = getCompletionBuilder();
        return () -> builder == null ? new ArrayList<>() : builder.build(sender, arguments);
    }

    @Override
    public boolean register(boolean sync) {
        reloadOptions();
        if (!isRegistered()) CommandMapCleaner.purgeStaleEntries(this);
        return super.register(sync);
    }

    @Override
    public boolean unregister(boolean sync) {
        reloadOptions();
        boolean unregistered = super.unregister(sync);
        CommandMapCleaner.purgeStaleEntries(this);
        return unregistered;
    }

    @UtilityClass
    public class Utils {

        private List<String> resolve(ConfigurableFile lang, String... strings) {
            if (strings.length != 1 && strings.length != 2)
                throw new NullPointerException();

            String key = strings[0];
            if (lang == null) return ArrayUtils.toList(key);

            List<String> fallback = strings.length != 2 ?
                    new ArrayList<>() :
                    ArrayUtils.toList(strings[1]);

            List<String> result = lang.toStringList("lang." + key);
            return !result.isEmpty() ? result : fallback;
        }

        private void setSettings(MessageSender message, CommandSender sender) {
            message.setLogger(!(sender instanceof Player));
            message.setTargets(sender);
        }

        private final class SenderImpl extends MessageSender {

            private final ConfigurableFile lang;
            private final CommandSender sender;

            private SenderImpl(SIRCommand command, CommandSender sender) {
                super(SIRApi.instance().getSender());
                this.lang = command.lang;
                setSettings(this, this.sender = sender);
            }

            private SenderImpl(SenderImpl sender) {
                super(sender);
                this.lang = sender.lang;
                setSettings(this, this.sender = sender.sender);
            }

            @NotNull
            public MessageSender copy() {
                return new SenderImpl(this);
            }

            @Override
            public boolean send(String... strings) {
                return super.send(resolve(lang, strings));
            }
        }

        private final class Default extends MessageSender {

            private Default(CommandSender sender) {
                super(SIRApi.instance().getSender());
                setSettings(this, sender);
            }

            @Override
            public boolean send(String... strings) {
                return super.send(resolve(null, strings));
            }
        }

        public MessageSender create(SIRCommand command, CommandSender sender) {
            return command != null ? new SenderImpl(command, sender) : new Default(sender);
        }

        public MessageSender create(CommandSender sender) {
            return create(null, sender);
        }

        public TabBuilder newBuilder() {
            return new TabBuilder().setPermissionPredicate(SIRApi.instance().getUserManager()::hasPermission);
        }

        public List<String> getOnlineNames() {
            return CollectionBuilder.of(Bukkit.getOnlinePlayers()).map(HumanEntity::getName).toList();
        }
    }
}
