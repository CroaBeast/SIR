package com.bitaspire.sir.module.channel;

import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.UserFormatter;
import com.bitaspire.sir.channel.Access;
import com.bitaspire.sir.channel.Audience;
import com.bitaspire.sir.channel.ChatChannel;
import com.bitaspire.sir.channel.Click;
import com.bitaspire.sir.channel.Logging;
import com.bitaspire.sir.channel.Style;
import com.bitaspire.sir.module.channel.channel.Resolver;
import com.bitaspire.sir.user.SIRUser;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.experimental.UtilityClass;
import me.croabeast.common.CollectionBuilder;
import me.croabeast.common.applier.StringApplier;
import me.croabeast.common.util.ReplaceUtils;
import me.croabeast.file.Configurable;
import me.croabeast.prismatic.PrismaticAPI;
import me.croabeast.prismatic.element.MarkupFormat;
import org.apache.commons.lang.StringUtils;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@UtilityClass
final class Factory {

    private final String DEFAULT_FORMAT = " &7{player}: {message}";
    private final String[] CHAT_KEYS = {
            "{tag}", "{prefix}", "{sir-prefix}", "{suffix}", "{sir-suffix}",
            "{color}", "{chat-color}", "{color-start}", "{color-end}", "{message}"
    };

    @Getter
    abstract class BaseChannel implements ChatChannel {

        private final SIRApi api = SIRApi.instance();
        private final Channels main;

        private final ConfigurationSection section;
        private final String name;
        private final boolean global;

        private final ChatChannel parent;

        private final String permission;
        private final int priority;
        @Accessors(fluent = true)
        private final boolean relayToDiscord;

        private final Access access;
        private final Audience audience;
        private final Style style;
        private final Logging logging;

        @Getter(AccessLevel.NONE)
        private final ColorChecker checker;

        BaseChannel(Channels main, ConfigurationSection section, ChatChannel parent) {
            this(main, section, parent, section.getString("name", section.getName()));
        }

        BaseChannel(Channels main, ConfigurationSection section, ChatChannel parent, String name) {
            this.main = main;
            this.section = section;
            this.parent = parent;
            this.name = StringUtils.defaultIfBlank(name, section.getName());

            global = section.getBoolean("global", true);

            permission = fromParent("permission", ChatChannel::getPermission, "DEFAULT");
            priority = fromParent(
                    "priority", ChatChannel::getPriority,
                    permission.matches("(?i)DEFAULT") ? 0 : 1
            );
            relayToDiscord = fromParent("relay-to-discord", ChatChannel::relayToDiscord, false);

            access = new AccessImpl(
                    fromParent("access.default", c -> c.getAccess().isDefault(), false),
                    accessPrefixes(),
                    accessCommands(),
                    fromParent("access.strip-prefix", c -> c.getAccess().shouldStripPrefix(), true)
            );

            audience = new AudienceImpl(
                    fromParent("radius", c -> c.getAudience().getRadius(), global ? -1 : 100),
                    fromParent("same-world", c -> c.getAudience().isSameWorld(), false),
                    fromList("worlds", c -> c.getAudience().getWorldsNames()),
                    fromParent("recipient-permission", c -> c.getAudience().getPermission(), "DEFAULT"),
                    fromParent("recipient-group", c -> c.getAudience().getGroup(), getGroup()),
                    fromParent("include-sender", c -> c.getAudience().shouldIncludeSender(), true)
            );

            style = new StyleImpl(
                    fromParent("tag", c -> c.getStyle().getTag(), null),
                    fromParent("prefix", c -> c.getStyle().getPrefix(), null),
                    fromParent("suffix", c -> c.getStyle().getSuffix(), null),
                    fromParent("color", c -> c.getStyle().getColor(), null),
                    fromParent("color-options.normal", c -> c.getStyle().allowsNormalColors(), false),
                    fromParent("color-options.special", c -> c.getStyle().allowsSpecialColors(), false),
                    fromParent("color-options.rgb", c -> c.getStyle().allowsRgbColors(), false),
                    fromParent("color-options.mini-message", c -> c.getStyle().allowsMiniMessage(), false),
                    clickAction(),
                    fromList("hover", c -> c.getStyle().getHover()),
                    fromParent("format", c -> c.getStyle().getFormat(), DEFAULT_FORMAT).trim()
            );

            checker = new ColorChecker(
                    style.allowsNormalColors(),
                    style.allowsSpecialColors(),
                    style.allowsRgbColors(),
                    style.allowsMiniMessage()
            );

            logging = new LoggingImpl(
                    fromParent("logging.enabled", c -> c.getLogging().isEnabled(), false),
                    resolveLogFormat(main)
            );
        }

        private boolean useParents(String path) {
            return !section.isSet(path) && parent != null;
        }

        @SuppressWarnings("unchecked")
        private <T> T fromParent(String path, Function<ChatChannel, T> mapper, T def) {
            return useParents(path) ? mapper.apply(parent) : (T) section.get(path, def);
        }

        private List<String> fromList(String path, Function<ChatChannel, List<String>> mapper) {
            List<String> list = useParents(path) ? mapper.apply(parent) : Configurable.toStringList(section, path, null);
            return immutable(list);
        }

        private List<String> accessPrefixes() {
            List<String> list;

            if (section.isSet("access.prefix")) {
                String prefix = StringUtils.trimToNull(section.getString("access.prefix"));
                list = prefix == null ? Collections.emptyList() : Collections.singletonList(prefix);
            }
            else if (section.isSet("access.prefixes"))
                list = Resolver.strings(section, "access.prefixes");
            else
                list = parent != null ? parent.getAccess().getPrefixes() : Collections.emptyList();

            return immutable(list);
        }

        private List<String> accessCommands() {
            List<String> list = section.isSet("access.commands") ?
                    Configurable.toStringList(section, "access.commands") :
                    (parent != null ? parent.getAccess().getCommands() : Collections.emptyList());

            return immutable(list);
        }

        @Nullable
        private Click clickAction() {
            Object configured = section.get("click-action");
            configured = configured != null ? configured : section.get("click");

            if (configured instanceof ConfigurationSection)
                return new ClickImpl((ConfigurationSection) configured);
            if (configured instanceof String)
                return new ClickImpl((String) configured);

            return (!section.isSet("click-action") && !section.isSet("click") && parent != null) ?
                    parent.getStyle().getClick() : null;
        }

        @NotNull
        private String resolveLogFormat(Channels main) {
            String configured = useParents("logging.format") ?
                    parent.getLogging().getFormat() :
                    section.getString("logging.format");

            String fallback = (main.config.useSimpleLogger() ?
                    main.config.getSimpleLoggerFormat() :
                    style.getFormat()).trim();

            if (loggingEnabled() && StringUtils.isNotBlank(configured))
                return configured.trim();

            return fallback;
        }

        private boolean loggingEnabled() {
            return fromParent("logging.enabled", c -> c.getLogging().isEnabled(), false);
        }

        @NotNull
        private List<String> immutable(@Nullable List<String> list) {
            return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
        }

        @NotNull
        public Set<SIRUser> getRecipients(SIRUser user) {
            Set<SIRUser> previous = api.getUserManager().getUsers();
            if (user == null) return previous;

            Audience audience = getAudience();
            CollectionBuilder<SIRUser> users = CollectionBuilder
                    .of(previous)
                    .filter(u -> {
                        World world = u.getPlayer().getWorld();
                        return audience.getWorlds().contains(world);
                    });

            if (audience.isSameWorld()) {
                World parserWorld = user.getPlayer().getWorld();
                users.filter(u -> u.getPlayer().getWorld().equals(parserWorld));
            }

            final int radius = audience.getRadius();
            if (radius > 0) {
                Set<SIRUser> nearby = user.getNearbyUsers(radius);
                if (!nearby.isEmpty()) users.filter(nearby::contains);
            }

            if (StringUtils.isNotBlank(audience.getGroup()))
                users.filter(u -> api.getChat().getPermissionProvider().isInGroup(u.getPlayer(), audience.getGroup()));

            users.filter(u -> StringUtils.isBlank(audience.getPermission())
                    || audience.getPermission().matches("(?i)DEFAULT")
                    || hasPermission(u, audience.getPermission()));

            if (isLocal() && !getAccess().isDefault())
                users.filter(u -> u.getChannelData().isToggled(getName()));

            users.filter(u -> !u.getIgnoreData().isIgnoring(user, true));
            return users.toSet();
        }

        @NotNull
        public Set<SIRUser> getRecipients(Player player) {
            return getRecipients(api.getUserManager().getUser(player));
        }

        public String[] getChatKeys() {
            return CHAT_KEYS;
        }

        public String[] getChatValues(String message) {
            return getChatValues(null, message);
        }

        public String[] getChatValues(SIRUser user, String message) {
            Style style = getStyle();
            String chatColorStart = chatColorStart(user);
            String chatColorEnd = chatColorEnd(user);
            String color = StringUtils.defaultIfBlank(style.getColor(), chatColorStart);

            return new String[] {
                    style.getTag(),
                    style.getPrefix(),
                    style.getPrefix(),
                    style.getSuffix(),
                    style.getSuffix(),
                    color,
                    chatColorStart,
                    chatColorStart,
                    chatColorEnd,
                    message
            };
        }

        @NotNull
        private String chatColorStart(@Nullable SIRUser user) {
            return user == null ? "" : StringUtils.defaultString(user.getColorData().getStart());
        }

        @NotNull
        private String chatColorEnd(@Nullable SIRUser user) {
            return user == null ? "" : StringUtils.defaultString(user.getColorData().getEnd());
        }

        private boolean hasPermission(SIRUser user, String permission) {
            return api.getUserManager().hasPermission(user, permission);
        }

        @NotNull
        public String formatString(Player target, Player parser, String string, boolean chat) {
            String format = chat ? style.getFormat() : logging.getFormat();
            SIRUser user = api.getUserManager().getUser(parser);

            StringApplier applier = StringApplier.simplified(format)
                    .apply(s -> api.getLibrary().replace(parser, s))
                    .apply(s -> {
                        String[] values = getChatValues(user, checker.check(parser, string));
                        return ReplaceUtils.replaceEach(CHAT_KEYS, values, s);
                    });

            UserFormatter<?> emojis = api.getModuleManager().getFormatter("Emojis");
            if (emojis != null) applier.apply(s -> emojis.format(user, s));

            UserFormatter<?> tags = api.getModuleManager().getFormatter("Tags");
            if (tags != null) applier.apply(s -> tags.format(user, s));

            UserFormatter<ChatChannel> mentions = api.getModuleManager().getFormatter("Mentions");
            if (mentions != null) applier.apply(s -> mentions.format(user, s, this));

            MarkupFormat f = MarkupFormat.prismatic();
            if (isDefault() && !f.isFormatted(applier.toString()))
                return applier
                        .apply(s -> api.getLibrary().colorize(target, parser, s))
                        .apply(s -> api.getLibrary().getCharacterManager()
                                .align(api.getConfiguration().getChatCenterWidth(), s))
                        .toString();

            if (chat)
                applier.apply(api.getLibrary()::prepareText);

            return (isChatEventless() ? applier : applier.apply(f::strip)).toString();
        }

        @Override
        public String toString() {
            return "BaseChannel{path=" + section.getCurrentPath() +
                    ", permission='" + permission + '\'' +
                    ", priority=" + priority + ", global=" + global +
                    ", relayToDiscord=" + relayToDiscord +
                    ", format='" + style.getFormat() + '\'' + '}';
        }
    }

    @RequiredArgsConstructor
    @Getter
    class AccessImpl implements Access {

        private final boolean defaultAccess;
        private final List<String> prefixes;
        private final List<String> commands;
        private final boolean stripPrefix;

        @Override
        public boolean isDefault() {
            return defaultAccess;
        }

        @Override
        public boolean shouldStripPrefix() {
            return stripPrefix;
        }

        @Override
        public String toString() {
            return "Access{default=" + defaultAccess +
                    ", prefixes=" + prefixes +
                    ", commands=" + commands +
                    ", stripPrefix=" + stripPrefix + '}';
        }
    }

    @RequiredArgsConstructor
    @Getter
    class AudienceImpl implements Audience {

        private final int radius;
        private final boolean sameWorld;
        private final List<String> worldsNames;
        private final String permission;
        private final String group;
        private final boolean includeSender;

        @Override
        public boolean shouldIncludeSender() {
            return includeSender;
        }

        @Override
        public String toString() {
            return "Audience{radius=" + radius +
                    ", sameWorld=" + sameWorld +
                    ", worlds=" + worldsNames +
                    ", permission='" + permission + '\'' +
                    ", group='" + group + '\'' +
                    ", includeSender=" + includeSender + '}';
        }
    }

    @RequiredArgsConstructor
    @Getter
    class ClickImpl implements Click {

        private final me.croabeast.prismatic.element.Click action;
        private final String input;

        private ClickImpl(ConfigurationSection section) {
            action = me.croabeast.prismatic.element.Click.fromName(section.getString("action"));
            input = section.getString("input");
        }

        private ClickImpl(String string) {
            String[] array = string.replace("\"", "").split(":", 2);

            action = me.croabeast.prismatic.element.Click.fromName(array[0]);
            input = array.length > 1 ? array[1] : null;
        }

        @Override
        public String toString() {
            return "Click{action=" + action + ", input='" + input + '\'' + '}';
        }
    }

    @AllArgsConstructor
    @Getter
    class StyleImpl implements Style {

        private final String tag;
        private final String prefix;
        private final String suffix;
        private final String color;
        private final boolean normalColors;
        private final boolean specialColors;
        private final boolean rgbColors;
        private final boolean miniMessage;
        private final Click click;
        private final List<String> hover;

        @Setter
        private String format;

        @Override
        public boolean allowsNormalColors() {
            return normalColors;
        }

        @Override
        public boolean allowsSpecialColors() {
            return specialColors;
        }

        @Override
        public boolean allowsRgbColors() {
            return rgbColors;
        }

        @Override
        public boolean allowsMiniMessage() {
            return miniMessage;
        }

        @Override
        public String toString() {
            return "Style{tag='" + tag + '\'' +
                    ", prefix='" + prefix + '\'' +
                    ", suffix='" + suffix + '\'' +
                    ", color='" + color + '\'' +
                    ", normal=" + normalColors +
                    ", special=" + specialColors +
                    ", rgb=" + rgbColors +
                    ", miniMessage=" + miniMessage +
                    ", format='" + format + '\'' + '}';
        }
    }

    @RequiredArgsConstructor
    @Getter
    class LoggingImpl implements Logging {

        private final boolean enabled;
        private final String format;

        @Override
        public String toString() {
            return "Logging{enabled=" + enabled + ", format='" + format + '\'' + '}';
        }
    }

    @RequiredArgsConstructor
    class ColorChecker {

        static final String PERM = "sir.color.chat.";
        final boolean normal, special, rgb, miniMessage;

        boolean notColor(Player player, String perm) {
            boolean allowed;
            switch (perm) {
                case "special":
                    allowed = special;
                    break;
                case "rgb":
                    allowed = rgb;
                    break;
                case "mini-message":
                    allowed = miniMessage;
                    break;
                case "normal":
                default:
                    allowed = normal;
                    break;
            }

            return !SIRApi.instance().getUserManager().hasPermission(player, PERM + perm) && !allowed;
        }

        String check(Player player, String string) {
            StringApplier applier = StringApplier.simplified(string);

            if (notColor(player, "normal"))
                applier.apply(PrismaticAPI::stripBukkit);
            if (notColor(player, "rgb"))
                applier.apply(PrismaticAPI::stripRGB);
            if (notColor(player, "special"))
                applier.apply(PrismaticAPI::stripSpecial);
            if (notColor(player, "mini-message"))
                applier.apply(PrismaticAPI::stripMiniMessage);

            return applier.toString();
        }

        @Override
        public String toString() {
            return "ColorChecker{normal=" + normal + ", special=" + special +
                    ", rgb=" + rgb + ", miniMessage=" + miniMessage + '}';
        }
    }
}
