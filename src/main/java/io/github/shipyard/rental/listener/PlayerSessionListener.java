package io.github.shipyard.rental.listener;

import io.github.shipyard.rental.ShipyardRentalPlugin;
import io.github.shipyard.rental.model.Plot;
import io.github.shipyard.rental.model.Rental;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;
import java.util.Optional;

/**
 * Watches players crossing in/out of shipyard plot boundaries and swaps their
 * inventory + GameMode accordingly, for the plot's renter AND anyone they've
 * trusted. Whether a given player is "inside" a build session is tracked by
 * whether they have an inventory snapshot on disk, not by any in-memory flag -
 * that way any number of people (renter + trusted helpers) can be inside their
 * own independent build sessions in the same plot at once.
 *
 * Both normal walking (PlayerMoveEvent) AND teleports (PlayerTeleportEvent) are
 * watched: an ender pearl, chorus fruit, or /tpa/warp out of the plot doesn't
 * fire a normal walked-across-the-border move, so without also hooking
 * teleports a player could teleport out of the plot and be left wandering the
 * server still in creative with their real inventory stashed on disk.
 */
public class PlayerSessionListener implements Listener {

    private final ShipyardRentalPlugin plugin;

    public PlayerSessionListener(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        // Only bother once they've actually crossed a block boundary.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        handleTransition(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            return;
        }
        handleTransition(event.getPlayer(), event.getFrom(), event.getTo());
    }

    private void handleTransition(Player player, Location from, Location to) {
        if (player.hasPermission("shipyardrental.admin.bypass")) {
            return;
        }

        Optional<Plot> fromPlot = plugin.getPlotManager().findPlotAt(from.getWorld(),
                from.getBlockX(), from.getBlockY(), from.getBlockZ());
        Optional<Plot> toPlot = plugin.getPlotManager().findPlotAt(to.getWorld(),
                to.getBlockX(), to.getBlockY(), to.getBlockZ());

        String fromId = fromPlot.map(Plot::getId).orElse(null);
        String toId = toPlot.map(Plot::getId).orElse(null);
        if (Objects.equals(fromId, toId)) {
            return;
        }

        if (fromPlot.isPresent()) {
            handleExit(player, fromPlot.get());
        }
        if (toPlot.isPresent()) {
            handleEnter(player, toPlot.get());
        }
    }

    private void handleEnter(Player player, Plot plot) {
        Optional<Rental> rentalOpt = plugin.getRentalManager().getRental(plot.getId());
        if (rentalOpt.isEmpty()) {
            return;
        }
        Rental rental = rentalOpt.get();
        if (rental.isExpired() || !rental.isAuthorized(player.getUniqueId())) {
            return;
        }
        if (plugin.getSnapshotManager().hasSnapshot(player.getUniqueId())) {
            return; // already in a build session (e.g. re-entered after a border wobble)
        }

        plugin.getSnapshotManager().enterBuildMode(player, resolveBuildMode());
        boolean isRenter = rental.getRenter().equals(player.getUniqueId());
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                isRenter
                        ? "&d[Shipyard] &7Build mode on. Your normal inventory is safely stashed until you leave."
                        : "&d[Shipyard] &7Build mode on - you're helping build here. Your normal inventory is stashed until you leave."));
    }

    private void handleExit(Player player, Plot plot) {
        Optional<Rental> rentalOpt = plugin.getRentalManager().getRental(plot.getId());
        if (rentalOpt.isEmpty()) {
            return;
        }
        Rental rental = rentalOpt.get();
        if (!rental.isAuthorized(player.getUniqueId())) {
            return;
        }

        boolean restored = plugin.getSnapshotManager().exitBuildMode(player);
        if (restored) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&d[Shipyard] &7Build mode off. Welcome back to survival."));
        }
    }

    private GameMode resolveBuildMode() {
        try {
            return GameMode.valueOf(plugin.getConfig().getString("Build GameMode", "CREATIVE").toUpperCase());
        } catch (IllegalArgumentException e) {
            return GameMode.CREATIVE;
        }
    }

    /**
     * Safety net for a server restart (or any disconnect) that happens while a player
     * is mid-build-session. Two genuinely different situations look identical from just
     * "does this player have a leftover snapshot on disk", and need opposite handling:
     *
     * <ul>
     *   <li>They're still standing inside a plot they're actually authorized to build
     *       in - completely normal, they just reconnected mid-session. No boundary-
     *       crossing move/teleport event will ever fire for this (they never left and
     *       came back, they were just offline), so without this check they'd silently
     *       stay on their real survival inventory while standing in build territory
     *       until they physically walked out and back in to re-trigger handleEnter.
     *   <li>Anything else - the rental expired while they were offline, the plot got
     *       reset/reassigned, some other plugin moved them - genuinely stuck/orphaned,
     *       and restoring their real inventory is the safe, correct fallback.
     * </ul>
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("Restore On Rejoin If Stuck Inside", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.getSnapshotManager().hasSnapshot(player.getUniqueId())) {
            return;
        }

        Location loc = player.getLocation();
        Optional<Plot> plotHere = plugin.getPlotManager().findPlotAt(loc.getWorld(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (plotHere.isPresent()) {
            Optional<Rental> rentalOpt = plugin.getRentalManager().getRental(plotHere.get().getId());
            if (rentalOpt.isPresent() && !rentalOpt.get().isExpired()
                    && rentalOpt.get().isAuthorized(player.getUniqueId())) {
                // Still legitimately inside their own session - leave the stashed
                // inventory alone, just re-assert GameMode in case the restart reset it.
                GameMode buildMode = resolveBuildMode();
                if (player.getGameMode() != buildMode) {
                    player.setGameMode(buildMode);
                }
                return;
            }
        }

        plugin.getSnapshotManager().exitBuildMode(player);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&e[Shipyard] &7You reconnected mid build-session, so your original inventory was restored."));
    }
}
