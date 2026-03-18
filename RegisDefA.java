package zxc.zxc.rEgiS;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class RegisDefA implements Listener {

    private final REgiS plugin;
    private File countriesFile;
    private FileConfiguration countriesConfig;

    private final Map<String, List<Long>> commandAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> tempBans = new ConcurrentHashMap<>();
    private final Map<String, String> ipCountryCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastIpConnection = new ConcurrentHashMap<>();
    private final Map<String, Long> ipBans = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final Set<String> AUTH_COMMANDS = Set.of("/reg", "/register", "/r", "/login", "/l", "/log");
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    public RegisDefA(REgiS plugin) {
        this.plugin = plugin;
        loadCountriesConfig();
        startCleanupTasks();
    }

    private void loadCountriesConfig() {
        countriesFile = new File(plugin.getDataFolder(), "Countries.yml");
        if (!countriesFile.exists()) {
            plugin.saveResource("Countries.yml", false);
        }
        countriesConfig = YamlConfiguration.loadConfiguration(countriesFile);
    }
    private void startCleanupTasks() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            tempBans.entrySet().removeIf(entry -> entry.getValue() < now);
            ipBans.entrySet().removeIf(entry -> entry.getValue() < now); // Очистка IP банов
            lastIpConnection.entrySet().removeIf(entry -> (now - entry.getValue()) > 10000);
        }, 20L * 60, 20L * 60);
    }
    public void removeTempBan(String playerName) {
        tempBans.remove(playerName);
    }

    public void removeIpBan(String ip) {
        ipBans.remove(ip);
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        if (plugin.isPlayerLocked(playerName)) {
            int timeoutSeconds = plugin.getConfig().getInt("security.auth-timeout-seconds", 60);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayerExact(playerName);
                if (p != null && p.isOnline() && plugin.isPlayerLocked(playerName)) {
                    p.kickPlayer("§cВремя на авторизацию истекло!\n§7Пожалуйста, перезайдите и авторизуйтесь быстрее.");
                }
            }, 20L * timeoutSeconds);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        String ip = event.getAddress().getHostAddress();
        String playerName = player.getName();
        String mode = plugin.getConfig().getString("security.mode", "average").toLowerCase();
        if (!VALID_NAME_PATTERN.matcher(playerName).matches()) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "§cНедопустимый никнейм.\n§7Используйте только латинские буквы, цифры и '_'.");
            return;
        }

        if (mode.equals("max")) {
            long lastConn = lastIpConnection.getOrDefault(ip, 0L);
            if (System.currentTimeMillis() - lastConn < 3000) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "§cПодозрительно частые подключения!\n§7Подождите пару секунд перед входом.");
                return;
            }
            lastIpConnection.put(ip, System.currentTimeMillis());
        }

        if (tempBans.containsKey(playerName)) {
            long expiry = tempBans.get(playerName);
            if (System.currentTimeMillis() < expiry) {
                long minutesLeft = TimeUnit.MILLISECONDS.toMinutes(expiry - System.currentTimeMillis());
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                        "§cВременно заблокирован за подозрительную активность.\n" +
                                "§7(Подозрение на бота или спам командами)\n" +
                                "§7Осталось: " + minutesLeft + " мин.");
                return;
            } else {
                tempBans.remove(playerName);
            }
        }

        String country = getCountryByIP(ip);
        if (country != null && !isCountryAllowed(country)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "§cДоступ с вашей страны запрещён политикой сервера.");
            plugin.getLogger().info("Блокировка входа " + playerName + " из страны " + country);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Thread.sleep(1500);
                if (!player.isOnline()) return;

                long ping = player.getPing();
                int threshold = plugin.getConfig().getInt("security.ping-threshold", 300);

                if (ping > threshold) {
                    plugin.getLogger().warning("Высокий пинг у " + playerName + ": " + ping + " мс");
                    if ("max".equals(mode)) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) {
                                player.kickPlayer("§cВаш пинг слишком высок (" + ping + " мс).\n§7Подозрение на использование прокси/VPN.");
                                applyTempBan(playerName, "Высокий пинг (Прокси)");
                            }
                        });
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        if (!plugin.isPlayerLocked(playerName)) return;
        String message = event.getMessage();
        String[] parts = message.split("\\s+");
        if (parts.length == 0) return;

        String cmd = parts[0].toLowerCase();
        String mode = plugin.getConfig().getString("security.mode", "average").toLowerCase();

        List<String> allowedCommands = plugin.getConfig().getStringList("onregister.commandallowed");
        boolean isAllowed = false;
        for (String allowed : allowedCommands) {
            String formattedAllowed = allowed.startsWith("/") ? allowed.toLowerCase() : "/" + allowed.toLowerCase();
            if (cmd.equals(formattedAllowed)) {
                isAllowed = true;
                break;
            }
        }

        if (!isAllowed && !AUTH_COMMANDS.contains(cmd)) {
            event.setCancelled(true);
            player.sendMessage("§c❌ Сначала авторизуйтесь на сайте!");
            return;
        }

        if (AUTH_COMMANDS.contains(cmd)) {
            if (parts.length > 1) {
                event.setCancelled(true);
                if (mode.equals("max")) {
                    player.kickPlayer("§c❌ Ошибка формата!\n§7У нас регистрация через сайт. Пишите просто " + cmd);
                    applyTempBan(playerName, "Попытка ввода пароля в чат (бот)");
                } else {
                    player.sendMessage("§c❌ Пишите просто §e" + cmd + " §cбез паролей и пробелов!");
                    recordAttempt(playerName);
                }
                return;
            }

            recordAttempt(playerName);
            int maxAttempts = mode.equals("max") ? 3 : (mode.equals("weak") ? 7 : 5);
            boolean banOnRepeat = !mode.equals("weak");
            int attempts = getAttemptCount(playerName, 15);

            if (attempts >= maxAttempts) {
                handleExcessiveAttempts(player, playerName, banOnRepeat);
                event.setCancelled(true);
            } else if (attempts == maxAttempts - 1) {
                player.sendMessage("§c⚠️ Вы отправляете команды слишком часто!");
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerName = event.getPlayer().getName();
        commandAttempts.remove(playerName);
    }

    private void recordAttempt(String playerName) {
        commandAttempts.computeIfAbsent(playerName, k -> new ArrayList<>()).add(System.currentTimeMillis());
    }

    private int getAttemptCount(String playerName, int seconds) {
        List<Long> attempts = commandAttempts.get(playerName);
        if (attempts == null) return 0;
        long cutoff = System.currentTimeMillis() - seconds * 1000L;
        attempts.removeIf(t -> t < cutoff);
        return attempts.size();
    }

    private void handleExcessiveAttempts(Player player, String playerName, boolean banOnRepeat) {
        player.kickPlayer("§c❌ Вы были отключены за спам командами.\n§7Пожалуйста, дождитесь загрузки и не флудите.");

        if (banOnRepeat) {
            if (tempBans.containsKey(playerName)) {
                long newExpiry = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30);
                tempBans.put(playerName, newExpiry);
                plugin.getLogger().warning("Игрок " + playerName + " забанен на 30 мин за спам командами.");
            } else {
                applyTempBan(playerName, "Спам командами авторизации");
            }
        }
    }

    private void applyTempBan(String playerName, String reason) {
        int banMinutes = plugin.getConfig().getInt("security.temp-ban-minutes", 10);
        long expiry = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(banMinutes);
        tempBans.put(playerName, expiry);
        plugin.getLogger().warning("Временный бан для " + playerName + " по причине: " + reason);
    }

    private String getCountryByIP(String ip) {
        if (ipCountryCache.containsKey(ip)) return ipCountryCache.get(ip);
        if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0")) return null;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json/" + ip + "?fields=countryCode"))
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String countryCode = response.body().replaceAll(".*\"countryCode\":\"([^\"]+)\".*", "$1");
                if (!countryCode.isEmpty() && !countryCode.contains("{")) {
                    ipCountryCache.put(ip, countryCode);
                    return countryCode;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка IP-API для " + ip + ": " + e.getMessage());
        }
        return null;
    }
    private boolean isCountryAllowed(String country) {
        List<String> enabled = countriesConfig.getStringList("EnabledCountries");
        List<String> disabled = countriesConfig.getStringList("DisabledCountries");
        String mode = countriesConfig.getString("FilterMode", "blacklist");

        return "whitelist".equalsIgnoreCase(mode) ? enabled.contains(country) : !disabled.contains(country);
    }
}