package com.bitaspire.sir.command.message;

import me.croabeast.common.CollectionBuilder;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.takion.message.MessageSender;
import org.apache.commons.lang.StringUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;

final class Message extends Command {

    private final MessageProvider main;

    Message(MessageProvider main) {
        super("message", main.getLang());
        this.main = main;
    }

    @Override
    public boolean execute(@NotNull CommandSender s, String[] args) {
        if (!isPermitted(s)) return true;

        final SIRUser user = main.getApi().getUserManager().getUser(s);
        if (user != null && user.getMuteData().isMuted())
            return Utils.create(this, s).setLogger(false).send("is-muted");

        if (args.length == 0)
            return Utils.create(this, s).setLogger(false).send("need-player");

        SIRUser target = main.getApi().getUserManager().fromClosest(args[0]);
        if (target == null) return checkPlayer(s, args[0]);

        if (Objects.equals(target, user))
            return Utils.create(this, s).setLogger(false).send("not-yourself");

        if (target.getIgnoreData().blocks(user, false))
            return Utils.create(this, s).setLogger(false)
                    .addPlaceholder("{target}", target.getName())
                    .addPlaceholder("{type}", getLang().get("lang.channels.msg", ""))
                    .send("ignoring");

        boolean vanished = getLang().get("lang.vanish-messages.enabled", true);
        if (target.isVanished() && vanished)
            return Utils.create(this, s).setLogger(false).send("vanish-messages.message");

        String message = SIRApi.joinArray(1, args);
        if (StringUtils.isBlank(message))
            return Utils.create(this, s).setLogger(false).send("empty-message");

        Values initValues = new Values(main, true);
        Values receiveValues = new Values(main, false);

        boolean senderEnabled = user == null || main.isToggled(user);
        boolean receiverEnabled = main.isToggled(target);

        Player player = target.getPlayer();

        MessageSender sender = Utils.create(this, null)
                .setSensitive(false).setLogger(false)
                .addPlaceholder("{receiver}", isConsoleValue(player))
                .addPlaceholder("{message}", message)
                .addPlaceholder("{sender}", isConsoleValue(s));

        if (senderEnabled) {
            new MessageSender(sender).setTargets(s).send(initValues.getOutput());
            initValues.playSound(s);
        }

        if (receiverEnabled) {
            new MessageSender(sender).setTargets(player).send(receiveValues.getOutput());
            receiveValues.playSound(player);
        }

        main.replies.put(player, s);
        main.replies.put(s, player);

        return sender.setErrorPrefix(null)
                .setLogger(true).send("console-formatting.format");
    }

    @NotNull
    public Supplier<Collection<String>> generateCompletions(CommandSender sender, String[] arguments) {
        return () -> Utils.newBuilder()
                .addArguments(0,
                        CollectionBuilder.of(main.getApi().getUserManager().getUsers())
                                .filter(u -> !u.isVanished())
                                .map(SIRUser::getName).toList()
                )
                .addArgument(1, "<message>").build(sender, arguments);
    }
}
