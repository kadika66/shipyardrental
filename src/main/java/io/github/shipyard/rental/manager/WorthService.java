package io.github.shipyard.rental.manager;

import io.github.shipyard.rental.ShipyardRentalPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class WorthService {

    private final ShipyardRentalPlugin plugin;
    private final Map<Material, Double> prices = new HashMap<>();
    private double defaultPrice = 0.0;

    public WorthService(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        prices.clear();
        File file = new File(plugin.getDataFolder(), "worth.yml");
        if (!file.exists()) {
            plugin.saveResource("worth.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            Material mat = Material.matchMaterial(key);
            if (mat == null) {
                plugin.getLogger().warning("worth.yml: unknown material '" + key + "', skipping");
                continue;
            }
            prices.put(mat, yaml.getDouble(key));
        }
        defaultPrice = plugin.getConfig().getDouble("Default Block Worth", 0.0);

        // Loud on purpose: a near-empty or missing price sheet doesn't error out anywhere
        // downstream, it just quietly prices every ship at (close to) 0 - this is the one
        // place that failure mode is actually visible before someone runs /shipyard finish
        // and gets confused by a zero. Note this only (re)writes worth.yml on disk if it
        // doesn't already exist - an existing file from an earlier install is never
        // overwritten by a plugin update, so bumping the jar alone won't refresh a stale one.
        plugin.getLogger().info("Loaded " + prices.size() + " material price(s) from worth.yml"
                + " (fallback for anything else: " + defaultPrice + ").");
        if (prices.isEmpty()) {
            plugin.getLogger().warning("worth.yml has no usable price entries - every finished "
                    + "ship will price at 0 (or 'Default Block Worth') regardless of what's built. "
                    + "Check " + file.getPath() + " directly.");
        }
    }

    public boolean hasPrice(Material material) {
        return prices.containsKey(material);
    }

    public double priceOf(Material material) {
        return prices.getOrDefault(material, defaultPrice);
    }
}
