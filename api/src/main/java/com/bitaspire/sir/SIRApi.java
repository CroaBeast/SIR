package com.bitaspire.sir;

import com.bitaspire.sir.file.Config;
import com.bitaspire.sir.chat.ChatProcessor;
import com.bitaspire.sir.chat.ProcessorManager;
import me.croabeast.common.util.ArrayUtils;
import me.croabeast.scheduler.GlobalScheduler;
import com.bitaspire.sir.addon.AddonManager;
import com.bitaspire.sir.command.CommandManager;
import com.bitaspire.sir.module.ModuleManager;
import com.bitaspire.sir.user.SIRUser;
import com.bitaspire.sir.user.UserManager;
import me.croabeast.takion.TakionLib;
import me.croabeast.takion.message.MessageSender;
import me.croabeast.takion.placeholder.PlaceholderManager;
import me.croabeast.vault.chat.ChatProvider;
import me.croabeast.vault.economy.EconomyProvider;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Central API interface for the SIR plugin.
 *
 * <p> Provides access to all top-level services and managers. Use {@link #instance()} to
 * obtain the active implementation at runtime.
 */
public interface SIRApi {

    /**
     * Returns the global task scheduler used by SIR.
     *
     * @return the scheduler.
     */
    @NotNull
    GlobalScheduler getScheduler();

    /**
     * Returns the Vault chat provider.
     *
     * @return the chat provider.
     */
    @NotNull
    ChatProvider getChat();

    /**
     * Returns the Vault economy provider.
     *
     * @return the economy provider.
     */
    @NotNull
    EconomyProvider getEconomy();

    /**
     * Returns the Bukkit plugin instance backing this API.
     *
     * @return the plugin.
     */
    @NotNull
    Plugin getPlugin();

    /**
     * Returns the main plugin configuration.
     *
     * @return the configuration.
     */
    @NotNull
    Config getConfiguration();

    /**
     * Returns the module manager responsible for loading and managing SIR modules.
     *
     * @return the module manager.
     */
    @NotNull
    ModuleManager getModuleManager();

    /**
     * Returns the addon manager responsible for loading and managing SIR addons.
     *
     * @return the addon manager.
     * @throws UnsupportedOperationException if called outside of SIR+.
     */
    @NotNull
    default AddonManager getAddonManager() {
        throw new UnsupportedOperationException("AddonManager is only available in SIR+");
    }

    /**
     * Returns the user manager for accessing and managing {@link com.bitaspire.sir.user.SIRUser} instances.
     *
     * @return the user manager.
     */
    @NotNull
    UserManager getUserManager();

    /**
     * Returns the command manager responsible for loading and managing command providers.
     *
     * @return the command manager.
     */
    @NotNull
    CommandManager getCommandManager();

    /**
     * Returns the processor manager responsible for registering and executing
     * {@link ChatProcessor} instances.
     *
     * @return the processor manager.
     */
    @NotNull
    ProcessorManager getProcessorManager();

    /**
     * Returns the TakionLib instance used for messaging, logging, and placeholders.
     *
     * @return the library.
     */
    @NotNull
    TakionLib getLibrary();

    /**
     * Returns a copy of SIR's preconfigured message sender.
     *
     * <p> Unlike {@link TakionLib#getLoadedSender()}, the returned sender already carries SIR's
     * placeholder aliases, the Emojis and Tags formatters, and SIR's error prefix.
     *
     * @return a fresh sender copy.
     */
    @NotNull
    MessageSender getSender();

    /**
     * Reloads the plugin configuration and all active modules/providers.
     */
    void reload();

    /**
     * Returns the active {@link SIRApi} instance.
     *
     * @return the API instance.
     * @throws NullPointerException if the API has not been initialized yet.
     */
    @NotNull
    static SIRApi instance() {
        return Objects.requireNonNull(Api.api, "SIR's API isn't initialized yet");
    }

    /**
     * Joins array elements from {@code index} onwards into a space-separated string.
     *
     * @param index the start index (inclusive).
     * @param array the source array.
     * @return joined string, or {@code null} if the array is empty or index is out of bounds.
     */
    static String joinArray(int index, String... array) {
        if (ArrayUtils.isArrayEmpty(array) || index >= array.length)
            return null;

        StringBuilder b = new StringBuilder();

        for (int i = index; i < array.length; i++) {
            b.append(array[i]);
            if (i != array.length - 1) b.append(" ");
        }

        return b.toString();
    }

    /**
     * Executes a list of commands on behalf of a user.
     *
     * <p> Command prefixes control the executor:
     * <ul>
     *   <li>{@code [player]} - dispatched as the player.</li>
     *   <li>{@code [op]} - dispatched as the player with temporary OP.</li>
     *   <li>{@code [console]} (default) - dispatched from the console.</li>
     * </ul>
     *
     * <p> If {@code user} is online, placeholders in each command are replaced before dispatch.
     *
     * @param user the user context for placeholder replacement, or {@code null} for console-only.
     * @param commands the list of commands to execute.
     */
    static void executeCommands(SIRUser user, List<String> commands) {
        if (commands == null || commands.isEmpty()) return;

        commands.removeIf(StringUtils::isBlank);
        commands.replaceAll(String::trim);

        Player[] player = {null};
        if (user != null && user.isOnline()) {
            PlaceholderManager manager = instance().getLibrary().getPlaceholderManager();
            commands.replaceAll(s -> manager.replace(player[0] = user.getPlayer(), s));
        }

        instance().getScheduler().runTask(new Runnable() {
            private String format(String prefix, String command) {
                return (!command
                        .toLowerCase(Locale.ENGLISH)
                        .startsWith(prefix) ?
                        command :
                        command.substring(prefix.length()))
                        .trim();
            }

            @Override
            public void run() {
                for (String command : commands) {
                    final String temp = command.toLowerCase(Locale.ENGLISH);

                    if (temp.startsWith("[player]") && player[0] != null) {
                        Bukkit.dispatchCommand(player[0], format("[player]", command));
                        continue;
                    }

                    if (temp.startsWith("[op]") && player[0] != null) {
                        boolean op = player[0].isOp();
                        try {
                            if (!op) player[0].setOp(true);
                            Bukkit.dispatchCommand(player[0], format("[op]", command));
                        } finally {
                            if (!op) player[0].setOp(false);
                        }
                        continue;
                    }

                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), format("[console]", command));
                }
            }
        });
    }
}
