package com.bitaspire.sir.module.vanish;

import com.bitaspire.sir.chat.ChatProcessor;
import com.bitaspire.sir.module.JoinQuitService;
import me.croabeast.common.Registrable;
import com.bitaspire.sir.Listener;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.takion.message.MessageSender;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Listeners implements Registrable {

    private final Vanish main;
    private final Listener listener;

    Listeners(Vanish main) {
        this.main = main;
        listener = new Listener() {
            @EventHandler
            private void onVanish(VanishEvent event) {
                if (!main.isEnabled()) return;

                SIRUser user = event.getUser();
                if (main.getApi().getModuleManager().getModule("Login") != null)
                    user.setLogged(true);

                JoinQuitService joinQuit = main.getApi().getModuleManager().getJoinQuitService();
                if (joinQuit == null) return;
                if (!main.config.isFakeJoinQuit()) return;

                boolean join = !event.isVanished();
                if (!joinQuit.isOnCooldown(user, join)) joinQuit.displayVanish(user, join);
            }

            @EventHandler
            private void onChat(AsyncPlayerChatEvent event) {
                if (main.getApi().getProcessorManager().isModernPipelineActive()) return;
                if (event.isCancelled() || !main.isEnabled() || !main.config.isChatEnabled())
                    return;

                SIRUser user = main.getApi().getUserManager().getUser(event.getPlayer());
                if (user == null) return;

                ChatProcessor.Context context = new ChatProcessor.Context(user, event.getMessage(), event.isAsynchronous());
                processChat(context);

                event.setMessage(context.getMessage());
                if (context.isCancelled()) event.setCancelled(true);
            }
        };
    }

    private final List<Listener> listeners = new ArrayList<>();

    void processChat(ChatProcessor.Context context) {
        if (context.isCancelled() || !main.isEnabled() || !main.config.isChatEnabled())
            return;

        String key = main.config.getChatKey();
        if (key == null || key.isEmpty()) return;

        String message = context.getMessage();
        Player player = context.getPlayer();
        List<String> list = main.config.getNotAllowed();

        MessageSender sender = main.getApi().getSender().setTargets(player);
        if (main.config.isRegex()) {
            Matcher match = Pattern.compile(key).matcher(message);

            if (!match.find()) {
                context.cancel();
                sender.send(list);
                return;
            }

            context.setMessage(message.replace(match.group(), ""));
            return;
        }

        boolean prefix = main.config.isPrefix();
        int kLen = key.length();

        boolean ok = prefix ? message.startsWith(key) : message.endsWith(key);
        if (!ok) {
            context.cancel();
            sender.send(list);
            return;
        }

        context.setMessage(!prefix ?
                message.substring(0, message.length() - kLen) :
                message.substring(kLen));
    }

    @Override
    public boolean isRegistered() {
        return listener.isRegistered() && listeners.stream().allMatch(Listener::isRegistered);
    }

    @Override
    public boolean register() {
        PluginManager manager = Bukkit.getPluginManager();
        listeners.clear();

        if ((manager.isPluginEnabled("SuperVanish") || manager.isPluginEnabled("PremiumVanish"))
                && classExists("de.myzelyam.api.vanish.PlayerVanishStateChangeEvent"))
            listeners.add(new SuperVanishListener(main));
        else if (manager.isPluginEnabled("CMI")
                && classExists("com.Zrips.CMI.events.CMIPlayerVanishEvent")
                && classExists("com.Zrips.CMI.events.CMIPlayerUnVanishEvent"))
            listeners.add(new CmiVanishListener(main));
        else if (manager.isPluginEnabled("Essentials")
                && classExists("net.ess3.api.events.VanishStatusChangeEvent"))
            listeners.add(new EssentialsVanishListener(main));

        boolean registered = listener.register();
        for (Listener listener : listeners)
            registered &= listener.register();

        if (!registered) unregister();
        return registered;
    }

    @Override
    public boolean unregister() {
        boolean unregistered = listener.unregister();
        for (Listener listener : listeners)
            unregistered &= listener.unregister();

        listeners.clear();
        return unregistered;
    }

    private boolean classExists(String name) {
        try {
            Class.forName(name, false, Listeners.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
