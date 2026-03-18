package zxc.zxc.rEgiS;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.List;

public class EffectOnRegist {

    private final REgiS plugin;

    public EffectOnRegist(REgiS plugin) {
        this.plugin = plugin;
    }

    public void applyOnRegister(Player player) {
        if (!plugin.getConfig().getBoolean("onregister.enabled", false)) {
            return;
        }
        applyDarknessEffect(player);
        setGameMode(player);
        new BukkitRunnable() {
            @Override
            public void run() {
                executeCommands(player);
            }
        }.runTaskLater(plugin, 20L);
    }

    private void applyDarknessEffect(Player player) {
        String effect = plugin.getConfig().getString("onregister.effect", "darkness");
        int infiniteTicks = 999999;

        if (effect.equalsIgnoreCase("darkness") || effect.equalsIgnoreCase("затемнение")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, infiniteTicks, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, infiniteTicks, 2));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_DIGGING, infiniteTicks, 1));
        } else if (effect.equalsIgnoreCase("blind")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, infiniteTicks, 0));

        } else if (effect.equalsIgnoreCase("glow")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0));

        } else if (effect.equalsIgnoreCase("fire")) {
            player.setFireTicks(100); // 5 секунд огня

        } else if (effect.equalsIgnoreCase("none")) {
            // -=-
        }
    }

    private void setGameMode(Player player) {
        int gamemode = plugin.getConfig().getInt("onregister.gamemode", 0);

        switch (gamemode) {
            case 0:
                player.setGameMode(GameMode.SURVIVAL);
                break;
            case 1:
                player.setGameMode(GameMode.CREATIVE);
                break;
            case 2:
                player.setGameMode(GameMode.ADVENTURE);
                break;
            case 3:
                player.setGameMode(GameMode.SPECTATOR);
                break;
            default:
                player.setGameMode(GameMode.SURVIVAL);
        }
    }

    private void executeCommands(Player player) {
        List<String> commands = plugin.getConfig().getStringList("onregister.commands");

        if (commands == null || commands.isEmpty()) {
            return;
        }

        for (String command : commands) {
            if (command == null || command.isEmpty()) continue;
            String finalCommand = command
                    .replace("%player%", player.getName())
                    .replace("%p%", player.getName())
                    .replace("%world%", player.getWorld().getName())
                    .replace("%x%", String.valueOf(player.getLocation().getBlockX()))
                    .replace("%y%", String.valueOf(player.getLocation().getBlockY()))
                    .replace("%z%", String.valueOf(player.getLocation().getBlockZ()));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);

            plugin.getLogger().info("Executed command for " + player.getName() + ": " + finalCommand);
        }
    }
    public void clearEffects(Player player) {
        player.getActivePotionEffects().forEach(effect ->
                player.removePotionEffect(effect.getType())
        );
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        player.setFireTicks(0);
    }
}