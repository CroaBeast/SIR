package com.bitaspire.sir.module.channel;

import lombok.RequiredArgsConstructor;
import me.croabeast.common.util.ReplaceUtils;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.channel.Access;
import com.bitaspire.sir.channel.ChatChannel;
import com.bitaspire.sir.channel.Click;
import com.bitaspire.sir.module.DiscordService;
import com.bitaspire.sir.module.ModuleManager;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.prismatic.PrismaticAPI;
import me.croabeast.takion.TakionLib;
import me.croabeast.takion.channel.Channel;
import me.croabeast.prismatic.element.Element;
import me.croabeast.takion.message.MessageSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@RequiredArgsConstructor
final class Listener extends com.bitaspire.sir.Listener {

    private static final Pattern RGB_HEX_CODE = Pattern.compile("(?i)([&\\u00A7]#|\\{#)[0-9a-f]{6}}?");

    private final Channels main;

    private boolean handleEmptyMessage(MessageSender sender, String message) {
        if (main.config.allowsEmpty() || !isVisibleBlank(message))
            return false;

        sender.copy().send(main.config.getAllowEmptyMessages());
        return true;
    }

    private boolean isVisibleBlank(String message) {
        if (StringUtils.isBlank(message)) return true;

        String stripped = RGB_HEX_CODE.matcher(message).replaceAll("");
        stripped = PrismaticAPI.stripAll(stripped);
        stripped = ChatColor.stripColor(PrismaticAPI.colorize(stripped));

        return StringUtils.isBlank(stripped);
    }

    private String stripPrefix(ChatChannel channel, String message) {
        Access access = channel.getAccess();

        String prefix = access.getMatchingPrefix(message);
        if (StringUtils.isBlank(prefix) || !access.shouldStripPrefix())
            return message;

        return StringUtils.stripStart(message.substring(prefix.length()), null);
    }

    void dispatch(SIRUser user, ChatChannel channel, String message, boolean async) {
        ChatEvent chat = new ChatEvent(user, channel, message, async);
        chat.setGlobal(channel.isGlobal());
        chat.call();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled() || !main.isEnabled()) return;

        SIRUser user = main.getApi().getUserManager().getUser(event.getPlayer());
        if (user == null) return;

        if (!user.isLogged()) {
            event.setCancelled(true);
            return;
        }

        MessageSender sender = main.getApi().getSender()
                .setTargets(user.getPlayer());

        if (user.getMuteData().isMuted()) {
            sender.copy().send(main.config.getMutedMessages());
            event.setCancelled(true);
            return;
        }

        String message = event.getMessage();
        if (handleEmptyMessage(sender, message)) {
            event.setCancelled(true);
            return;
        }

        boolean async = event.isAsynchronous();

        ChatChannel routed = main.data.getAccessible(user, message, true);
        if (routed != null) {
            event.setCancelled(true);

            String routedMessage = stripPrefix(routed, message);
            if (StringUtils.isBlank(routedMessage)) {
                handleEmptyMessage(sender, routedMessage);
                return;
            }

            dispatch(user, routed, routedMessage, async);
            return;
        }

        ChatChannel channel = main.data.getFallback(user);
        if (channel == null) return;

        MessageMask mask = MessageMask.create(channel, message);
        String output = channel.formatString(user.getPlayer(), mask.getMessage(), true);
        if (main.config.useBukkitFormat()) {
            output = mask.restore(output);
            event.setFormat(Element.stripMarkup(output).replace("%", "%%"));
            return;
        }

        event.setCancelled(true);
        dispatch(user, channel, message, async);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    private void onCommand(PlayerCommandPreprocessEvent event) {
        if (!main.isEnabled()) return;

        SIRUser user = main.getApi().getUserManager().getUser(event.getPlayer());
        String[] args = event.getMessage().split(" ");

        ChatChannel channel = main.data.getAccessible(user, args[0], false);
        if (channel == null) return;

        event.setCancelled(true);

        MessageSender sender = main.getApi().getSender()
                .setTargets(event.getPlayer());

        String message = SIRApi.joinArray(1, args);
        if (StringUtils.isBlank(message)) {
            handleEmptyMessage(sender, message);
            return;
        }

        dispatch(user, channel, message, event.isAsynchronous());
    }

    @EventHandler
    private void onSIRChat(ChatEvent event) {
        if (!main.isEnabled()) return;

        TakionLib lib = main.getApi().getLibrary();

        ChatChannel channel = event.getChannel();
        Player player = event.getPlayer();

        String message = event.getMessage();
        MessageMask mask = MessageMask.create(channel, message);
        String displayMessage = mask.getMessage();

        ModuleManager manager = main.getApi().getModuleManager();
        if (channel.relayToDiscord() && manager.isEnabled("Discord")) {
            String m = lib.getPlaceholderManager().replace(player, message);
            String name = event.isGlobal() ? "global-chat" : channel.getName();

            DiscordService discord = manager.getDiscordService();
            if (discord != null)
                discord.sendMessage(
                        discord.isRestricted() ? "restricted" : name, player,
                        DiscordRelayFormatter.create(channel, event.getUser(), m)
                );
        }

        lib.getLogger().log(channel.formatString(player, message, false));
        String[] keys = channel.getChatKeys();
        String[] values = channel.getChatValues(event.getUser(), displayMessage);

        List<String> hover = channel.getStyle().getHover();
        if (hover != null && !hover.isEmpty()) {
            hover = new ArrayList<>(hover);
            hover.replaceAll(s -> lib.replace(player, ReplaceUtils.replaceEach(keys, values, s)));
        }

        Click click = channel.getStyle().getClick();
        String input = click != null ? click.getInput() : null;

        if (StringUtils.isNotBlank(input))
            input = ReplaceUtils.replaceEach(keys, values, input);

        Channel chat = lib.getChannelManager().identify("chat");

        Set<SIRUser> users = event.getRecipients();
        boolean includeSender = channel.getAudience().shouldIncludeSender();

        if (includeSender) users.add(event.getUser());
        else users.remove(event.getUser());

        for (SIRUser user : users) {
            Player p = user.getPlayer();

            String temp = channel.formatString(p, player, displayMessage, true);
            temp = chat.formatString(p, player, temp);

            Element.Builder builder = Element.parse(temp, lib.getMarkup()).toBuilder();
            if (hover != null && !hover.isEmpty()) builder.hoverAll(hover);
            if (click != null)
                builder.clickAll(click.getAction(), input);

            BaseComponent[] compiled = builder.build().bungee(lib.getMarkup().context(player));
            mask.restore(compiled);
            p.spigot().sendMessage(compiled);
        }
    }

    private static final class MessageMask {

        private final String token;
        private final String message;

        private MessageMask(String token, String message) {
            this.token = token;
            this.message = message;
        }

        private static MessageMask create(ChatChannel channel, String message) {
            String token = createToken(message);
            String masked = mask(message, token, channel.getStyle().allowsMiniMessage());

            return masked.equals(message) ? new MessageMask(null, message) : new MessageMask(token, masked);
        }

        private static String createToken(String message) {
            String base = "__SIR_LITERAL_LT_" + Integer.toHexString(System.identityHashCode(message)) + "_";
            String token;
            int index = 0;

            do {
                token = base + index++ + "__";
            } while (message.contains(token));

            return token;
        }

        private static String mask(String message, String token, boolean miniMessage) {
            if (message.indexOf('<') == -1) return message;
            if (!miniMessage) return message.replace("<", token);

            StringBuilder builder = new StringBuilder(message.length());
            for (int index = 0; index < message.length(); index++) {
                char c = message.charAt(index);
                if (c == '<' && !startsMiniMessageTag(message, index)) builder.append(token);
                else builder.append(c);
            }

            return builder.toString();
        }

        private static boolean startsMiniMessageTag(String message, int index) {
            int next = index + 1;
            if (next >= message.length()) return false;
            if (message.charAt(next) == '/') next++;
            if (next >= message.length()) return false;

            char start = message.charAt(next);
            if (start != '#' && start != '_' && !Character.isLetter(start)) return false;

            int end = message.indexOf('>', next + 1);
            int nested = message.indexOf('<', next);
            return end != -1 && (nested == -1 || nested > end);
        }

        private String getMessage() {
            return message;
        }

        private String restore(String value) {
            return token == null || value == null || value.indexOf(token) == -1 ? value : value.replace(token, "<");
        }

        private void restore(BaseComponent[] components) {
            if (token == null || components == null) return;

            for (BaseComponent component : components)
                restore(component);
        }

        private void restore(BaseComponent component) {
            if (component == null) return;

            if (component instanceof TextComponent) {
                TextComponent text = (TextComponent) component;
                text.setText(restore(text.getText()));
            }

            String insertion = component.getInsertion();
            if (insertion != null) component.setInsertion(restore(insertion));

            ClickEvent click = component.getClickEvent();
            if (click != null)
                component.setClickEvent(new ClickEvent(click.getAction(), restore(click.getValue())));

            HoverEvent hover = component.getHoverEvent();
            if (hover != null)
                restore(hover.getValue());

            List<BaseComponent> extra = component.getExtra();
            if (extra != null)
                for (BaseComponent child : extra)
                    restore(child);
        }
    }
}
