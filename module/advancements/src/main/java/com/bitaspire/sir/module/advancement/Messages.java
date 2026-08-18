package com.bitaspire.sir.module.advancement;

import lombok.SneakyThrows;
import me.croabeast.advancement.AdvancementInfo;
import me.croabeast.common.applier.StringApplier;
import me.croabeast.common.util.ReplaceUtils;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.file.ExtensionFile;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.takion.TakionLib;
import me.croabeast.takion.format.PlainFormat;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class Messages {

    final Advancements main;

    private final Map<Type, List<String>> messages = new HashMap<>(), commands = new HashMap<>();
    private final DataHandler data;

    @SneakyThrows
    Messages(Advancements main) {
        this.main = main;

        ExtensionFile file = new ExtensionFile(main, "messages", true);

        for (Type type : Type.values()) {
            String name = type.name().toLowerCase(Locale.ENGLISH);
            messages.put(type, file.toStringList("messages." + name));
            commands.put(type, file.toStringList("commands." + name));
        }

        data = main.data;
    }

    void send(Advancement advancement, Player player) {
        ConfigurationSection section = data.sections.get(advancement);
        if (section == null) return;

        Replacer replacer = new Replacer(section, advancement);

        AdvancementInfo info = data.information.get(advancement);
        Type type = Type.fromFrame(info.getFrame());

        Set<Player> players = Bukkit.getOnlinePlayers().stream()
                .filter(entry -> {
                    SIRUser user = main.getApi().getUserManager().getUser(entry);
                    return user != null && main.isToggled(user);
                })
                .collect(Collectors.toSet());

        TakionLib library = SIRApi.instance().getLibrary();
        SIRApi.instance().getSender().setTargets(players)
                .setParser(player)
                .addFunctions(replacer::replace)
                .send(messages.getOrDefault(type, new ArrayList<>()));

        List<String> commands = this.commands.getOrDefault(type, new ArrayList<>());

        Pattern cPattern = Pattern.compile("(?i)^\\[(global|console)]");
        Pattern pPattern = Pattern.compile("(?i)^\\[player]");

        main.getApi().getScheduler().runTask(() -> {
            for (String c : commands) {
                if (StringUtils.isBlank(c)) continue;

                Matcher pm = pPattern.matcher(c), cm = cPattern.matcher(c);

                StringApplier applier = StringApplier.simplified(c)
                        .apply(s -> library.replace(player, s))
                        .apply(PlainFormat.TRIM_START_SPACES::accept);

                if (pm.find() && player != null) {
                    String text = applier.toString().replace(pm.group(), "");
                    Bukkit.dispatchCommand(player, text);
                    continue;
                }

                if (cm.find()) applier.apply(s -> s.replace(cm.group(), ""));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), applier.toString());
            }
        });
    }

    UnaryOperator<String> getReplacer(Advancement advancement) {
        ConfigurationSection section = data.sections.get(advancement);
        if (section == null) return s -> s;

        Replacer replacer = new Replacer(section, advancement);
        return replacer::replace;
    }

    static class Replacer {

        private static final String[] MAIN_PLACEHOLDERS = {
                "{advancement}",
                "{adv}",
                "{description}",
                "{frame}",
                "{frame-lower-case}",
                "{frame-capitalized}"
        };

        private static final String[] ITEM_PLACEHOLDERS = {
                "{item}",
                "{item-lower-case}",
                "{item-capitalized}"
        };

        private final String title, description, frame, item;

        Replacer(ConfigurationSection section, Advancement advancement) {
            String key = advancement.getKey().getKey();

            String title = section.getString("title");
            if (title == null)
                title = WordUtils.capitalize(key.substring(key.lastIndexOf('/') + 1).replace('_', ' '));

            this.title = title;

            description = section.getString("description", "No description available.");
            frame = section.getString("frame", "TASK");

            ItemStack item = section.getItemStack("item");
            this.item = item == null ? null : item.getType().toString();
        }

        String replace(String message) {
            if (StringUtils.isBlank(message)) return message;

            StringApplier applier = StringApplier.simplified(message);
            applier.apply(s -> ReplaceUtils.replaceEach(MAIN_PLACEHOLDERS, new String[] {
                    title,
                    title,
                    description,
                    frame,
                    frame.toLowerCase(Locale.ENGLISH),
                    WordUtils.capitalize(frame.toLowerCase(Locale.ENGLISH))
            }, s, false));

            if (item != null) {
                applier.apply(s -> ReplaceUtils.replaceEach(ITEM_PLACEHOLDERS, new String[] {
                        item,
                        item.toLowerCase(Locale.ENGLISH),
                        WordUtils.capitalize(item.toLowerCase(Locale.ENGLISH))
                }, s, false));
            }

            return applier.toString();
        }
    }

    enum Type {
        TASK,
        GOAL,
        CHALLENGE,
        CUSTOM;

        static Type fromFrame(AdvancementInfo.Frame frame) {
            switch (frame) {
                case TASK:
                    return TASK;
                case GOAL:
                    return GOAL;
                case CHALLENGE:
                    return CHALLENGE;
                default:
                    return CUSTOM;
            }
        }
    }
}
