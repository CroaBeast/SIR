package com.bitaspire.sir.module.mention;

import com.bitaspire.sir.ChatCompletions;
import me.croabeast.common.util.ReplaceUtils;
import me.croabeast.prismatic.PrismaticAPI;
import com.bitaspire.sir.ChatToggleable;
import com.bitaspire.sir.SoundSection;
import com.bitaspire.sir.UserFormatter;
import com.bitaspire.sir.channel.ChatChannel;
import com.bitaspire.sir.module.SIRModule;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.prismatic.chat.MultiComponent;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Mentions extends SIRModule implements UserFormatter<ChatChannel>, ChatToggleable {

    Data data;
    private ChatCompletions completions;

    @Override
    public boolean register() {
        data = new Data(this);
        completions = new ChatCompletions(getApi(), this::getCompletions);
        completions.register();
        return true;
    }

    @Override
    public boolean unregister() {
        if (completions != null) {
            completions.unregister();
            completions = null;
        }
        return true;
    }

    @NotNull
    public String format(SIRUser user, String string, ChatChannel channel) {
        if (user == null || StringUtils.isBlank(string) || !isEnabled() || !user.isOnline())
            return string;

        boolean senderEnabled = isToggled(user);

        UnaryOperator<String> operator = null;
        List<String> firstMessages = null;
        SoundSection firstSound = null;

        for (Mention mention : data.getMentions()) {
            if (!mention.canUse(user))
                continue;

            String prefix = mention.getPrefix();
            if (StringUtils.isBlank(prefix)) continue;

            Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "([^\\s]+)\\b");
            Matcher matcher = pattern.matcher(string);
            StringBuffer buffer = new StringBuffer();

            boolean replacedAny = false;
            while (matcher.find()) {
                SIRUser target = getApi().getUserManager().fromClosest(matcher.group(1));
                if ((target == null || user == target ||
                        target.getIgnoreData().isIgnoring(user, true)) ||
                        (channel != null &&
                                !channel.getRecipients(user).contains(target)))
                    continue;

                UnaryOperator<String> op = s -> ReplaceUtils.replaceEach(
                        new String[]{"{prefix}", "{sender}", "{receiver}"},
                        new String[]{prefix, user.getName(), target.getName()}, s
                );
                if (operator == null) operator = op;

                String finder = matcher.group();
                String color = PrismaticAPI.getEndColor(finder);

                boolean receiverEnabled = isToggled(target);
                if (receiverEnabled) {
                    getApi().getSender()
                            .setTargets(target.getPlayer())
                            .setLogger(false)
                            .addFunctions(op)
                            .send(mention.getReceiverMessages());

                    SoundSection receiverSound = mention.getReceiverSound();
                    if (receiverSound != null) receiverSound.playSound(target);
                }

                if (senderEnabled && firstMessages == null) firstMessages = mention.getSenderMessages();
                if (senderEnabled && firstSound == null) firstSound = mention.getSenderSound();

                List<String> hover = new ArrayList<>(mention.getHover());
                hover.replaceAll(op);

                String click = op.apply(mention.getClick());
                String result = op.apply(mention.getValue());

                result = MultiComponent.fromString(getApi().getLibrary().getChatProcessor(), result)
                        .setHover(hover)
                        .setClick(click)
                        .toFormattedString();

                String replace = getApi().getLibrary().colorize(user.getPlayer(), result);
                if (color != null) replace += color;

                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replace));
                replacedAny = true;
            }

            if (replacedAny) {
                matcher.appendTail(buffer);
                string = buffer.toString();
            }
        }

        if (senderEnabled && firstSound != null) firstSound.playSound(user);
        if (senderEnabled && firstMessages != null)
            getApi().getSender()
                    .addFunctions(operator)
                    .setLogger(false)
                    .setTargets(user.getPlayer())
                    .send(firstMessages);

        return string;
    }

    @NotNull
    public String format(SIRUser user, String string) {
        return format(user, string, null);
    }

    private Collection<String> getCompletions(SIRUser user) {
        if (user == null || !user.isOnline() || !isEnabled() || data == null)
            return Collections.emptyList();

        Set<String> values = new LinkedHashSet<>();

        for (Mention mention : data.getMentions()) {
            if (!mention.canUse(user)) continue;

            String prefix = mention.getPrefix();
            if (StringUtils.isBlank(prefix)) continue;

            for (SIRUser target : getApi().getUserManager().getUsers(true)) {
                if (!canSuggest(user, target)) continue;
                values.add(prefix + target.getPlayer().getName());
            }
        }

        return values;
    }

    private boolean canSuggest(SIRUser user, SIRUser target) {
        if (target == null || !target.isOnline() || user == target) return false;
        if (target.getIgnoreData().isIgnoring(user, true)) return false;
        return user.getPlayer().canSee(target.getPlayer());
    }
}
