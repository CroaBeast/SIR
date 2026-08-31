package com.bitaspire.sir.user;

import org.bukkit.entity.Player;

/**
 * Interface for managing ignore data for users in the SIR plugin.
 *
 * <p> This interface provides methods to check if a user is ignoring another user,
 * ignore or unignore users, and manage ignore settings for chat messages.
 *
 * <p> It allows for both player and SIRUser types to be ignored or unignored.
 * Additionally, it provides methods to ignore or unignore all users for chat messages.
 */
public interface IgnoreData {

    /**
     * Checks if the specified SIRUser is ignoring another user for chat messages.
     *
     * @param player the SIRUser to check
     * @param chat   whether to check for chat messages or private messages
     * @return true if the user is ignoring the specified SIRUser, false otherwise
     */
    boolean isIgnoring(SIRUser player, boolean chat);

    /**
     * Checks if the specified Player is ignoring another user for chat messages.
     *
     * @param player the Player to check
     * @param chat   whether to check for chat messages or private messages
     * @return true if the user is ignoring the specified Player, false otherwise
     */
    boolean isIgnoring(Player player, boolean chat);

    /**
     * Checks if the user is ignoring all users for chat messages.
     *
     * @param chat whether to check for chat messages or private messages
     * @return true if the user is ignoring all users, false otherwise
     */
    boolean isIgnoringAll(boolean chat);

    /**
     * Ignores the specified SIRUser for chat messages.
     *
     * @param user the SIRUser to ignore
     * @param chat whether to ignore for chat messages or private messages
     */
    void ignore(SIRUser user, boolean chat);

    /**
     * Ignores the specified Player for chat messages.
     *
     * @param player the Player to ignore
     * @param chat   whether to ignore for chat messages or private messages
     */
    void ignore(Player player, boolean chat);

    /**
     * Ignores all users for chat messages.
     * @param chat whether to ignore for chat messages or private messages
     */
    void ignoreAll(boolean chat);

    /**
     * Unignores the specified SIRUser for chat messages.
     *
     * @param user the SIRUser to unignore
     * @param chat whether to unignore for chat messages or private messages
     */
    void unignore(SIRUser user, boolean chat);

    /**
     * Unignores the specified Player for chat messages.
     *
     * @param player the Player to unignore
     * @param chat   whether to unignore for chat messages or private messages
     */
    void unignore(Player player, boolean chat);

    /**
     * Unignores all users for chat messages.
     * @param chat whether to unignore for chat messages or private messages
     */
    void unignoreAll(boolean chat);

    /**
     * Checks if the specified SIRUser is blocked, either individually or by the
     * ignore-all setting.
     *
     * @param user the SIRUser to check
     * @param chat whether to check for chat messages or private messages
     * @return true if the user is blocked, false otherwise
     */
    default boolean blocks(SIRUser user, boolean chat) {
        return isIgnoringAll(chat) || isIgnoring(user, chat);
    }
}
