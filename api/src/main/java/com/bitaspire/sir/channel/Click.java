package com.bitaspire.sir.channel;

import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a click action attached to a chat message in a {@link ChatChannel}.
 *
 * <p> Wraps a {@link me.croabeast.prismatic.element.Click} action type and the input string that
 * is used when the player clicks the message in chat (e.g., a URL, command, or suggestion).
 */
public interface Click {

    /**
     * Returns the click action type (e.g., open URL, run command, suggest command).
     *
     * @return the click action.
     */
    @NotNull
    me.croabeast.prismatic.element.Click getAction();

    /**
     * Returns the input value associated with the click action.
     *
     * @return the input string, or {@code null} if not set.
     */
    @Nullable
    String getInput();

    /**
     * Returns whether this click action has no meaningful input.
     *
     * @return {@code true} if the input is blank or null.
     */
    default boolean isEmpty() {
        return StringUtils.isBlank(getInput());
    }
}
