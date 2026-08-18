package com.bitaspire.sir.module.cooldown;

import com.bitaspire.sir.chat.ChatProcessor;
import lombok.RequiredArgsConstructor;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.user.SIRUser;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
final class Listener extends com.bitaspire.sir.Listener {

    private final Cooldowns main;

    private final Map<UUID, Integer> spamMap = new HashMap<>();
    private final Map<UUID, Long> timers = new HashMap<>();

    @EventHandler
    private void onChat(AsyncPlayerChatEvent event) {
        if (main.getApi().getProcessorManager().isModernPipelineActive()) return;
        if (event.isCancelled() || !main.isEnabled()) return;

        SIRUser user = main.getApi()
                .getUserManager()
                .getUser(event.getPlayer());
        if (user == null) return;

        ChatProcessor.Context context = new ChatProcessor.Context(user, event.getMessage(), event.isAsynchronous());
        process(context);

        if (context.isCancelled()) event.setCancelled(true);
    }

    void process(ChatProcessor.Context context) {
        if (context.isCancelled() || !main.isEnabled()) return;

        SIRUser user = context.getUser();
        CooldownUnit unit = main.data.getUnit(user);
        if (unit == null) return;

        long timer = timers.getOrDefault(user.getUuid(), 0L);

        int time = unit.getTime();
        if (time <= 0 || timer <= 0) return;

        long rest = System.currentTimeMillis() - timer;
        if (rest >= time * 1000L) return;

        context.cancel();
        int result = Math.round(rest / 1000F);

        if (unit.isSpamEnabled()) {
            int count = spamMap.getOrDefault(user.getUuid(), 0);

            if (rest >= unit.getSpamTimeLimit() * 1000L &&
                    count >= unit.getSpamCount())
            {
                SIRApi.executeCommands(user, unit.getSpamCommands());
                spamMap.put(user.getUuid(), ++count);
            }
        }

        if (user.isOnline())
            main.getApi().getSender()
                    .addPlaceholder("{time}", time - result)
                    .setLogger(false)
                    .setTargets(user.getPlayer())
                    .send(unit.getMessages());

        timers.put(user.getUuid(), System.currentTimeMillis());
    }
}
