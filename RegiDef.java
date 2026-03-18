package zxc.zxc.rEgiS;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import java.util.List;

public class RegiDef implements Listener {

    private final REgiS plugin;
    private final List<String> allowedCommands;

    public RegiDef(REgiS plugin) {
        this.plugin = plugin;
        this.allowedCommands = plugin.getConfig().getStringList("onregister.commandallowed");
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getConfig().getBoolean("onregister.enabled", false)) {
            return;
        }

        if (plugin.getConfig().getBoolean("onregister.commandsallallowed", false)) {
            return;
        }

        if (!plugin.isPlayerLocked(player.getName())) {
            return;
        }

        String command = event.getMessage().toLowerCase();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        String baseCommand = command.split(" ")[0];

        for (String allowed : allowedCommands) {
            String allowedLower = allowed.toLowerCase().replace("/", "");
            if (baseCommand.equals(allowedLower)) {
                return;
            }
        }

        event.setCancelled(true);
        player.sendMessage(plugin.t("messages.not_authenticated"));
    }
}