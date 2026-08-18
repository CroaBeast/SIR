package com.bitaspire.sir.module.moderation;

import com.bitaspire.sir.chat.ChatProcessor;
import lombok.Getter;
import lombok.SneakyThrows;
import me.croabeast.common.CollectionBuilder;
import me.croabeast.common.CustomListener;
import me.croabeast.common.Registrable;
import me.croabeast.file.Configurable;
import me.croabeast.file.ConfigurableFile;
import com.bitaspire.sir.file.ExtensionFile;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.takion.message.MessageSender;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.*;

abstract class Module implements Registrable {

    final String moduleName, bypass;
    final Map<UUID, Integer> violations = new HashMap<>();

    private final Moderation main;
    final ConfigurableFile file;

    private int replaceIndex = 0;

    private final CustomListener listener = new CustomListener() {
        @Getter
        private final Status status = new Status();

        @EventHandler(priority = EventPriority.LOWEST)
        private void onChatViolation(AsyncPlayerChatEvent event) {
            if (main.getApi().getProcessorManager().isModernPipelineActive()) return;

            SIRUser user = main.getApi().getUserManager().getUser(event.getPlayer());
            if (user == null) return;

            ChatProcessor.Context context = new ChatProcessor.Context(user, event.getMessage(), event.isAsynchronous());
            process(context);

            event.setMessage(context.getMessage());
            if (context.isCancelled()) event.setCancelled(true);
        }
    };

    @SneakyThrows
    Module(Moderation main, String name) {
        this.main = main;
        this.moduleName = name;
        this.bypass = (this.file = new ExtensionFile(main, name, true)).get("bypass", "sir.moderation.bypass." + name);
    }

    private final Random random = new Random();

    String getReplacement(List<String> replacements, String word) {
        if (replacements.isEmpty()) return word;

        final int size = replacements.size();
        if (replaceIndex >= size) replaceIndex = 0;

        String type = file.get("replace-options.type", "CHARACTER");

        boolean isCharacter = type.matches("(?i)character");
        boolean order = file.get("replace-options.order", true);

        int index = order ? replaceIndex++ : random.nextInt(size);

        if (isCharacter) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                sb.append(replacements.get(index));
            }
            return sb.toString();
        }

        return replacements.get(index);
    }

    final void process(ChatProcessor.Context context) {
        if (!file.get("enabled", true)) return;
        if (main.getApi().getUserManager().hasPermission(context.getPlayer(), bypass)) return;
        process0(context);
    }

    abstract void process0(ChatProcessor.Context context);

    boolean validateAndExecuteActions(Player player, String message, int max) {
        MessageSender sender = main.getApi().getSender()
                .addPlaceholder("{player}", player.getName())
                .addPlaceholder("{message}", message)
                .addPlaceholder("{type}", main.config.getName(moduleName))
                .setLogger(true);

        String loggerResults = main.config.getViolationLogFormat();
        if (main.config.isStaffNotified())
            sender.copy().setTargets(
                            CollectionBuilder.of(Bukkit.getOnlinePlayers())
                                    .filter(p -> main.getApi().getUserManager()
                                            .hasPermission(p, main.config.getNotifyPermission()))
                                    .toSet()
                    )
                    .send(loggerResults);

        if (main.config.isViolationLogging())
            sender.copy().setTargets((Player) null).send(loggerResults);

        main.getApi().getSender()
                .setTargets(player)
                .send(file.toStringList("warnings"));

        ConfigurationSection actions = file.getSection("actions");
        if (actions == null) return false;

        UUID uuid = player.getUniqueId();

        int count = violations.getOrDefault(uuid, 0) + 1;
        violations.put(uuid, count);

        if (count >= max) {
            violations.put(uuid, 0);
            main.getApi().getSender()
                    .setTargets(player)
                    .send(Configurable.toStringList(actions, "messages"));

            SIRApi.executeCommands(
                    main.getApi().getUserManager().getUser(player),
                    Configurable.toStringList(actions, "commands")
            );
            return true;
        }

        return false;
    }

    @Override
    public boolean isRegistered() {
        return listener.isRegistered();
    }

    @Override
    public boolean register() {
        return listener.register(main.getApi().getPlugin());
    }

    @Override
    public boolean unregister() {
        return listener.unregister();
    }
}
