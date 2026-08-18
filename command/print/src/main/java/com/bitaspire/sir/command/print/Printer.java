package com.bitaspire.sir.command.print;

import lombok.RequiredArgsConstructor;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.UserFormatter;
import com.bitaspire.sir.module.ModuleManager;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.takion.TakionLib;
import me.croabeast.takion.channel.Channel;
import me.croabeast.takion.channel.ChannelManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

@RequiredArgsConstructor
final class Printer {

    final PrintProvider main;
    final String[] args;
    final int index;

    final SIRApi api = SIRApi.instance();
    final TakionLib library = api.getLibrary();

    private String parse(Player player, String string) {
        ModuleManager moduleManager = api.getModuleManager();
        SIRUser user = api.getUserManager().getUser(player);

        UserFormatter<?> emojis = moduleManager.getFormatter("Emojis");
        if (emojis != null) string = emojis.format(user, string);

        UserFormatter<?> tags = moduleManager.getFormatter("Tags");
        if (tags != null) string = tags.format(user, string);

        return string;
    }

    void print(CommandSender sender, String input, String key) {
        String message = SIRApi.joinArray(index, args);
        String center = api.getConfiguration().getCenterPrefix();
        int centerWidth = api.getConfiguration().getChatCenterWidth();

        ChannelManager channelManager = library.getChannelManager();
        String argument = args[2];

        for (Player player : TargetLoader.getTargets(sender, input)) {
            SIRUser user = api.getUserManager().getUser(player);
            if (user != null && !main.isToggled(user))
                continue;

            Channel channel = channelManager.identify(key);

            if (channel == channelManager.identify("chat")) {
                String[] array = library.splitString(message);

                for (String string : array) {
                    switch (argument.toLowerCase(Locale.ENGLISH)) {
                        case "centered":
                            string = string.startsWith(center) ?
                                    string : center + string;
                            break;

                        case "default":
                            string = !string.startsWith(center) ?
                                    string : string.substring(center.length());
                            break;

                        default:
                            break;
                    }

                    String parsed = parse(player, string);
                    if (parsed != null && parsed.startsWith(center))
                        parsed = library.getCharacterManager().align(centerWidth, parsed);

                    channel.send(player, parsed);
                }
                continue;
            }

            else if (channel == channelManager.identify("title")) {
                String time = null;
                try {
                    time = Integer.parseInt(args[2]) + "";
                } catch (Exception ignored) {}

                time = time != null ? (":" + time) : "";

                channel.send(player,
                        channelManager.getStartDelimiter() +
                                channel.getName() + time +
                                channelManager.getEndDelimiter() + " " +
                                parse(player, message)
                );
                continue;
            }

            channel.send(player, parse(player, message));
        }
    }
}
