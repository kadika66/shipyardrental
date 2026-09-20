package io.github.shipyard.rental.listener;

import io.github.shipyard.rental.ShipyardRentalPlugin;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closes off the ways a player could smuggle a "free" creative-mode item out
 * into their real, survival-mode inventory once their build session ends.
 * The inventory swap (see InventorySnapshotManager) only protects the
 * player's own inventory container - anything that lets an item leave that
 * container into something that ISN'T wiped by the swap is a duplication
 * vector. That mainly means:
 *
 * <ul>
 *   <li>Ender chests - vanilla, per-player storage that exists independently
 *       of any block or location, so it survives the plot being reset.</li>
 *   <li>Third-party "virtual storage" plugins (player vaults, banks, kits,
 *       trade menus, etc.) reachable via their own commands - these can't be
 *       enumerated in advance, so instead of a blocklist we run an allowlist:
 *       everything except a short list of known-safe commands is blocked
 *       outright while a build session is active.</li>
 *   <li>Simply dropping the item on the ground for an accomplice standing
 *       just outside the plot boundary to walk over and pick up - WorldGuard's
 *       BUILD flag doesn't govern item-entity pickup, so this needs its own
 *       block.</li>
 * </ul>
 */
public class BuildModeGuardListener implements Listener {

    private final ShipyardRentalPlugin plugin;

    public BuildModeGuardListener(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!inBuildSession(player)) {
            return;
        }
        String base = event.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        // Strip a plugin-name prefix like "/pv:vault" -> "pv" so the check can't be dodged that way.
        int colon = base.indexOf(':');
        if (colon >= 0) {
            base = base.substring(colon + 1);
        }
        if (allowedCommands().contains(base)) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&c[Shipyard] &7That command is disabled while you're in build mode. Leave the plot first."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) {
            return;
        }
        if (!inBuildSession(player)) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&c[Shipyard] &7Ender chests are disabled while you're in build mode."));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getConfig().getBoolean("Block Item Drop In Build Mode", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (!inBuildSession(player)) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&c[Shipyard] &7Dropping items is disabled while you're in build mode."));
    }

    private boolean inBuildSession(Player player) {
        if (player.hasPermission("shipyardrental.admin.bypass")) {
            return false;
        }
        return plugin.getSnapshotManager().hasSnapshot(player.getUniqueId());
    }

    private Set<String> allowedCommands() {
        List<String> configured = plugin.getConfig().getStringList("Build Mode Allowed Commands");
        Set<String> set = new HashSet<>();
        for (String s : configured) {
            set.add(s.toLowerCase(Locale.ROOT));
        }
        return set;
    }
}
