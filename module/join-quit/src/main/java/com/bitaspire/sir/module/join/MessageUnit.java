package com.bitaspire.sir.module.join;

import lombok.Getter;
import me.croabeast.file.Configurable;
import com.bitaspire.sir.PermissibleUnit;
import com.bitaspire.sir.SIRApi;
import com.bitaspire.sir.SoundSection;
import com.bitaspire.sir.module.DiscordService;
import com.bitaspire.sir.user.SIRUser;
import me.croabeast.takion.message.MessageSender;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Getter
final class MessageUnit implements PermissibleUnit {

    private final JoinQuit main;
    private final SIRApi api;

    private final ConfigurationSection section;
    private final Messages.Type type;

    private SoundSection soundSection = null;
    private SpawnSection spawnSection = null;

    private final int invulnerable;

    private final List<String> publicList, privateList, commands;

    MessageUnit(JoinQuit main, ConfigurationSection section, Messages.Type type) {
        this.main = main;
        this.api = main.getApi();
        this.section = section;
        this.type = type;

        try {
            SoundSection temp = new SoundSection(section.getConfigurationSection("sound"));
            if (temp.isEnabled()) soundSection = temp;
        } catch (Exception ignored) {}

        try {
            SpawnSection temp = new SpawnSection(section.getConfigurationSection("spawn"));
            if (temp.enabled) spawnSection = temp;
        } catch (Exception ignored) {}

        invulnerable = section.getInt("invulnerable");

        publicList = Configurable.toStringList(section, "public");
        privateList = Configurable.toStringList(section, "private");
        commands = Configurable.toStringList(section, "commands");
    }

    void teleport(SIRUser user) {
        if (spawnSection != null) spawnSection.teleport(user);
    }

    void execute(SIRUser user) {
        MessageSender sender = api.getSender();
        Player player = user.isOnline() ? user.getPlayer() : null;

        List<Player> targets = Bukkit.getOnlinePlayers().stream()
                .filter(p -> {
                    SIRUser target = api.getUserManager().getUser(p);
                    return target != null && main.isToggled(target);
                })
                .collect(Collectors.toList());

        sender.copy().setParser(player).setTargets(targets).send(publicList);
        if (type != Messages.Type.QUIT) {
            boolean chatEnabled = main.isToggled(user);
            if (chatEnabled)
                sender.copy().setTargets(player).send(privateList);

            user.getImmuneData().giveImmunity(invulnerable);
            if (chatEnabled && soundSection != null) soundSection.playSound(user);

            teleport(user);
        }

        SIRApi.executeCommands(type == Messages.Type.QUIT ? null : user, commands);

        DiscordService discord = api.getModuleManager().getDiscordService();
        if (discord != null)
            discord.sendMessage(type == Messages.Type.FIRST_JOIN ?
                    "first-join" :
                    type.name().toLowerCase(Locale.ENGLISH), player, s -> s);
    }

    private static class SpawnSection {

        private final boolean enabled;
        private final Location location;

        SpawnSection(ConfigurationSection section) {
            enabled = Objects.requireNonNull(section).getBoolean("enabled");

            final World world = Bukkit.getWorld(Objects.requireNonNull(section.getString("world")));
            location = Objects.requireNonNull(world).getSpawnLocation();

            try {
                String[] c = Objects.requireNonNull(section.getString("coordinates")).split(",", 3);
                try {
                    location.setX(Double.parseDouble(c[0]));
                } catch (Exception ignored) {}
                try {
                    location.setY(Double.parseDouble(c[1]));
                } catch (Exception ignored) {}
                try {
                    location.setZ(Double.parseDouble(c[2]));
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}

            try {
                String[] r = Objects.requireNonNull(section.getString("rotation")).split(",", 3);
                try {
                    location.setYaw(Float.parseFloat(r[0]));
                } catch (Exception ignored) {}
                try {
                    location.setPitch(Float.parseFloat(r[1]));
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }

        void teleport(SIRUser user) {
            if (user.isOnline()) user.getPlayer().teleport(location);
        }
    }
}
