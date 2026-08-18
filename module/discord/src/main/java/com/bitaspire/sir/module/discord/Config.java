package com.bitaspire.sir.module.discord;

import lombok.SneakyThrows;
import me.croabeast.common.applier.StringApplier;
import me.croabeast.file.Configurable;
import me.croabeast.prismatic.PrismaticAPI;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.file.ExtensionFile;
import org.apache.commons.lang.StringUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.UnaryOperator;

final class Config {

    private final Set<String> eventChannels = new HashSet<>(
            Arrays.asList("first-join", "join", "quit", "advancements")
    );

    private final String defaultServer;
    private final Discord.Backend backend;
    final boolean restricted;

    private final Map<String, List<String>> channelIds = new HashMap<>();
    private final Map<String, EmbedTemplate> embeds = new HashMap<>();

    @SneakyThrows
    Config(Discord main, Discord.Backend backend) {
        this.backend = backend;
        restricted = backend == Discord.Backend.ESSENTIALS;
        ExtensionFile file = new ExtensionFile(main, "config", true);
        defaultServer = file.get("default-server", new Object()).toString();
        loadEmbeds(file);
        loadChannelIds(file);
    }

    private void loadChannelIds(ExtensionFile file) {
        ConfigurationSection idsSection = file.getSection("channel-ids");
        if (idsSection == null) return;

        for (String key : idsSection.getKeys(false))
            channelIds.put(key, Configurable.toStringList(idsSection, key));
    }

    private void loadEmbeds(ExtensionFile file) {
        ConfigurationSection messagesSection = file.getSection("messages");
        if (messagesSection == null) return;

        for (String key : messagesSection.getKeys(false)) {
            ConfigurationSection section = messagesSection.getConfigurationSection(key);
            if (section != null)
                embeds.put(key, new EmbedTemplate(section, backend, defaultServer));
        }
    }

    void send(String channel, Player player, UnaryOperator<String> operator) {
        List<String> ids = channelIds.get(channel);
        EmbedTemplate template = embeds.get(channel);
        boolean eventChannel = eventChannels.contains(channel);

        if (template == null) {
            if (eventChannel) return;
            template = embeds.get("global-chat");
        }

        if (ids == null || ids.isEmpty()) {
            if (eventChannel) return;
            ids = channelIds.getOrDefault("global-chat", Collections.emptyList());
        }

        if (template != null) template.send(player, ids, operator);
    }

    static final class EmbedTemplate {
        private final Discord.Backend backend;
        final String defaultServer;

        final boolean enabled;
        final boolean playerWebhook;
        final boolean timeStamp;

        final Author author;

        final String name;
        final String text;
        final String color;
        final String thumbnail;
        final String description;
        final String titleText;
        final String titleUrl;

        EmbedTemplate(ConfigurationSection section, Discord.Backend backend, String defaultServer) {
            this.backend = backend;
            this.defaultServer = defaultServer;

            this.name = section.getString("name", section.getString("username", ""));
            this.text = section.getString("text");

            ConfigurationSection authorSection = section.getConfigurationSection("embed.author");
            this.author = authorSection != null ? new Author(authorSection) : null;

            this.color = section.getString("embed.color");
            this.thumbnail = section.getString("embed.thumbnail");
            this.description = section.getString("embed.description");
            this.titleText = section.getString("embed.title.text");
            this.titleUrl = section.getString("embed.title.url");

            this.enabled = section.getBoolean("embed.enabled");
            this.playerWebhook = section.getBoolean("player-webhook");
            this.timeStamp = section.getBoolean("embed.timestamp");
        }

        private static UnaryOperator<String> createFormatter(Player player, UnaryOperator<String> base,
                                                            UnaryOperator<String> translator) {
            return string -> {
                if (StringUtils.isBlank(string)) return string;

                String output = StringApplier.simplified(string).apply(base)
                        .apply(s -> SIRApi.instance().getLibrary().replace(player, s))
                        .apply(PrismaticAPI::stripAll)
                        .toString();

                return translator.apply(output);
            };
        }

        void send(Player player, List<String> ids, UnaryOperator<String> operator) {
            if (backend == Discord.Backend.NONE) return;

            switch (backend) {
                case DISCORD_SRV:
                    DiscordSrv.send(player, ids, this, createFormatter(player, operator, DiscordSrv::translateEmotes));
                    return;
                case ESSENTIALS:
                    sendViaEssentialsDiscord(player, createFormatter(player, operator, UnaryOperator.identity()));
                    return;
                default:
            }
        }

        private void sendViaEssentialsDiscord(Player player, UnaryOperator<String> formatter) {
            SIRApi.instance().getScheduler().runTask(() -> Essentials.send(player, text, formatter));
        }

        static final class Author {

            final String name, url, iconUrl;

            Author(ConfigurationSection section) {
                this.name = section.getString("name", "");
                this.url = section.getString("url");
                this.iconUrl = section.getString("icon-url");
            }
        }
    }
}
