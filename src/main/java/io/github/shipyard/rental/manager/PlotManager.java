package io.github.shipyard.rental.manager;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.github.shipyard.rental.ShipyardRentalPlugin;
import io.github.shipyard.rental.model.Plot;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the list of defined shipyard plots and keeps their backing WorldGuard
 * regions in sync (created on define, removed on remove/reset-delete).
 */
public class PlotManager {

    private final ShipyardRentalPlugin plugin;
    private final File file;
    private final Map<String, Plot> plots = new LinkedHashMap<>();

    public PlotManager(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "plots.yml");
        load();
    }

    public Optional<Plot> get(String id) {
        return Optional.ofNullable(plots.get(id.toLowerCase()));
    }

    public Map<String, Plot> all() {
        return plots;
    }

    /**
     * Defines a new plot from a raw cuboid (already resolved from the admin's
     * WorldEdit selection by the command handler) and registers a matching
     * WorldGuard region so only the renter can build inside it.
     */
    public Plot define(String id, World world, BlockVector3 min, BlockVector3 max, double pricePerDay) {
        String key = id.toLowerCase();
        Plot plot = new Plot(key, world.getName(),
                min.x(), min.y(), min.z(),
                max.x(), max.y(), max.z(), pricePerDay);

        RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regionManager != null) {
            ProtectedCuboidRegion region = new ProtectedCuboidRegion(plot.getRegionId(), min, max);
            // Deliberately NOT setting build:DENY explicitly. A freshly created WorldGuard
            // region already denies build to non-owners/non-members by default, with zero
            // flags set - that's the built-in protection every new region gets, and it comes
            // with an implicit exemption for the region's own owners/members. Explicitly
            // setting build:DENY overrides that exemption and applies to EVERYONE, including
            // the owner, unless it's scoped to a region group - confirmed the hard way via
            // live testing: a real renter, correctly listed as owner, was still denied until
            // this explicit flag was removed. So: leave it unset, let the default do its job.
            region.setPriority(plugin.getConfig().getInt("Plot Region Priority", 10));
            regionManager.addRegion(region);
        }

        plots.put(key, plot);
        save();
        return plot;
    }

    public boolean remove(String id) {
        String key = id.toLowerCase();
        Plot plot = plots.remove(key);
        if (plot == null) {
            return false;
        }
        World world = plugin.getServer().getWorld(plot.getWorld());
        if (world != null) {
            RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(world));
            if (regionManager != null) {
                regionManager.removeRegion(plot.getRegionId());
            }
        }
        save();
        return true;
    }

    /**
     * Sets the sole renter/owner of the plot's region, clearing any previous
     * owner AND any previously trusted members - used when a fresh rental
     * starts (not on every /shipyard extend, which keeps trusted helpers).
     */
    public void setRegionOwner(Plot plot, java.util.UUID uuid) {
        World world = plugin.getServer().getWorld(plot.getWorld());
        if (world == null) {
            return;
        }
        RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regionManager == null) {
            return;
        }
        ProtectedRegion region = regionManager.getRegion(plot.getRegionId());
        if (region == null) {
            return;
        }
        region.getOwners().clear();
        region.getMembers().clear();
        if (uuid != null) {
            region.getOwners().addPlayer(uuid);
            // No explicit build flag here either, same reasoning as in define() - leave the
            // region's default protection alone so the owner exemption actually applies.
        }
    }

    /** Clears owners and members entirely - used when a rental ends/expires/resets. */
    public void clearRegionAccess(Plot plot) {
        setRegionOwner(plot, null);
    }

    /**
     * Re-applies the configured priority to an already-defined plot's region.
     * Needed for plots created before "Plot Region Priority" existed/changed,
     * or defined while the config had a different value - lets you fix an
     * existing plot without deleting and redefining it. Called automatically
     * on every rent/extend, and admins can force it for every plot via
     * /shipyard reload.
     */
    public void applyPriority(Plot plot) {
        RegionManager regionManager = regionManagerFor(plot);
        if (regionManager == null) return;
        ProtectedRegion region = regionManager.getRegion(plot.getRegionId());
        if (region == null) return;
        region.setPriority(plugin.getConfig().getInt("Plot Region Priority", 10));
    }

    /**
     * Clears any explicit build flag on the region, self-healing plots that were
     * defined before this was fixed (see define()) - an explicit build:DENY blocks
     * the owner too on some WorldGuard setups, not just non-members, which defeats
     * the entire point of ownership. Safe/idempotent to call repeatedly. Called
     * automatically on every rent/extend, and for every plot via /shipyard reload.
     */
    public void clearExplicitBuildFlag(Plot plot) {
        RegionManager regionManager = regionManagerFor(plot);
        if (regionManager == null) return;
        ProtectedRegion region = regionManager.getRegion(plot.getRegionId());
        if (region == null) return;
        region.setFlag(Flags.BUILD, null);
    }

    /** Adds a trusted helper as a WorldGuard region member (can build, can't manage the region). */
    public void addRegionMember(Plot plot, java.util.UUID uuid) {
        RegionManager regionManager = regionManagerFor(plot);
        if (regionManager == null) return;
        ProtectedRegion region = regionManager.getRegion(plot.getRegionId());
        if (region == null) return;
        region.getMembers().addPlayer(uuid);
    }

    public void removeRegionMember(Plot plot, java.util.UUID uuid) {
        RegionManager regionManager = regionManagerFor(plot);
        if (regionManager == null) return;
        ProtectedRegion region = regionManager.getRegion(plot.getRegionId());
        if (region == null) return;
        region.getMembers().removePlayer(uuid);
    }

    private RegionManager regionManagerFor(Plot plot) {
        World world = plugin.getServer().getWorld(plot.getWorld());
        if (world == null) return null;
        return WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    public Optional<Plot> findPlotAt(World world, int x, int y, int z) {
        for (Plot plot : plots.values()) {
            if (!plot.getWorld().equalsIgnoreCase(world.getName())) {
                continue;
            }
            if (plot.contains(x, y, z)) {
                return Optional.of(plot);
            }
        }
        return Optional.empty();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("plots");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            Plot plot = new Plot(
                    key,
                    s.getString("world"),
                    s.getInt("minX"), s.getInt("minY"), s.getInt("minZ"),
                    s.getInt("maxX"), s.getInt("maxY"), s.getInt("maxZ"),
                    s.getDouble("pricePerDay")
            );
            plots.put(key, plot);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Plot plot : plots.values()) {
            String base = "plots." + plot.getId() + ".";
            yaml.set(base + "world", plot.getWorld());
            yaml.set(base + "minX", plot.getMinX());
            yaml.set(base + "minY", plot.getMinY());
            yaml.set(base + "minZ", plot.getMinZ());
            yaml.set(base + "maxX", plot.getMaxX());
            yaml.set(base + "maxY", plot.getMaxY());
            yaml.set(base + "maxZ", plot.getMaxZ());
            yaml.set(base + "pricePerDay", plot.getPricePerDay());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save plots.yml: " + e.getMessage());
        }
    }
}
