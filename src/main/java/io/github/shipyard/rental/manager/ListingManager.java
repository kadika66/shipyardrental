package io.github.shipyard.rental.manager;

import io.github.shipyard.rental.ShipyardRentalPlugin;
import io.github.shipyard.rental.model.ShipListing;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ListingManager {

    private final ShipyardRentalPlugin plugin;
    private final File file;
    /** owner UUID -> ship name (lowercase) -> listing */
    private final Map<UUID, Map<String, ShipListing>> listings = new LinkedHashMap<>();

    public ListingManager(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "listings.yml");
        load();
    }

    public void add(ShipListing listing) {
        listings.computeIfAbsent(listing.getOwner(), u -> new LinkedHashMap<>())
                .put(listing.getShipName().toLowerCase(), listing);
        save();
    }

    public void remove(ShipListing listing) {
        Map<String, ShipListing> map = listings.get(listing.getOwner());
        if (map != null) {
            map.remove(listing.getShipName().toLowerCase());
        }
        save();
    }

    public List<ShipListing> getFor(UUID owner) {
        return new ArrayList<>(listings.getOrDefault(owner, Map.of()).values());
    }

    public java.util.Optional<ShipListing> get(UUID owner, String shipName) {
        Map<String, ShipListing> map = listings.get(owner);
        if (map == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(map.get(shipName.toLowerCase()));
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("listings");
        if (root == null) {
            return;
        }
        for (String ownerKey : root.getKeys(false)) {
            ConfigurationSection ownerSection = root.getConfigurationSection(ownerKey);
            if (ownerSection == null) continue;
            UUID owner = UUID.fromString(ownerKey);
            for (String shipKey : ownerSection.getKeys(false)) {
                ConfigurationSection s = ownerSection.getConfigurationSection(shipKey);
                if (s == null) continue;
                ShipListing listing = new ShipListing(
                        owner,
                        s.getString("shipName"),
                        s.getString("schematicPath"),
                        s.getDouble("price"),
                        s.getInt("blockCount")
                );
                listings.computeIfAbsent(owner, u -> new LinkedHashMap<>()).put(shipKey, listing);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, ShipListing>> ownerEntry : listings.entrySet()) {
            for (Map.Entry<String, ShipListing> shipEntry : ownerEntry.getValue().entrySet()) {
                ShipListing l = shipEntry.getValue();
                String base = "listings." + ownerEntry.getKey() + "." + shipEntry.getKey() + ".";
                yaml.set(base + "shipName", l.getShipName());
                yaml.set(base + "schematicPath", l.getSchematicRelativePath());
                yaml.set(base + "price", l.getPrice());
                yaml.set(base + "blockCount", l.getBlockCount());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save listings.yml: " + e.getMessage());
        }
    }
}
