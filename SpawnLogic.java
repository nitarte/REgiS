package zxc.zxc.rEgiS;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SpawnLogic {

    private final JavaPlugin plugin;
    private File spawnsFile;
    private FileConfiguration spawnsConfig;
    private final Random random = new Random();
    private static final int MAX_SPAWNS = 5; // Максимальное количество спавнов

    public SpawnLogic(JavaPlugin plugin) {
        this.plugin = plugin;
        initSpawnsFile();
    }

    private void initSpawnsFile() {
        spawnsFile = new File(plugin.getDataFolder(), "spawns.yml");
        if (!spawnsFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                spawnsFile.createNewFile();
                spawnsConfig = YamlConfiguration.loadConfiguration(spawnsFile);
                spawnsConfig.set("spawns", new ArrayList<>());
                spawnsConfig.set("enabled-count", 0);
                saveSpawnsConfig();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        spawnsConfig = YamlConfiguration.loadConfiguration(spawnsFile);
    }

    private void saveSpawnsConfig() {
        try {
            spawnsConfig.save(spawnsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean setSpawn(String name, Player player) {

        if (spawnsConfig.contains("spawns." + name)) {
            player.sendMessage("§c❌ Спавн с названием '" + name + "' уже существует!");
            return false;
        }

        int totalSpawns = 0;
        if (spawnsConfig.contains("spawns")) {
            totalSpawns = spawnsConfig.getConfigurationSection("spawns").getKeys(false).size();
        }

        if (totalSpawns >= MAX_SPAWNS) {
            player.sendMessage("§c❌ Достигнут лимит спавнов! Максимум: " + MAX_SPAWNS);
            player.sendMessage("§cИспользуйте /regis removespawn <название> чтобы удалить ненужные спавны");
            return false;
        }

        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return false;

        String path = "spawns." + name;

        spawnsConfig.set(path + ".world", world.getName());
        spawnsConfig.set(path + ".x", loc.getX());
        spawnsConfig.set(path + ".y", loc.getY());
        spawnsConfig.set(path + ".z", loc.getZ());
        spawnsConfig.set(path + ".yaw", (float) loc.getYaw());
        spawnsConfig.set(path + ".pitch", (float) loc.getPitch());
        spawnsConfig.set(path + ".enabled", true);
        updateEnabledCount();
        saveSpawnsConfig();
        player.sendMessage("§a✅ Спавн '" + name + "' успешно создан! (" + (totalSpawns + 1) + "/" + MAX_SPAWNS + ")");
        return true;
    }

    public boolean removeSpawn(String name) {
        if (spawnsConfig.contains("spawns." + name)) {
            spawnsConfig.set("spawns." + name, null);
            updateEnabledCount();
            saveSpawnsConfig();
            return true;
        }
        return false;
    }

    public boolean toggleSpawn(String name) {
        String path = "spawns." + name + ".enabled";
        if (spawnsConfig.contains(path)) {
            boolean current = spawnsConfig.getBoolean(path);
            spawnsConfig.set(path, !current);
            updateEnabledCount();
            saveSpawnsConfig();
            return true;
        }
        return false;
    }

    private void updateEnabledCount() {
        int count = 0;
        if (spawnsConfig.contains("spawns")) {
            for (String key : spawnsConfig.getConfigurationSection("spawns").getKeys(false)) {
                if (spawnsConfig.getBoolean("spawns." + key + ".enabled", false)) {
                    count++;
                }
            }
        }
        spawnsConfig.set("enabled-count", count);
    }

    public Location getRandomSpawn() {
        List<Location> enabledSpawns = new ArrayList<>();

        if (!spawnsConfig.contains("spawns")) {
            return null;
        }

        for (String key : spawnsConfig.getConfigurationSection("spawns").getKeys(false)) {
            if (spawnsConfig.getBoolean("spawns." + key + ".enabled", false)) {
                String worldName = spawnsConfig.getString("spawns." + key + ".world");
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    double x = spawnsConfig.getDouble("spawns." + key + ".x");
                    double y = spawnsConfig.getDouble("spawns." + key + ".y");
                    double z = spawnsConfig.getDouble("spawns." + key + ".z");
                    float yaw = (float) spawnsConfig.getDouble("spawns." + key + ".yaw");
                    float pitch = (float) spawnsConfig.getDouble("spawns." + key + ".pitch");

                    enabledSpawns.add(new Location(world, x, y, z, yaw, pitch));
                }
            }
        }

        if (enabledSpawns.isEmpty()) {
            return null;
        }

        return enabledSpawns.get(random.nextInt(enabledSpawns.size()));
    }

    public boolean teleportToSpawn(Player player) {
        Location spawn = getRandomSpawn();
        if (spawn != null) {
            player.teleport(spawn);
            player.sendMessage("§a✅ Телепортирован на спавн!");
            return true;
        } else {
            player.sendMessage("§c❌ Спавны не настроены или отключены!");
            return false;
        }
    }

    public void listSpawns(CommandSender sender) {
        sender.sendMessage("§6=== Список спавнов ===");
        if (!spawnsConfig.contains("spawns") || spawnsConfig.getConfigurationSection("spawns").getKeys(false).isEmpty()) {
            sender.sendMessage("§cСпавны не найдены");
            return;
        }
        int totalSpawns = spawnsConfig.getConfigurationSection("spawns").getKeys(false).size();
        sender.sendMessage("§7Всего спавнов: §f" + totalSpawns + "/" + MAX_SPAWNS);
        for (String key : spawnsConfig.getConfigurationSection("spawns").getKeys(false)) {
            boolean enabled = spawnsConfig.getBoolean("spawns." + key + ".enabled", false);
            String world = spawnsConfig.getString("spawns." + key + ".world", "unknown");
            String status = enabled ? "§a✓ Включен" : "§c✗ Отключен";

            sender.sendMessage("§7- §f" + key + " §7(" + world + ") " + status);

            if (enabled) {
                double x = spawnsConfig.getDouble("spawns." + key + ".x");
                double y = spawnsConfig.getDouble("spawns." + key + ".y");
                double z = spawnsConfig.getDouble("spawns." + key + ".z");
                sender.sendMessage("  §8Координаты: §f" + Math.round(x) + " " + Math.round(y) + " " + Math.round(z));
            }
        }

        int enabledCount = spawnsConfig.getInt("enabled-count", 0);
        sender.sendMessage("§7Включено: §f" + enabledCount + "  §7Отключено: §f" + (totalSpawns - enabledCount));
    }
    public int getEnabledCount() {
        return spawnsConfig.getInt("enabled-count", 0);
    }
    public int getTotalSpawns() {
        if (!spawnsConfig.contains("spawns")) {
            return 0;
        }
        return spawnsConfig.getConfigurationSection("spawns").getKeys(false).size();
    }

}