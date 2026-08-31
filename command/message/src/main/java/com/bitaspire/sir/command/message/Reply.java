package com.bitaspire.sir.command.message;

import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.takion.message.MessageSender;
import org.apache.commons.lang.StringUtils;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.function.Supplier;

public class Reply extends Command {

    private final MessageProvider main;

    Reply(MessageProvider main) {
        super("reply", main.getLang());
        this.main = main;
    }

    @Override
    public boolean execute(@NotNull CommandSender s, String[] args) {
        if (!isPermitted(s)) return true;

        SIRUser receiver = main.getApi().getUserManager().getUser(s);
        if (receiver != null && receiver.getMuteData().isMuted())
            return Utils.create(this, s).setLogger(false).send("is-muted");

        CommandSender init = main.replies.get(s);
        if (init == null)
            return Utils.create(this, s).setLogger(false).send("not-replied");

        SIRUser initiator = main.getApi().getUserManager().getUser(init);

        if (initiator.getIgnoreData().blocks(receiver, false))
            return Utils.create(this, s).setLogger(false)
                    .addPlaceholder("{target}", initiator.getName())
                    .addPlaceholder("{type}", getLang().get("lang.channels.msg", ""))
                    .send("ignoring");

        if (getLang().get("lang.vanish-messages.enabled", true) &&
                initiator.isVanished())
            return Utils.create(this, s).setLogger(false).send("vanish-messages.message");

        String message = SIRApi.joinArray(0, args);
        if (StringUtils.isBlank(message))
            return Utils.create(this, s).setLogger(false).send("empty-message");

        Values initValues = new Values(main, true);
        Values receiveValues = new Values(main, false);

        boolean senderEnabled = receiver == null || main.isToggled(receiver);
        boolean initiatorEnabled = main.isToggled(initiator);

        MessageSender sender = Utils.create(this, null)
                .setSensitive(false).setLogger(false)
                .addPlaceholder("{receiver}", isConsoleValue(init))
                .addPlaceholder("{message}", message)
                .addPlaceholder("{sender}", isConsoleValue(s));

        if (senderEnabled) {
            new MessageSender(sender).setTargets(s).send(initValues.getOutput());
            receiveValues.playSound(s);
        }

        if (initiatorEnabled) {
            new MessageSender(sender).setTargets(init).send(receiveValues.getOutput());
            initValues.playSound(init);
        }

        return sender.setErrorPrefix(null)
                .setLogger(true).send("console-formatting.format");
    }

    @NotNull
    public Supplier<Collection<String>> generateCompletions(CommandSender sender, String[] arguments) {
        return () -> Utils.newBuilder().addArgument(0, "<message>").build(sender, arguments);
    }
}
