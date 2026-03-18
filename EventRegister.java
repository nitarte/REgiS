package zxc.zxc.rEgiS;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mindrot.jbcrypt.BCrypt;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class EventRegister {

    private final REgiS plugin;
    private final Map<String, Long> ipRegistrations = new HashMap<>();
    public EventRegister(REgiS plugin) {
        this.plugin = plugin;
    }
    public boolean resetPassword(CommandSender sender, String target, String newPassword) {
        if (newPassword.length() < 4) {
            sender.sendMessage("§c❌ Пароль должен быть минимум 4 символа!");
            return false;
        }

        String hashedPass = BCrypt.hashpw(newPassword, BCrypt.gensalt());

        try (PreparedStatement ps = plugin.getConnection().prepareStatement("UPDATE users SET password = ? WHERE username = ?")) {
            ps.setString(1, hashedPass);
            ps.setString(2, target);

            int updated = ps.executeUpdate();
            if (updated > 0) {
                sender.sendMessage("§a✅ Пароль игрока " + target + " успешно изменен!");
                return true;
            } else {
                sender.sendMessage("§c❌ Игрок " + target + " не найден в базе!");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            sender.sendMessage("§c❌ Ошибка базы данных!");
        }
        return false;
    }
    public boolean forceRegister(CommandSender sender, String target, String password) {
        try (PreparedStatement check = plugin.getConnection().prepareStatement("SELECT username FROM users WHERE username = ?")) {
            check.setString(1, target);
            ResultSet rs = check.executeQuery();
            if (rs.next()) {
                sender.sendMessage("§c❌ Игрок " + target + " уже зарегистрирован!");
                return false;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        String hashedPass = BCrypt.hashpw(password, BCrypt.gensalt());
        String ip = "0.0.0.0";

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
                "INSERT INTO users (username, password, ip, last_seen, twofa_enabled) VALUES (?, ?, ?, ?, 0)")) {
            ps.setString(1, target);
            ps.setString(2, hashedPass);
            ps.setString(3, ip);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            sender.sendMessage("§a✅ Игрок " + target + " успешно зарегистрирован!");
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            sender.sendMessage("§c❌ Ошибка базы данных!");
        }
        return false;
    }

    public boolean unregister(CommandSender sender, String target, String confirmPassword) {
        if (!confirmPassword.equals("confirm") && !confirmPassword.equals("yes") && !confirmPassword.equals("да")) {
            sender.sendMessage("§c❌ Для подтверждения введите: /regis unregister " + target + " confirm");
            sender.sendMessage("§7Это действие необратимо!");
            return false;
        }

        try (PreparedStatement ps = plugin.getConnection().prepareStatement("DELETE FROM users WHERE username = ?")) {
            ps.setString(1, target);
            int deleted = ps.executeUpdate();

            if (deleted > 0) {
                sender.sendMessage("§a✅ Регистрация игрока §f" + target + " §aудалена!");
                Player p = Bukkit.getPlayer(target);
                if (p != null && p.isOnline()) {
                    p.kickPlayer("§cВаш аккаунт был удален администратором!");
                }
                return true;
            } else {
                sender.sendMessage("§c❌ Игрок §f" + target + " §cне найден в базе!");
                return false;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            sender.sendMessage("§c❌ Ошибка базы данных!");
            return false;
        }
    }

    public boolean setMaxIp(CommandSender sender, int limit) {
        if (limit < 1 || limit > 100) {
            sender.sendMessage("§c❌ Лимит должен быть от 1 до 100!");
            return false;
        }
        plugin.getConfig().set("security.max-accounts-per-ip", limit);
        plugin.saveConfig();
        plugin.reloadConfig();

        sender.sendMessage("§a✅ Максимальное количество аккаунтов с одного IP установлено: §f" + limit);
        return true;
    }

    public boolean reloadConfig(CommandSender sender) {
        plugin.reloadConfig();
        plugin.saveDefaultConfig();
        sender.sendMessage("§a✅ Конфигурация перезагружена!");
        return true;
    }

    public void sendHelp(CommandSender sender, int page) {
        boolean isAdmin = sender.hasPermission("regis.admin");

        sender.sendMessage("§8┌─── §bREgiS §7Помощь §8[§f" + page + "§8/§f3§8] ───┐");

        if (page == 1) {
            sender.sendMessage("§7");
            sender.sendMessage("§7");
            sender.sendMessage("§7");
            sender.sendMessage(" §a/reg §8- §b§lссылка на регистрацию");
            sender.sendMessage(" §a/login [пароль] §8- §a§lвход");
            sender.sendMessage(" §a/register [пароль] §8- §e§lрегистрация");
            sender.sendMessage("");
            sender.sendMessage("§7Зайди по ссылке из /reg и введи пароль там");
            sender.sendMessage("§7");
            sender.sendMessage("§7");
            sender.sendMessage("§7");
        }
        else if (page == 2) {
            if (!isAdmin) {
                sender.sendMessage(" §cУ тебя нет прав на эту страницу");
            } else {
                sender.sendMessage("§7");
                sender.sendMessage("§7");
                sender.sendMessage("§7");
                sender.sendMessage(" §e§l/regis setspawn §c[игрок] §e- §aсоздать");
                sender.sendMessage(" §e§l/regis removespawn §c[игрок] §8- §aудалить");
                sender.sendMessage(" §e§l/regis togglespawn §c[игрок] §8- §aвкл/§cвыкл");
                sender.sendMessage(" §e§l/regis spawn §c[игрок] §8- §aтелепорт");
                sender.sendMessage(" §e§l/regis listspawns §8- §fсписок");
                sender.sendMessage("§7");
                sender.sendMessage("§7");
                sender.sendMessage("§7");
            }
        }
        else if (page == 3) {
            if (!isAdmin) {
                sender.sendMessage(" §cУ тебя нет прав на эту страницу");
            } else {
                sender.sendMessage("§7");
                sender.sendMessage("§7");
                sender.sendMessage("§7");
                sender.sendMessage(" §a§l/regis resetpassword §c[игрок] [пароль] §a- §fсмена пароля");
                sender.sendMessage(" §a§l/regis register §c[игрок] [пароль] §a- §fпринуд. регистрация");
                sender.sendMessage(" §a§l/regis unregister §c[игрок] confirm §a- §fудалить аккаунт");
                sender.sendMessage(" §a§l/regis maxip §c[число] §a- §fлимит аккаунтов с IP");
                sender.sendMessage(" §a§l/regis reload §e§l- §fперезагрузить конфиг");
                sender.sendMessage("§7");
                sender.sendMessage("§7");
                sender.sendMessage("§7");
            }
        }

        sender.sendMessage("§8└─────────────┘");

        if (sender instanceof Player) {
            Player p = (Player) sender;
            TextComponent line = new TextComponent("");

            TextComponent prev = new TextComponent(page > 1 ? "§a« Назад" : "§7« Назад");
            if (page > 1) {
                prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/regis help " + (page-1)));
                prev.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text("§7Предыдущая страница")));
            }

            TextComponent next = new TextComponent(page < 3 ? "Вперёд »§a" : "Вперёд »§7");
            if (page < 3) {
                next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/regis help " + (page+1)));
                next.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text("§7Следующая страница")));
            }

            line.addExtra("          ");
            line.addExtra(prev);
            line.addExtra(" §8• §r");
            line.addExtra(next);

            p.spigot().sendMessage(line);
        }
    }
}