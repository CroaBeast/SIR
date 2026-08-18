package com.bitaspire.sir.module.moderation;

import com.bitaspire.sir.chat.ChatProcessor;
import me.croabeast.prismatic.element.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Links extends Module {

    Links(Moderation main) {
        super(main, "links");
    }

    @Override
    void process0(ChatProcessor.Context context) {
        String message = context.getMessage();

        List<String> links = file.toStringList("allowed-links");
        boolean foundAny = false;

        Matcher matcher = Element.URL_PATTERN.matcher(message);
        List<String> restrictedLinks = new ArrayList<>();

        while (matcher.find()) {
            String match = matcher.group();
            boolean allowed = false;

            for (String link : links)
                if (match.matches("(?i)" + Pattern.quote(link))) {
                    allowed = true;
                    break;
                }

            if (allowed) continue;

            restrictedLinks.add(match);
            foundAny = true;
        }

        if (foundAny) {
            validateAndExecuteActions(
                    context.getPlayer(), message,
                    file.getConfiguration().getInt("actions.maximum-violations", 3)
            );

            if (file.get("control", "BLOCK").matches("(?i)block")) {
                context.cancel();
                return;
            }

            List<String> list = file.toStringList("replace-options.replacements");

            for (String link : restrictedLinks) {
                String replace = getReplacement(list, link);
                message = message.replace(link, replace);
            }

            context.setMessage(message);
        }
    }
}
