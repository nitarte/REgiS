package zxc.zxc.rEgiS;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.mindrot.jbcrypt.BCrypt;

public final class REgiS extends JavaPlugin implements CommandExecutor, Listener {
    private EffectOnRegist effectOnRegist;
    private HttpServer server;
    private Connection connection;
    private final Set<String> lockedPlayers = new HashSet<>();
    private JDA jda;
    private RegisDefA regisDefA;
    private final Map<String, TwoFactorCode> pending2FA = new ConcurrentHashMap<>();
    private SpawnLogic spawnLogic;
    private EventRegister eventRegister;
    private final Map<String, Long> webAuthSessions = new ConcurrentHashMap<>();
    private static class TwoFactorCode {
        final String code;
        final long expiry;
        TwoFactorCode(String code) {
            this.code = code;
            this.expiry = System.currentTimeMillis() + 300_000;
        }
        boolean isValid() { return System.currentTimeMillis() < expiry; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initDatabase();
        getServer().getPluginManager().registerEvents(this, this);
        startWebServer(getConfig().getInt("web.port", 25618));
        getCommand("reg").setExecutor(this);
        getCommand("login").setExecutor(this);
        getCommand("regis").setExecutor(this);
        getCommand("unregban").setExecutor(this);
        getCommand("unregbanip").setExecutor(this);
        eventRegister = new EventRegister(this);
        spawnLogic = new SpawnLogic(this);
        effectOnRegist = new EffectOnRegist(this);
        this.regisDefA = new RegisDefA(this);
        getServer().getPluginManager().registerEvents(regisDefA, this);
        getServer().getPluginManager().registerEvents(new RegiDef(this), this);
        if (getConfig().getBoolean("discord.enabled") && getConfig().contains("discord.bot_token")) {
            String token = getConfig().getString("discord.bot_token");
            try {
                jda = JDABuilder.createDefault(token).build();
                jda.awaitReady();
                getLogger().info("Discord bot connected successfully.");
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Discord bot: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                getLogger().info("Проверка игрока после перезагрузки: " + player.getName());
                playerDataMap.remove(player.getName());
                lockedPlayers.remove(player.getName());
                pending2FA.remove(player.getName());
                verified2FA.remove(player.getName());
                String ip = player.getAddress().getAddress().getHostAddress();
                long maxAge = getConfig().getLong("session.max-age-minutes", 10) * 60000;

                try (PreparedStatement ps = connection.prepareStatement("SELECT last_seen, ip FROM users WHERE username = ?")) {
                    ps.setString(1, player.getName());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        long lastSeen = rs.getLong("last_seen");
                        String lastIp = rs.getString("ip");
                        if (ip.equals(lastIp) && (System.currentTimeMillis() - lastSeen) < maxAge) {
                            player.sendMessage(t("messages.auto_login"));
                            updateSession(player.getName(), ip);
                            getLogger().info("Игрок " + player.getName() + " авто-авторизован после перезагрузки");
                            continue;
                        }
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
                getLogger().info("Игрок " + player.getName() + " заблокирован после перезагрузки (требуется авторизация)");
                playerDataMap.put(player.getName(), new PlayerData(player));
                lockedPlayers.add(player.getName());

                if (spawnLogic.getEnabledCount() > 0) {
                    spawnLogic.teleportToSpawn(player);
                }

                effectOnRegist.applyOnRegister(player);
                sendLink(player);
            }
        }, 20L);

        Bukkit.getConsoleSender().sendMessage("§b╔════════════════════════════════════╗");
        Bukkit.getConsoleSender().sendMessage("§b║           REgiS v1.4               ║");
        Bukkit.getConsoleSender().sendMessage("§b║      Регистрация через сайт        ║");
        Bukkit.getConsoleSender().sendMessage("§b╠════════════════════════════════════╣");
        Bukkit.getConsoleSender().sendMessage("§b║                                    ║");
        Bukkit.getConsoleSender().sendMessage("§b║  §fАвтор: §c§lKaZak§b                      ║");
        Bukkit.getConsoleSender().sendMessage("§b║  §fВерсия: §a1.4 (2FA) §b                ║");
        Bukkit.getConsoleSender().sendMessage("§b║  §fMinecraft: §c1.20.1 §b                ║");
        Bukkit.getConsoleSender().sendMessage("§b║  §fJava: §e§l17§b                          ║");
        Bukkit.getConsoleSender().sendMessage("§b║                                    ║");
        Bukkit.getConsoleSender().sendMessage("§b╚════════════════════════════════════╝");

        getLogger().info("Плагин успешно запущен! Веб-сервер на порту " + getConfig().getInt("web.port", 25618));
    }
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        String playerName = e.getPlayer().getName();
        verified2FA.remove(playerName);
        pending2FA.remove(playerName);
        lockedPlayers.remove(playerName);
        playerDataMap.remove(playerName);
    }
    @Override
    public void onDisable() {
        if (server != null) server.stop(0);
        try { if (connection != null) connection.close(); } catch (SQLException e) { e.printStackTrace(); }
        if (jda != null) jda.shutdown();
    }
    public Connection getConnection() {
        return connection;
    }
    private void initDatabase() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "database.db").getAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT, ip TEXT, discord_id TEXT, last_seen LONG)");
                try { s.execute("ALTER TABLE users ADD COLUMN discord_name TEXT"); } catch (SQLException ignored) {}
                try { s.execute("ALTER TABLE users ADD COLUMN discord_avatar TEXT"); } catch (SQLException ignored) {}
                try { s.execute("ALTER TABLE users ADD COLUMN twofa_enabled INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }
    private final Map<String, PlayerData> playerDataMap = new ConcurrentHashMap<>();

    private static class PlayerData {
        final Location location;
        final GameMode gameMode;
        final Collection<PotionEffect> effects;

        PlayerData(Player player) {
            this.location = player.getLocation();
            this.gameMode = player.getGameMode();
            this.effects = player.getActivePotionEffects().stream()
                    .map(effect -> new PotionEffect(
                            effect.getType(),
                            effect.getDuration(),
                            effect.getAmplifier(),
                            effect.isAmbient(),
                            effect.hasParticles(),
                            effect.hasIcon()
                    ))
                    .collect(Collectors.toList());
        }

        void restore(Player player) {
            player.teleport(location);
            player.setGameMode(gameMode);
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            player.addPotionEffects(effects);
        }
    }
    private void startWebServer(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/auth", ex -> {
                String user = getQueryParam(ex.getRequestURI().getQuery(), "user");
                sendResponse(ex, getAdvancedHtml(user != null ? user : "Player"), "text/html");
            });
            server.createContext("/api/submit", ex -> {
                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String user = getQueryParam(body, "user");
                    String pass = getQueryParam(body, "password");
                    String result = handleAuth(user, pass);
                    sendResponse(ex, result, "text/plain");
                }
            });
            server.createContext("/api/verify2fa", ex -> {
                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String user = getQueryParam(body, "user");
                    String code = getQueryParam(body, "code");
                    TwoFactorCode tfc = pending2FA.get(user);
                    if (tfc != null && tfc.isValid() && tfc.code.equals(code)) {
                        pending2FA.remove(user);
                        mark2FAVerified(user);
                        unlock(user);
                        sendResponse(ex, "OK", "text/plain");
                    } else {
                        sendResponse(ex, "ERROR", "text/plain");
                    }
                }
            });

            server.createContext("/discord-callback", ex -> {
                String query = ex.getRequestURI().getQuery();
                String code = getQueryParam(query, "code");
                String mcUser = getQueryParam(query, "state");

                if (code != null && mcUser != null) {

                    Long lastAuth = webAuthSessions.get(mcUser);
                    if (lastAuth == null || System.currentTimeMillis() - lastAuth > 300000) {
                        sendResponse(ex, "<h2>❌ Ошибка привязки</h2><p>Сначала авторизуйтесь на сайте (введите пароль).</p><p><a href='/auth?user=" + mcUser + "'>Вернуться к авторизации</a></p>", "text/html");
                        return;
                    }

                    String data = exchangeCodeForId(code);
                    if (data != null) {
                        String[] parts = data.split("\\|");
                        if (parts.length >= 3) {
                            String discordId = parts[0];
                            try (PreparedStatement checkPs = connection.prepareStatement("SELECT username FROM users WHERE discord_id = ?")) {
                                checkPs.setString(1, discordId);
                                try (ResultSet checkRs = checkPs.executeQuery()) {
                                    if (checkRs.next()) {
                                        String existingUser = checkRs.getString("username");
                                        if (!existingUser.equals(mcUser)) {
                                            sendResponse(ex, "<h2>❌ Ошибка привязки</h2><p>Этот Discord аккаунт уже привязан к другому игроку.</p>", "text/html");
                                            return;
                                        }
                                    }
                                }
                            } catch (SQLException e) {
                                e.printStackTrace();
                                sendResponse(ex, "<h2>❌ Ошибка сервера</h2><p>Произошла ошибка при обращении к базе данных.</p>", "text/html");
                                return;
                            }
                            saveDiscordData(mcUser, parts[0], parts[1], parts[2]);
                            if (jda != null) {
                                sendDiscordLinkedNotification(parts[0], mcUser, parts[1]);
                            }
                            unlock(mcUser);
                            String returnUrl = "/auth?user=" + mcUser;
                            sendResponse(ex, getSuccessHtml(mcUser, parts[1], returnUrl), "text/html");
                            return;
                        } else {
                            getLogger().warning("Ошибка парсинга данных Discord: " + data);
                        }
                    }
                }
                sendResponse(ex, "<h2>Ошибка привязки. Проверьте консоль сервера.</h2>", "text/html");
            });

            server.createContext("/api/command", ex -> {
                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String user = getQueryParam(body, "user");
                    String cmd = getQueryParam(body, "cmd");
                    String val = getQueryParam(body, "val");
                    Long lastAuth = webAuthSessions.get(user);
                    if (lastAuth == null || System.currentTimeMillis() - lastAuth > 300000) {
                        sendResponse(ex, "Ошибка: Нет активной сессии! Авторизуйтесь заново.", "text/plain");
                        return;
                    }
                    String result = "Неизвестная команда";
                    Player p = Bukkit.getPlayer(user);

                    if ("kick".equals(cmd)) {
                        Bukkit.getScheduler().runTask(this, () -> { if(p != null) p.kickPlayer("§cСессия завершена через веб-панель"); });
                        result = "Игрок кикнут";
                    } else if ("ban".equals(cmd)) {
                        Bukkit.getScheduler().runTask(this, () -> Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(user, "Self-ban via Web", null, null));
                        result = "Аккаунт забанен";
                    } else if ("unban".equals(cmd)) {
                        Bukkit.getScheduler().runTask(this, () -> Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(user));
                        result = "Аккаунт разбанен";
                    } else if ("ips".equals(cmd)) {
                        result = "Зарегистрированные IP: [" + getPlayerIp(user) + "]";
                    } else if ("reset".equals(cmd)) {
                        if (val == null || val.length() < 4) {
                            result = "Ошибка: Пароль слишком короткий!";
                        } else {
                            try {
                                String hashedPass = BCrypt.hashpw(val, BCrypt.gensalt());
                                try (PreparedStatement ps = connection.prepareStatement("UPDATE users SET password = ? WHERE username = ?")) {
                                    ps.setString(1, hashedPass);
                                    ps.setString(2, user);
                                    ps.executeUpdate();
                                    result = "Пароль успешно изменен";
                                }
                            } catch (SQLException e) {
                                result = "Ошибка БД";
                            }
                        }
                    }
                    sendResponse(ex, result, "text/plain");
                }
            });
            server.createContext("/background.jpg", ex -> {
                File bg = new File(getDataFolder(), getConfig().getString("site.bg-file", "background.jpg"));
                if (bg.exists()) {
                    byte[] b = Files.readAllBytes(bg.toPath());
                    ex.getResponseHeaders().set("Content-Type", "image/jpeg");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                } else ex.sendResponseHeaders(404, -1);
                ex.close();
            });

            server.start();
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void sendDiscordLinkedNotification(String discordId, String mcUser, String dsName) {
        jda.retrieveUserById(discordId).queue(user -> {
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("🔗 Discord привязан к Minecraft");
            embed.setDescription("Ваш Discord аккаунт **@" + dsName + "** успешно привязан к игроку **" + mcUser + "**!");

            embed.addField("✅ Что теперь доступно?",
                    "• Двухфакторная аутентификация (2FA)\n" +
                            "• Вход через Discord\n" +
                            "• Управление аккаунтом через Discord", false);

            embed.addField("🔐 Безопасность",
                    "Теперь при входе в игру вы можете получать коды подтверждения сюда", false);

            embed.setColor(new Color(88, 101, 242));
            embed.setFooter("REgiS Authentication", jda.getSelfUser().getAvatarUrl());
            embed.setTimestamp(Instant.now());

            user.openPrivateChannel().queue(channel ->
                    channel.sendMessageEmbeds(embed.build()).queue(
                            success -> getLogger().info("Discord link notification sent to " + mcUser),
                            failure -> getLogger().warning("Failed to send DM to " + discordId)
                    )
            );
        }, failure -> getLogger().warning("User with ID " + discordId + " not found."));
    }
    private String exchangeCodeForId(String code) {
        try {
            URL url = new URL("https://discord.com/api/oauth2/token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "REgiS-Auth-Plugin");

            String params = "client_id=" + getConfig().getString("discord.client_id") +
                    "&client_secret=" + getConfig().getString("discord.client_secret") +
                    "&grant_type=authorization_code&code=" + code +
                    "&redirect_uri=" + getConfig().getString("discord.redirect_uri");

            conn.getOutputStream().write(params.getBytes(StandardCharsets.UTF_8));
            if (conn.getResponseCode() != 200) return null;

            String res = new String(conn.getInputStream().readAllBytes());
            String token = extractJsonValue(res, "access_token");

            URL userUrl = new URL("https://discord.com/api/users/@me");
            HttpURLConnection userConn = (HttpURLConnection) userUrl.openConnection();
            userConn.setRequestProperty("Authorization", "Bearer " + token);
            userConn.setRequestProperty("User-Agent", "REgiS-Auth-Plugin");

            if (userConn.getResponseCode() != 200) return null;

            String userRes = new String(userConn.getInputStream().readAllBytes());
            String id = extractJsonValue(userRes, "id");
            String name = extractJsonValue(userRes, "username");
            String avatarHash = extractJsonValue(userRes, "avatar");

            String avatarUrl = (avatarHash != null)
                    ? "https://cdn.discordapp.com/avatars/" + id + "/" + avatarHash + ".png"
                    : "https://cdn.discordapp.com/embed/avatars/0.png";

            return id + "|" + name + "|" + avatarUrl;
        } catch (Exception e) { return null; }
    }

    private String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\":\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) return matcher.group(1);
        return null;
    }
    private void sendDiscordNotification(String mcUser, String dsName, boolean isEnabled) {
        Player player = Bukkit.getPlayer(mcUser);
        if (player != null && player.isOnline()) {
            if (isEnabled) {
                player.sendMessage("§a✅ Ваш Discord аккаунт §b@" + dsName + "§a успешно привязан!");
                player.sendMessage("§7Теперь вы можете использовать двухфакторную аутентификацию.");
            } else {
                player.sendMessage("§c❌ Привязка Discord аккаунта отменена или не удалась.");
            }
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String ip = p.getAddress().getAddress().getHostAddress();
        long maxAge = getConfig().getLong("session.max-age-minutes", 10) * 60000;

        try (PreparedStatement ps = connection.prepareStatement("SELECT last_seen, ip FROM users WHERE username = ?")) {
            ps.setString(1, p.getName());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long lastSeen = rs.getLong("last_seen");
                String lastIp = rs.getString("ip");
                if (ip.equals(lastIp) && (System.currentTimeMillis() - lastSeen) < maxAge) {
                    p.sendMessage(t("messages.auto_login"));
                    updateSession(p.getName(), ip);
                    return;
                }
            }
        } catch (SQLException ex) { ex.printStackTrace(); }
        playerDataMap.put(p.getName(), new PlayerData(p));
        lockedPlayers.add(p.getName());
        if (spawnLogic.getEnabledCount() > 0) {
            spawnLogic.teleportToSpawn(p);
        }
        effectOnRegist.applyOnRegister(p);
        sendLink(p);
    }

    private void updateSession(String user, String ip) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE users SET last_seen = ?, ip = ? WHERE username = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ip); ps.setString(3, user);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void saveDiscordData(String user, String dsId, String dsName, String dsAvatar) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE users SET discord_id = ?, discord_name = ?, discord_avatar = ? WHERE username = ?")) {
            ps.setString(1, dsId);
            ps.setString(2, dsName);
            ps.setString(3, dsAvatar);
            ps.setString(4, user);
            ps.executeUpdate();
            updateSession(user, getPlayerIp(user));
            sendDiscordNotification(user, dsName, true);
            getLogger().info("Discord аккаунт @" + dsName + " привязан к игроку " + user);
        } catch (SQLException e) {
            e.printStackTrace();
            sendDiscordNotification(user, dsName, false);
        }
    }

    private String handleAuth(String user, String pass) {
        if (user == null || pass == null || pass.isEmpty()) return "ERROR";
        Player player = Bukkit.getPlayer(user);
        try (PreparedStatement ps = connection.prepareStatement("SELECT password, twofa_enabled, discord_id FROM users WHERE username = ?")) {
            ps.setString(1, user);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String storedHash = rs.getString("password");
                if (!BCrypt.checkpw(pass, storedHash)) return "ERROR";
                boolean hasDiscord = rs.getString("discord_id") != null && !rs.getString("discord_id").isEmpty();
                if (rs.getInt("twofa_enabled") == 1) {
                    if (!is2FAVerified(user)) {
                        send2FACodeOnLogin(user);
                        return "2FA_REQUIRED";
                    }
                }
                if (player != null && !lockedPlayers.contains(user)) {
                    updateSession(user, getPlayerIp(user));
                    webAuthSessions.put(user, System.currentTimeMillis());
                    if (!hasDiscord) {
                        return "DISCORD_LINK_READY";
                    }
                    return "ALREADY_AUTH";
                }
                updateSession(user, getPlayerIp(user));
                unlock(user);
                webAuthSessions.put(user, System.currentTimeMillis());
                if (!hasDiscord) {
                    return "DISCORD_LINK_READY";
                }
                return "OK";
            } else {
                String hashedPass = BCrypt.hashpw(pass, BCrypt.gensalt());
                try (PreparedStatement ins = connection.prepareStatement("INSERT INTO users (username, password, ip, last_seen, twofa_enabled) VALUES (?, ?, ?, ?, 0)")) {
                    ins.setString(1, user);
                    ins.setString(2, hashedPass); // Сохраняем хеш
                    ins.setString(3, getPlayerIp(user));
                    ins.setLong(4, System.currentTimeMillis());
                    ins.executeUpdate();
                }
                unlock(user);
                webAuthSessions.put(user, System.currentTimeMillis());
                return "OK";
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }
    }
    public boolean isPlayerLocked(String playerName) {
        return lockedPlayers.contains(playerName);


    }

    private final Map<String, Long> verified2FA = new ConcurrentHashMap<>();

    private boolean is2FAVerified(String user) {
        Long timestamp = verified2FA.get(user);
        if (timestamp == null) return false;
        return System.currentTimeMillis() - timestamp < 3600000;
    }

    private void mark2FAVerified(String user) {
        verified2FA.put(user, System.currentTimeMillis());
    }
    private void unlock(String name) {
        boolean wasLocked = lockedPlayers.contains(name);
        lockedPlayers.remove(name);
        pending2FA.remove(name);
        verified2FA.remove(name);

        Bukkit.getScheduler().runTask(this, () -> {
            Player p = Bukkit.getPlayer(name);
            if (p != null) {
                if (!wasLocked) return;

                PlayerData data = playerDataMap.remove(name);
                if (data != null) {
                    data.restore(p);
                } else {
                    p.setGameMode(GameMode.SURVIVAL);
                }
                for (PotionEffect effect : p.getActivePotionEffects()) {
                    p.removePotionEffect(effect.getType());
                }
                effectOnRegist.clearEffects(p);

                p.sendMessage(t("messages.success"));
            }
        });
    }

    private void sendLink(Player p) {
        String domain = getConfig().getString("web.domain");
        int port = getConfig().getInt("web.port");
        String url = "http://" + domain + ":" + port + "/auth?user=" + p.getName();
        p.sendMessage(t("messages.must_auth").replace("%domain%", domain).replace("%port%", String.valueOf(port)).replace("%player%", p.getName()));
    }

    public String t(String path) {
        String msg = getConfig().getString(path, path);
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "") + msg);
    }
    private String getPlayerIp(String name) {
        Player p = Bukkit.getPlayer(name);
        return p != null ? p.getAddress().getAddress().getHostAddress() : "0.0.0.0";
    }
    private void send2FACodeOnLogin(String user) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT discord_id FROM users WHERE username = ?")) {
            ps.setString(1, user);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String discordId = rs.getString("discord_id");
                if (discordId != null && !discordId.isEmpty() && jda != null) {
                    String code = generate2FACode();
                    pending2FA.put(user, new TwoFactorCode(code));
                    send2FACode(discordId, code, user);
                    getLogger().info("2FA code sent to " + user + " for login attempt");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String getQueryParam(String q, String k) {
        if (q == null) return null;
        for (String p : q.split("&")) {
            String[] kv = p.split("=");
            if (kv.length == 2 && kv[0].equals(k)) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private void sendResponse(HttpExchange ex, String content, String type) throws IOException {
        byte[] b = content.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        ex.close();
    }

    @EventHandler public void onMove(PlayerMoveEvent e) { if (lockedPlayers.contains(e.getPlayer().getName())) e.setCancelled(true); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("reg") || command.getName().equalsIgnoreCase("login")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (!lockedPlayers.contains(player.getName())) {
                    player.sendMessage(t("messages.already_authenticated"));
                    return true;
                }
                sendLink(player);
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("regis")) {
            if (!sender.hasPermission("regis.admin")) {
                sender.sendMessage(t("messages.no_permission"));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage("§cИспользование:");
                sender.sendMessage("§c/regis <игрок> - управление 2FA");
                sender.sendMessage("§c/regis setspawn <название> - создать спавн");
                sender.sendMessage("§c/regis removespawn <название> - удалить спавн");
                sender.sendMessage("§c/regis togglespawn <название> - вкл/выкл спавн");
                sender.sendMessage("§c/regis spawn <игрок> - телепорт на спавн");
                sender.sendMessage("§c/regis listspawns - список спавнов");
                return true;
            }
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("setspawn")) {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /regis setspawn <название>");
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cЭту команду можно использовать только в игре!");
                    return true;
                }
                String spawnName = args[1];
                Player player = (Player) sender;
                int totalSpawns = spawnLogic.getTotalSpawns();
                if (totalSpawns >= 5) {
                    sender.sendMessage("§c❌ Достигнут лимит спавнов! Максимум: 5");
                    sender.sendMessage("§cИспользуйте /regis removespawn <название> чтобы удалить ненужные спавны");
                    return true;
                }

                if (spawnLogic.setSpawn(spawnName, player)) {
                }
                return true;
            }

            if (subCommand.equals("removespawn")) {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /regis removespawn <название>");
                    return true;
                }
                String spawnName = args[1];
                if (spawnLogic.removeSpawn(spawnName)) {
                    sender.sendMessage("§a✅ Спавн '" + spawnName + "' удален!");
                } else {
                    sender.sendMessage("§c❌ Спавн '" + spawnName + "' не найден!");
                }
                return true;
            }
            if (subCommand.equals("resetpassword")) {
                if (args.length < 3) {
                    sender.sendMessage("§cИспользование: /regis resetpassword <игрок> <новый_пароль>");
                    return true;
                }
                String target = args[1];
                String newPassword = args[2];
                eventRegister.resetPassword(sender, target, newPassword);
                return true;
            }

            if (subCommand.equals("register")) {
                if (args.length < 3) {
                    sender.sendMessage("§cИспользование: /regis register <игрок> <пароль>");
                    return true;
                }
                String target = args[1];
                String password = args[2];
                eventRegister.forceRegister(sender, target, password);
                return true;
            }

            if (subCommand.equals("unregister")) {
                if (args.length < 3) {
                    sender.sendMessage("§cИспользование: /regis unregister <игрок> confirm");
                    return true;
                }
                String target = args[1];
                String confirm = args[2];
                eventRegister.unregister(sender, target, confirm);
                return true;
            }

            if (subCommand.equals("maxip")) {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /regis maxip <число>");
                    return true;
                }
                try {
                    int limit = Integer.parseInt(args[1]);
                    eventRegister.setMaxIp(sender, limit);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c❌ Укажите число!");
                }
                return true;
            }

            if (subCommand.equals("reload")) {
                eventRegister.reloadConfig(sender);
                return true;
            }

            if (subCommand.equals("help")) {
                int page = 1;
                if (args.length >= 2) {
                    try {
                        page = Integer.parseInt(args[1]);
                        if (page < 1 || page > 3) page = 1;
                    } catch (NumberFormatException e) {
                        page = 1;
                    }
                }
                eventRegister.sendHelp(sender, page);
                return true;
            }
            if (command.getName().equalsIgnoreCase("unregban")) {
                if (!sender.hasPermission("regis.admin")) {
                    sender.sendMessage("§c❌ У тебя нет прав!");
                    return true;
                }
                if (args.length < 1) {
                    sender.sendMessage("§cИспользование: /unregban <игрок>");
                    return true;
                }
                getRegisDefA().removeTempBan(args[0]);
                sender.sendMessage("§a✅ Временный бан (anti-bot) для игрока " + args[0] + " снят.");
                return true;
            }
            if (command.getName().equalsIgnoreCase("unregbanip")) {
                if (!sender.hasPermission("regis.admin")) {
                    sender.sendMessage("§c❌ У тебя нет прав!");
                    return true;
                }
                if (args.length < 1) {
                    sender.sendMessage("§cИспользование: /unregbanip <ip>");
                    return true;
                }
                getRegisDefA().removeIpBan(args[0]);
                sender.sendMessage("§a✅ Временный бан (anti-bot) для IP " + args[0] + " снят.");
                return true;
            }
            if (subCommand.equals("togglespawn")) {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /regis togglespawn <название>");
                    return true;
                }

                String spawnName = args[1];
                if (spawnLogic.toggleSpawn(spawnName)) {
                    sender.sendMessage("§a✅ Статус спавна '" + spawnName + "' изменен!");
                } else {
                    sender.sendMessage("§c❌ Спавн '" + spawnName + "' не найден!");
                }
                return true;
            }

            if (subCommand.equals("spawn")) {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /regis spawn <игрок>");
                    return true;
                }

                String targetName = args[1];
                Player target = Bukkit.getPlayer(targetName);

                if (target == null) {
                    sender.sendMessage("§c❌ Игрок не найден или не в сети!");
                    return true;
                }

                if (spawnLogic.teleportToSpawn(target)) {
                    sender.sendMessage("§a✅ Игрок " + targetName + " телепортирован на спавн!");
                }
                return true;
            }

            if (subCommand.equals("listspawns")) {
                spawnLogic.listSpawns(sender);
                return true;
            }
            if (args.length == 1) {
                String target = args[0];
                String adminName = sender.getName();

                try (PreparedStatement ps = connection.prepareStatement("SELECT discord_id, twofa_enabled FROM users WHERE username = ?")) {
                    ps.setString(1, target);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        sender.sendMessage("§cИгрок не найден в базе.");
                        return true;
                    }

                    String discordId = rs.getString("discord_id");
                    if (discordId == null || discordId.isEmpty()) {
                        sender.sendMessage("§cУ игрока не привязан Discord.");
                        return true;
                    }

                    boolean alreadyEnabled = rs.getInt("twofa_enabled") == 1;

                    if (alreadyEnabled) {
                        try (PreparedStatement upd = connection.prepareStatement("UPDATE users SET twofa_enabled = 0 WHERE username = ?")) {
                            upd.setString(1, target);
                            upd.executeUpdate();
                        }
                        if (jda != null) {
                            send2FADisabledNotification(discordId, target, adminName);
                        }
                        pending2FA.remove(target);

                        sender.sendMessage("§a2FA для игрока " + target + " отключена. Уведомление отправлено в Discord.");
                        return true;
                    } else {

                        if (jda == null) {
                            sender.sendMessage("§cDiscord бот не подключён. 2FA недоступна.");
                            return true;
                        }

                        try (PreparedStatement upd = connection.prepareStatement("UPDATE users SET twofa_enabled = 1 WHERE username = ?")) {
                            upd.setString(1, target);
                            upd.executeUpdate();
                        }
                        send2FAEnabledNotification(discordId, target, adminName);
                        sender.sendMessage("§a2FA для игрока " + target + " включена. Уведомление отправлено в Discord.");
                        sender.sendMessage("§7При следующем входе игрок получит код в Discord.");
                    }

                } catch (SQLException e) {
                    e.printStackTrace();
                    sender.sendMessage("§cОшибка базы данных.");
                }
                return true;

            }
        }
        return false;
    }
    public RegisDefA getRegisDefA() {
        return regisDefA;
    }
    private String generate2FACode() {
        return String.format("%06d", (int)(Math.random() * 1000000));
    }

    private void send2FAEnabledNotification(String discordId, String mcUser, String adminName) {
        jda.retrieveUserById(discordId).queue(user -> {
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("🔒 Двухфакторная аутентификация активирована");
            embed.setDescription("**" + adminName + "** включил двухфакторную защиту для вашего аккаунта **" + mcUser + "**.");

            embed.addField("📌 Что изменилось?",
                    "• Теперь при каждой попытке входа в игру\n" +
                            "• или через веб-панель\n" +
                            "• вам будет приходить код подтверждения сюда", false);

            embed.addField("🔐 Как это работает?",
                    "1. Вводите пароль на сайте\n" +
                            "2. Получаете 6-значный код здесь\n" +
                            "3. Вводите код для завершения входа", false);

            embed.setColor(new Color(88, 101, 242));
            embed.setFooter("Безопасность системы • " + jda.getSelfUser().getName(), jda.getSelfUser().getAvatarUrl());
            embed.setTimestamp(Instant.now());

            user.openPrivateChannel().queue(channel ->
                    channel.sendMessageEmbeds(embed.build()).queue(
                            success -> getLogger().info("2FA enabled notification sent to " + mcUser),
                            failure -> getLogger().warning("Failed to send DM to " + discordId + " (maybe DMs closed)")
                    )
            );
        }, failure -> getLogger().warning("User with ID " + discordId + " not found."));
    }
    private void send2FADisabledNotification(String discordId, String mcUser, String adminName) {
        jda.retrieveUserById(discordId).queue(user -> {
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("🔓 Двухфакторная аутентификация отключена");
            embed.setDescription("**" + adminName + "** отключил двухфакторную защиту для вашего аккаунта **" + mcUser + "**.");

            embed.addField("⚠️ Важно",
                    "Теперь вход в аккаунт будет происходить только по паролю, без дополнительного подтверждения.", false);

            embed.setColor(new Color(255, 170, 0));
            embed.setFooter("Безопасность системы • " + jda.getSelfUser().getName(), jda.getSelfUser().getAvatarUrl());
            embed.setTimestamp(Instant.now());

            user.openPrivateChannel().queue(channel ->
                    channel.sendMessageEmbeds(embed.build()).queue(
                            success -> getLogger().info("2FA disabled notification sent to " + mcUser),
                            failure -> getLogger().warning("Failed to send DM to " + discordId)
                    )
            );
        }, failure -> getLogger().warning("User with ID " + discordId + " not found."));
    }

    private void send2FACode(String discordId, String code, String mcUser) {
        jda.retrieveUserById(discordId).queue(user -> {
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("🔐 Код подтверждения входа");
            embed.setDescription("Попытка входа в аккаунт **" + mcUser + "**");

            // Красивое отображение кода
            embed.addField("📱 Ваш код:", "```\n" + code + "\n```", false);

            embed.addField("⏰ Срок действия", "Код действителен в течение 5 минут", true);
            embed.addField("🌐 IP адрес", "Новый вход с веб-панели", true);

            embed.addField("❓ Это не вы?",
                    "Если вы не пытаетесь войти в аккаунт, **немедленно сообщите администратору**!", false);

            embed.setColor(new Color(88, 101, 242));
            embed.setFooter("Никому не сообщайте этот код • " + jda.getSelfUser().getName(), jda.getSelfUser().getAvatarUrl());
            embed.setTimestamp(Instant.now());

            user.openPrivateChannel().queue(channel ->
                    channel.sendMessageEmbeds(embed.build()).queue(
                            success -> getLogger().info("2FA code sent to " + mcUser),
                            failure -> getLogger().warning("Failed to send DM to " + discordId)
                    )
            );
        }, failure -> getLogger().warning("User with ID " + discordId + " not found."));
    }

    private String getAdvancedHtml(String rawUser) {
        String user = rawUser.replaceAll("[^a-zA-Z0-9_]", "");
        String accent = getConfig().getString("site.accent-color", "#7289da");

        String dsLink = "https://discord.com/api/oauth2/authorize?client_id=" + getConfig().getString("discord.client_id") +
                "&redirect_uri=" + getConfig().getString("discord.redirect_uri") +
                "&response_type=code&scope=identify&state=" + user;
        boolean userExists = false;
        try (PreparedStatement ps = connection.prepareStatement("SELECT username FROM users WHERE username = ?")) {
            ps.setString(1, user);
            ResultSet rs = ps.executeQuery();
            userExists = rs.next();
        } catch (SQLException ignored) {}
        boolean isLinked = false;
        boolean twofaEnabled = false;
        String dsName = "Не привязан";
        String dsAvatar = "https://cdn-icons-png.flaticon.com/512/2111/2111370.png";

        try (PreparedStatement ps = connection.prepareStatement("SELECT discord_id, discord_name, discord_avatar, twofa_enabled FROM users WHERE username = ?")) {
            ps.setString(1, user);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String id = rs.getString("discord_id");
                if (id != null) {
                    isLinked = true;
                    dsName = rs.getString("discord_name");
                    if (dsName == null) dsName = "Linked User";
                    String rawAvatar = rs.getString("discord_avatar");
                    if (rawAvatar != null && rawAvatar.startsWith("http")) {
                        dsAvatar = rawAvatar;
                    }
                }
                twofaEnabled = rs.getInt("twofa_enabled") == 1;
            }
        } catch (SQLException ignored) {}

        return """
    <!DOCTYPE html>
    <html lang="ru">
    <head>
        <meta charset="UTF-8">
        <title>%TITLE%</title>
        <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;700&family=Fira+Code:wght@400&display=swap" rel="stylesheet">
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css">
        <style>
            :root { --accent: %ACCENT%; --bg: #0f172a; --ds: #5865F2; }
            * { box-sizing: border-box; transition: all 0.5s cubic-bezier(0.34, 1.56, 0.64, 1); }
            
            body { 
                margin: 0; height: 100vh; display: flex; align-items: center; justify-content: center; 
                background: var(--bg) url('/background.jpg') center/cover; 
                font-family: 'Outfit', sans-serif; overflow: hidden; color: white;
            }
            body::after { content: ''; position: absolute; inset: 0; background: rgba(15, 23, 42, 0.8); z-index: 1; }

            .main-container { position: relative; z-index: 3; display: flex; align-items: center; justify-content: center; gap: 40px; width: 100%; max-width: 1200px; }
            .ds-card {
                background: rgba(88, 101, 242, 0.1); border: 1px solid rgba(88, 101, 242, 0.3);
                border-radius: 20px; padding: 15px; display: flex; align-items: center; gap: 15px;
                margin-bottom: 25px; text-align: left; backdrop-filter: blur(10px);
            }
            .ds-avatar { width: 45px; height: 45px; border-radius: 50%; border: 2px solid var(--ds); }
            .ds-info { line-height: 1.2; }
            .ds-info b { display: block; font-size: 14px; color: #fff; }
            .ds-info span { font-size: 12px; color: rgba(255,255,255,0.5); }

            .card, .known-box { 
                width: 380px; padding: 50px 40px; 
                background: rgba(255, 255, 255, 0.03); backdrop-filter: blur(25px) saturate(180%);
                border-radius: 40px; border: 1px solid rgba(255, 255, 255, 0.1);
                box-shadow: 0 20px 50px rgba(0,0,0,0.5); text-align: center;
            }
            button:disabled {
                background: #1e293b !important;
                color: rgba(255,255,255,0.2) !important;
                cursor: not-allowed;
                transform: none !important;
            }
            .avatar-wrap { position: relative; display: inline-block; margin-bottom: 20px; cursor: pointer; }
            .avatar { width: 110px; height: 110px; border-radius: 35px; border: 3px solid var(--accent); padding: 5px; background: rgba(0,0,0,0.3); }
            
            .badge-2fa {
                            position: absolute; bottom: 0; right: 0;
                            background: var(--accent); border-radius: 50%; width: 32px; height: 32px;
                            display: flex; align-items: center; justify-content: center;
                            border: 2px solid white; color: white; font-size: 18px;
                            box-shadow: 0 0 10px var(--accent);
                        }
            
            h2 { margin: 0 0 10px 0; font-size: 32px; font-weight: 700; }
            input { width: 100%; padding: 18px 22px; margin-bottom: 15px; border-radius: 20px; border: 1px solid rgba(255,255,255,0.1); background: rgba(255,255,255,0.05); color: white; outline: none; }
            button { width: 100%; padding: 18px; border-radius: 20px; border: none; background: var(--accent); color: white; font-weight: 700; cursor: pointer; }

            .terminal {
                width: 550px; height: 450px; background: #0c0e14; border-radius: 20px; overflow: hidden;
                box-shadow: 0 30px 80px rgba(0,0,0,0.8); display: none; opacity: 0; transform: scale(0.95) translateX(30px);
                border: 1px solid rgba(255,255,255,0.05);
            }
            .term-header { background: #161922; padding: 12px 20px; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid rgba(255,255,255,0.05); }
            .dot { width: 12px; height: 12px; border-radius: 50%; }
            .red { background: #ff5f56; } .yellow { background: #ffbd2e; } .green { background: #27c93f; }
            .term-body { padding: 20px; font-family: 'Fira Code', monospace; font-size: 13px; color: #a6accd; height: 390px; overflow-y: auto; }
            .term-input { background: none; border: none; color: #4ade80; outline: none; width: 100%; font-family: inherit; }
            body.logged-in .card, body.logged-in .known-box { transform: translateX(-20px); width: 340px; }
            body.logged-in .terminal { display: block; opacity: 1; transform: scale(1) translateX(0); }
            body.logged-in .avatar-wrap { transform: scale(0.8); }

            .btn-link { display: flex; align-items: center; justify-content: center; gap: 10px; margin-top: 20px; color: rgba(255,255,255,0.4); text-decoration: none; font-size: 13px; }
            .btn-link:hover { color: #fff; }
        </style>
    </head>
    <body id="bd">
        <div class="main-container">
            <div id="auth-ui">
                <div class="card" id="main-card">
                    <div class="avatar-wrap">
                        <img class="avatar" src="https://minotar.net/helm/%USER%/110.png">
                        %BADGE_2FA%
                    </div>
                    <h2>%USER%</h2>

                    <div class="ds-card" style="display: %DS_VIS%">
                        <img src="%DS_AVATAR%" class="ds-avatar">
                        <div class="ds-info">
                            <span>Привязанный Discord</span>
                            <b>%DS_NAME%</b>
                        </div>
                    </div>

                    <div id="input-area">
                        <input type="password" id="p" placeholder="Твой пароль">
                        <button onclick="send()">АВТОРИЗОВАТЬСЯ</button>
                    </div>

                    <div id="logged-area" style="display: none;">
                        <button onclick="autoLogin()">ВОЙТИ В СИСТЕМУ</button>
                        <p onclick="logout()" style="cursor:pointer; font-size: 12px; margin-top: 15px; opacity: 0.4;">Сменить аккаунт</p>
                    </div>

                    <div id="twofa-area" style="display: none;">
                        <input type="text" id="code" placeholder="Код из Discord">
                        <button onclick="verify2FA()">ПОДТВЕРДИТЬ</button>
                    </div>

                    <a href="%DS_LINK%" class="btn-link" style="display: none;">
                                    <img src="https://cdn-icons-png.flaticon.com/512/2111/2111370.png" width="16">
                                    Привязать профиль
                                </a>
                </div>
            </div>

            <div class="terminal">
                <div class="term-header">
                    <div class="dot red"></div><div class="dot yellow"></div><div class="dot green"></div>
                    <span style="font-size: 11px; opacity: 0.4; margin-left: 10px;">macOS Terminal — root@%USER%</span>
                </div>
                <div class="term-body" id="t-body">
                    <div style="color: var(--accent)">[SYSTEM] Session initialized for user: %USER%</div>
                    <div style="color: #6272a4">Type 'help' to see available commands.</div>
                    <div id="t-logs"></div>
                    <div style="display:flex; gap:8px; margin-top:10px;">
                        <span style="color: var(--accent)">$</span>
                        <input type="text" id="t-in" class="term-input" autofocus>
                    </div>
                </div>
            </div>
        </div>

        <script>
            const user = '%USER%';
            const storageKey = 'regis_auth_' + user;
            const bd = document.getElementById('bd');

            if (localStorage.getItem(storageKey)) {
                            document.getElementById('input-area').style.display = 'none';
                            document.getElementById('logged-area').style.display = 'block';
                           
                            setTimeout(() => {
                                const discordBtn = document.querySelector('.btn-link[href*="discord"]');
                                
                                const dsCard = document.querySelector('.ds-card');
                                if (discordBtn && (!dsCard || dsCard.style.display === 'none')) {
                                    discordBtn.style.display = 'flex';
                                }
                            }, 500);
                        }

            function cooldown(btn) {
                btn.disabled = true;
                const oldText = btn.innerText;
                let sec = 10;
                const timer = setInterval(() => {
                    btn.innerText = `ПОДОЖДИТЕ (${sec}с)`;
                    sec--;
                    if (sec < 0) {
                        clearInterval(timer);
                        btn.disabled = false;
                        btn.innerText = oldText;
                    }
                }, 1000);
            }

            function autoLogin() {
                                              const btn = event.target;
                                              const savedPass = localStorage.getItem(storageKey);
                
                                              if (!savedPass) {
                                                  logout();
                                                  return;
                                              }
                
                                             
                                              if (savedPass === '2fa_verified') {
                                                  document.getElementById('logged-area').style.display = 'none';
                                                  document.getElementById('input-area').style.display = 'block';
                                                  document.getElementById('twofa-area').style.display = 'none';
                                                  localStorage.removeItem(storageKey);
                                                  return;
                                              }
                
                                              cooldown(btn);
                                              submitAuth(savedPass, true);
                                          }

            function send(){
                const btn = event.target;
                const v = document.getElementById('p').value;
                if(!v) return alert('Введите пароль!');
                cooldown(btn);
                submitAuth(v);
            }

            function submitAuth(pass, isAuto = false) {
                            fetch('/api/submit', {
                                method:'POST',
                                body:'user='+user+'&password='+encodeURIComponent(pass)
                            })
                            .then(r=>r.text()).then(d=>{
                                if(d === 'OK') {
                                    localStorage.setItem(storageKey, pass);
                                    bd.classList.add('logged-in');
                                    document.getElementById('input-area').style.display = 'none';
                                    document.getElementById('logged-area').style.display = 'block';
                                    
                                    const discordBtn = document.querySelector('.btn-link[href*="discord"]');
                                    if (discordBtn && discordBtn.style.display !== 'flex') {
                                        discordBtn.style.display = 'flex';
                                    }
                                    logT('Авторизация выполнена успешно. Добро пожаловать.');
                                } else if (d === 'DISCORD_LINK_READY') {
                                    localStorage.setItem(storageKey, pass);
                                    bd.classList.add('logged-in');
                                    document.getElementById('input-area').style.display = 'none';
                                    document.getElementById('logged-area').style.display = 'block';
                                    
                                    const discordBtn = document.querySelector('.btn-link[href*="discord"]');
                                    if (discordBtn) {
                                        discordBtn.style.display = 'flex';
                                    }
                                    logT('Привяжите Discord для дополнительной защиты.');
                                } else if (d === 'ALREADY_AUTH') {
                                    localStorage.setItem(storageKey, pass);
                                    bd.classList.add('logged-in');
                                    document.getElementById('input-area').style.display = 'none';
                                    document.getElementById('logged-area').style.display = 'block';
                                   
                                    const discordBtn = document.querySelector('.btn-link[href*="discord"]');
                                    if (discordBtn && discordBtn.style.display !== 'flex') {
                                        discordBtn.style.display = 'flex';
                                    }
                                    logT('Вы уже авторизованы в игре. Сессия обновлена.');
                                } else if (d === '2FA_REQUIRED') {
                                    localStorage.removeItem(storageKey);
                                    document.getElementById('input-area').style.display = 'none';
                                    document.getElementById('twofa-area').style.display = 'block';
                                    logT('Требуется двухфакторная аутентификация. Введите код из Discord.');
                                } else {
                                    alert('Неверный пароль!');
                                    if(isAuto) logout();
                                }
                            });
                        }

            function verify2FA() {
                             const code = document.getElementById('code').value;
                             if(!code) return alert('Введите код!');
                             fetch('/api/verify2fa', {
                                 method:'POST',\s
                                 body:'user='+user+'&code='+encodeURIComponent(code)
                             })
                             .then(r=>r.text()).then(d=>{
                                 if(d === 'OK') {
                                     localStorage.setItem(storageKey, '2fa_verified');
                                     bd.classList.add('logged-in');
                                     document.getElementById('twofa-area').style.display = 'none';
                                     document.getElementById('logged-area').style.display = 'block';
                                     logT('2FA подтверждена. Добро пожаловать.');
                                 } else {
                                     alert('Неверный код или время истекло.');
                                 }
                             });
                         }

            const tIn = document.getElementById('t-in');
            tIn.addEventListener('keypress', (e) => {
                if(e.key === 'Enter') {
                    const val = tIn.value.split(' ');
                    const cmd = val[0];
                    const arg = val[1] || '';
                    logT('> ' + tIn.value, '#fff');
                    
                    if(cmd === 'help') logT('Команды: kick, ban, unban, reset [пароль], ips, clear');
                    else if(cmd === 'clear') document.getElementById('t-logs').innerHTML = '';
                    else {
                        fetch('/api/command', {method:'POST', body:`user=${user}&cmd=${cmd}&val=${arg}`})
                        .then(r=>r.text()).then(res => logT('SERVER: ' + res));
                    }
                    tIn.value = '';
                }
            });

            function logT(m, c) {
                const l = document.createElement('div');
                l.style.color = c || '#4ade80';
                l.innerHTML = `[${new Date().toLocaleTimeString()}] ${m}`;
                document.getElementById('t-logs').appendChild(l);
                document.getElementById('t-body').scrollTop = 9999;
            }

            function logout() { localStorage.removeItem(storageKey); location.reload(); }
        </script>
    </body>
    </html>
    """.replace("%ACCENT%", accent)
                .replace("%USER%", user)
                .replace("%TITLE%", "REgiS | Dashboard")
                .replace("%DS_LINK%", dsLink)
                .replace("%DS_NAME%", dsName)
                .replace("%DS_AVATAR%", dsAvatar)
                .replace("%DS_VIS%", isLinked ? "flex" : "none")
                .replace("%DS_BTN_VIS%", !isLinked && userExists ? "flex" : "none")
                .replace("%BADGE_2FA%", twofaEnabled ? "<div class=\"badge-2fa\"><i class=\"fa-solid fa-circle-check\" style=\"color: #2ecc71;\"></i></div>" : "");
    }

    private String getSuccessHtml(String mcUser, String dsName, String returnUrl) {
        String accent = getConfig().getString("site.accent-color", "#7289da");
        return """
    <!DOCTYPE html>
    <html lang="ru">
    <head>
        <meta charset="UTF-8">
        <title>Успешная привязка</title>
        <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@400;700&display=swap" rel="stylesheet">
        <style>
            :root { --accent: %ACCENT%; }
            body { 
                margin: 0; height: 100vh; display: flex; align-items: center; justify-content: center; 
                background: #0f172a url('/background.jpg') center/cover; 
                font-family: 'Outfit', sans-serif; color: white; overflow: hidden;
            }
            body::after { content: ''; position: absolute; inset: 0; background: rgba(15, 23, 42, 0.9); z-index: 1; }
            
            .container { 
                position: relative; z-index: 10; text-align: center; 
                background: rgba(255, 255, 255, 0.03); backdrop-filter: blur(20px);
                padding: 60px; border-radius: 40px; border: 1px solid rgba(255, 255, 255, 0.1);
                box-shadow: 0 25px 50px rgba(0,0,0,0.5); max-width: 500px;
                animation: slideUp 0.6s ease-out;
            }
            
            @keyframes slideUp { from { opacity: 0; transform: translateY(30px); } to { opacity: 1; transform: translateY(0); } }

            .icon-circle {
                width: 80px; height: 80px; background: var(--accent); border-radius: 50%;
                display: flex; align-items: center; justify-content: center; margin: 0 auto 30px;
                box-shadow: 0 0 30px var(--accent);
            }
            
            h1 { margin: 0 0 10px; font-size: 28px; }
            p { opacity: 0.6; font-size: 16px; margin-bottom: 30px; line-height: 1.5; }
            
            .btn-return {
                display: inline-block; padding: 18px 40px; background: white; color: #0f172a;
                text-decoration: none; border-radius: 20px; font-weight: 700; font-size: 15px;
                transition: 0.3s; margin-bottom: 15px;
            }
            .btn-return:hover { transform: scale(1.05); box-shadow: 0 10px 20px rgba(0,0,0,0.2); }
            
            .user-info {
                background: rgba(255,255,255,0.05); padding: 15px; border-radius: 15px;
                margin-bottom: 20px; border: 1px solid rgba(255,255,255,0.05);
            }
            
            .game-notification {
                background: rgba(88, 101, 242, 0.15); 
                border: 1px solid rgba(88, 101, 242, 0.3);
                padding: 12px; border-radius: 12px; font-size: 14px;
                margin-bottom: 25px; color: #a6accd;
            }
            
            .game-notification i { color: #5865F2; margin-right: 8px; }
        </style>
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css">
    </head>
    <body>
        <div class="container">
            <div class="icon-circle">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>
            </div>
            <h1>Связь установлена!</h1>
            
            <div class="user-info">
                <span style="opacity: 0.5; font-size: 12px; display: block; margin-bottom: 5px;">АККАУНТЫ СИНХРОНИЗИРОВАНЫ</span>
                <span style="font-weight: 700;">%MC_USER%</span> 
                <span style="color: var(--accent); margin: 0 10px;">&harr;</span> 
                <span style="font-weight: 700;">@%DS_NAME%</span>
            </div>
            
            <div class="game-notification">
                <i class="fa-solid fa-bell"></i>
                <span>В игре появилось уведомление об успешной привязке!</span>
            </div>
            
            <p>Ваша учетная запись Discord успешно привязана к игровому профилю.<br>Теперь вы можете использовать двухфакторную аутентификацию.</p>
            
            <a href="%RETURN_URL%" class="btn-return">ВЕРНУТЬСЯ В ПРОФИЛЬ</a>
        </div>
    </body>
    </html>
    """
                .replace("%ACCENT%", accent)
                .replace("%MC_USER%", mcUser)
                .replace("%DS_NAME%", dsName)
                .replace("%RETURN_URL%", returnUrl);
    }
}