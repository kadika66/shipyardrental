package io.github.shipyard.rental.manager;

import io.github.shipyard.rental.ShipyardRentalPlugin;
import io.github.shipyard.rental.model.Plot;
import io.github.shipyard.rental.model.Rental;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * At most one active {@link Rental} per plot id at a time.
 */
public class RentalManager {

    private final ShipyardRentalPlugin plugin;
    private final File file;
    private final Map<String, Rental> rentalsByPlot = new LinkedHashMap<>();

    public RentalManager(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rentals.yml");
        load();
    }

    public Optional<Rental> getRental(String plotId) {
        return Optional.ofNullable(rentalsByPlot.get(plotId.toLowerCase()));
    }

    public Optional<Rental> getRentalFor(UUID player) {
        return rentalsByPlot.values().stream()
                .filter(r -> r.getRenter().equals(player))
                .findFirst();
    }

    /** Finds the rental (if any) this player is currently authorized to build in - as renter or trusted helper. */
    public Optional<Rental> getAuthorizedRentalFor(UUID player) {
        return rentalsByPlot.values().stream()
                .filter(r -> r.isAuthorized(player))
                .findFirst();
    }

    public long countActiveRentalsFor(UUID player) {
        return rentalsByPlot.values().stream()
                .filter(r -> r.getRenter().equals(player) && !r.isExpired())
                .count();
    }

    /**
     * How many plots this player is allowed to rent at once: "Max Rentals Per Player"
     * in config.yml, unless the player holds a "shipyardrental.rent.&lt;N&gt;" permission
     * (e.g. shipyardrental.rent.4), in which case the HIGHEST such N they hold wins
     * instead of stacking on top of the config default. Everyone still needs the base
     * "shipyardrental.rent" permission (default true) just to use /shipyard rent at all -
     * these numbered ones only raise the cap for whoever's been granted one.
     */
    public int getMaxRentals(org.bukkit.entity.Player player) {
        int max = plugin.getConfig().getInt("Max Rentals Per Player", 1);
        String prefix = "shipyardrental.rent.";
        for (org.bukkit.permissions.PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String perm = info.getPermission();
            if (perm.length() > prefix.length() && perm.regionMatches(true, 0, prefix, 0, prefix.length())) {
                try {
                    int n = Integer.parseInt(perm.substring(prefix.length()));
                    max = Math.max(max, n);
                } catch (NumberFormatException ignored) {
                    // Not a numbered tier (e.g. some other permission happens to start
                    // with the same prefix) - just skip it.
                }
            }
        }
        return max;
    }

    public Rental startOrExtend(Plot plot, UUID renter, int days) {
        String key = plot.getId();
        long millis = TimeUnit.DAYS.toMillis(days);
        Rental rental = rentalsByPlot.get(key);
        if (rental == null || rental.isExpired() || !rental.getRenter().equals(renter)) {
            // Fresh rental (nobody renting it, it lapsed, or it's changing hands): wipe
            // out whatever the previous renter set up, including trusted helpers.
            rental = new Rental(key, renter, System.currentTimeMillis() + millis);
            rentalsByPlot.put(key, rental);
            plugin.getPlotManager().setRegionOwner(plot, renter);
        } else {
            // Same renter extending their own still-active rental: keep trusted helpers as-is.
            rental.extend(millis);
        }
        // Cheap and idempotent - also self-heals plots defined before "Plot Region
        // Priority" existed or was changed, without needing to redefine the region.
        plugin.getPlotManager().applyPriority(plot);
        plugin.getPlotManager().clearExplicitBuildFlag(plot);
        save();
        return rental;
    }

    public void end(Plot plot) {
        rentalsByPlot.remove(plot.getId());
        plugin.getPlotManager().clearRegionAccess(plot);
        save();
    }

    public boolean trust(Rental rental, Plot plot, UUID helper) {
        boolean added = rental.trust(helper);
        if (added) {
            plugin.getPlotManager().addRegionMember(plot, helper);
            save();
        }
        return added;
    }

    public boolean untrust(Rental rental, Plot plot, UUID helper) {
        boolean removed = rental.untrust(helper);
        if (removed) {
            plugin.getPlotManager().removeRegionMember(plot, helper);
            save();
        }
        return removed;
    }

    public Map<String, Rental> all() {
        return rentalsByPlot;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("rentals");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            Rental rental = new Rental(key, UUID.fromString(s.getString("renter")), s.getLong("expires"));
            List<String> trustedList = s.getStringList("trusted");
            for (String t : trustedList) {
                try {
                    rental.trust(UUID.fromString(t));
                } catch (IllegalArgumentException ignored) {
                }
            }
            rentalsByPlot.put(key, rental);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Rental rental : rentalsByPlot.values()) {
            String base = "rentals." + rental.getPlotId() + ".";
            yaml.set(base + "renter", rental.getRenter().toString());
            yaml.set(base + "expires", rental.getExpiresAtMillis());
            List<String> trustedList = rental.getTrusted().stream().map(UUID::toString).toList();
            yaml.set(base + "trusted", trustedList);
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save rentals.yml: " + e.getMessage());
        }
    }
}
