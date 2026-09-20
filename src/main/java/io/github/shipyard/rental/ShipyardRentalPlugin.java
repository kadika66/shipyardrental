package io.github.shipyard.rental;

import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import io.github.shipyard.rental.command.ShipyardCommand;
import io.github.shipyard.rental.economy.EconomyService;
import io.github.shipyard.rental.gui.ShopGui;
import io.github.shipyard.rental.listener.BuildModeGuardListener;
import io.github.shipyard.rental.listener.PlayerSessionListener;
import io.github.shipyard.rental.manager.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class ShipyardRentalPlugin extends JavaPlugin {

    private PlotManager plotManager;
    private RentalManager rentalManager;
    private ListingManager listingManager;
    private WorthService worthService;
    private SchematicService schematicService;
    private InventorySnapshotManager snapshotManager;
    private EconomyService economyService;
    private ShopGui shopGui;

    private WorldEditPlugin worldEditPlugin;
    private File worldEditSchematicDir;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (getServer().getPluginManager().getPlugin("WorldEdit") == null
                || getServer().getPluginManager().getPlugin("WorldGuard") == null) {
            getLogger().severe("WorldEdit and WorldGuard are both required. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        worldEditPlugin = (WorldEditPlugin) getServer().getPluginManager().getPlugin("WorldEdit");
        String schemDirName;
        try {
            schemDirName = worldEditPlugin.getLocalConfiguration().saveDir;
        } catch (Exception e) {
            schemDirName = "schematics";
        }
        worldEditSchematicDir = new File(worldEditPlugin.getDataFolder(), schemDirName);
        if (!worldEditSchematicDir.exists()) {
            worldEditSchematicDir.mkdirs();
        }

        economyService = new EconomyService();
        if (!economyService.setup(this)) {
            getLogger().severe("Vault + an economy plugin are required. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        plotManager = new PlotManager(this);
        rentalManager = new RentalManager(this);
        listingManager = new ListingManager(this);
        worthService = new WorthService(this);
        schematicService = new SchematicService(this, worthService);
        snapshotManager = new InventorySnapshotManager(this);
        shopGui = new ShopGui(this);

        getCommand("shipyard").setExecutor(new ShipyardCommand(this));

        PlayerSessionListener sessionListener = new PlayerSessionListener(this);
        getServer().getPluginManager().registerEvents(sessionListener, this);
        getServer().getPluginManager().registerEvents(new BuildModeGuardListener(this), this);
        getServer().getPluginManager().registerEvents(shopGui, this);

        long intervalTicks = getConfig().getLong("Expiry Check Interval Seconds", 60) * 20L;
        getServer().getScheduler().runTaskTimer(this, this::sweepExpiredRentals, intervalTicks, intervalTicks);

        getLogger().info("ShipyardRental enabled with " + plotManager.all().size() + " plot(s).");

        double multiplier = getConfig().getDouble("Finish Price Multiplier", 1.0);
        if (multiplier <= 0) {
            getLogger().warning("'Finish Price Multiplier' in config.yml is " + multiplier
                    + " - every /shipyard finish will price at zero (or negative) regardless of "
                    + "what worth.yml says, no matter how the build is priced block-by-block.");
        }
    }

    private void sweepExpiredRentals() {
        long graceMinutes = getConfig().getLong("Expiry Grace Period Minutes", 1440);
        if (graceMinutes < 0) {
            return;
        }
        long graceMillis = graceMinutes * 60_000L;
        long now = System.currentTimeMillis();

        for (var entry : new java.util.HashMap<>(rentalManager.all()).entrySet()) {
            var rental = entry.getValue();
            if (!rental.isExpired()) {
                continue;
            }
            if (now - rental.getExpiresAtMillis() < graceMillis) {
                continue;
            }
            plotManager.get(rental.getPlotId()).ifPresent(plot -> {
                evictAllFromPlot(rental);
                org.bukkit.entity.Player online = getServer().getPlayer(rental.getRenter());
                if (online != null) {
                    online.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                            "&c[Shipyard] &7Your rental of plot '" + plot.getId() + "' expired and was reclaimed."));
                }
                schematicService.clearPlot(plot);
                rentalManager.end(plot);
            });
        }
    }

    /**
     * Force-restores every currently-online, currently-swapped-in player authorized on
     * this rental (the renter and any trusted helpers) before the plot gets cleared out
     * from under them.
     */
    public void evictAllFromPlot(io.github.shipyard.rental.model.Rental rental) {
        java.util.Set<java.util.UUID> everyone = new java.util.LinkedHashSet<>(rental.getTrusted());
        everyone.add(rental.getRenter());
        for (java.util.UUID uuid : everyone) {
            org.bukkit.entity.Player online = getServer().getPlayer(uuid);
            if (online != null && snapshotManager.hasSnapshot(online.getUniqueId())) {
                snapshotManager.exitBuildMode(online);
                online.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        "&d[Shipyard] &7Build session ended and your normal inventory was restored."));
            }
        }
    }

    public File getWorldEditSchematicDir() {
        return worldEditSchematicDir;
    }

    public WorldEditPlugin getWorldEditPlugin() {
        return worldEditPlugin;
    }

    public PlotManager getPlotManager() {
        return plotManager;
    }

    public RentalManager getRentalManager() {
        return rentalManager;
    }

    public ListingManager getListingManager() {
        return listingManager;
    }

    public WorthService getWorthService() {
        return worthService;
    }

    public SchematicService getSchematicService() {
        return schematicService;
    }

    public InventorySnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    public EconomyService getEconomyService() {
        return economyService;
    }

    public ShopGui getShopGui() {
        return shopGui;
    }
}
