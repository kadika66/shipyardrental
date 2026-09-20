package io.github.shipyard.rental.manager;

import io.github.shipyard.rental.ShipyardRentalPlugin;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saves a player's inventory, armor, offhand, XP and GameMode to disk, clears
 * them out for a build session, and restores everything on the way out.
 *
 * One snapshot file per player at a time; a snapshot existing on disk is itself
 * the source of truth for "this player is currently inside a build session",
 * which lets {@link #hasSnapshot(UUID)} double as a crash/relog safety check.
 */
public class InventorySnapshotManager {

    private final ShipyardRentalPlugin plugin;
    private final File dir;

    public InventorySnapshotManager(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "snapshots");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public boolean hasSnapshot(UUID uuid) {
        return fileFor(uuid).exists();
    }

    public void enterBuildMode(Player player, GameMode buildMode) {
        File f = fileFor(player.getUniqueId());
        YamlConfiguration yaml = new YamlConfiguration();

        PlayerInventory inv = player.getInventory();
        yaml.set("contents", serialize(inv.getContents()));
        yaml.set("armor", serialize(inv.getArmorContents()));
        yaml.set("offhand", inv.getItemInOffHand());
        yaml.set("gamemode", player.getGameMode().name());
        yaml.set("exp", player.getExp());
        yaml.set("level", player.getLevel());

        try {
            yaml.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save inventory snapshot for " + player.getName() + ": " + e.getMessage());
            return;
        }

        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(new ItemStack(org.bukkit.Material.AIR));
        player.setExp(0f);
        player.setLevel(0);
        player.setGameMode(buildMode);
    }

    /** @return true if a snapshot was found and restored. */
    public boolean exitBuildMode(Player player) {
        File f = fileFor(player.getUniqueId());
        if (!f.exists()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);

        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setContents(deserialize(yaml.getList("contents")));
        inv.setArmorContents(deserialize(yaml.getList("armor")));
        ItemStack offhand = yaml.getItemStack("offhand");
        inv.setItemInOffHand(offhand != null ? offhand : new ItemStack(org.bukkit.Material.AIR));
        player.setExp((float) yaml.getDouble("exp", 0));
        player.setLevel(yaml.getInt("level", 0));

        GameMode restore;
        try {
            restore = GameMode.valueOf(yaml.getString("gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            restore = GameMode.SURVIVAL;
        }
        player.setGameMode(restore);

        f.delete();
        return true;
    }

    private File fileFor(UUID uuid) {
        return new File(dir, uuid.toString() + ".yml");
    }

    private List<Map<String, Object>> serialize(ItemStack[] items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null) {
                list.add(new HashMap<>());
            } else {
                list.add(item.serialize());
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private ItemStack[] deserialize(List<?> raw) {
        ItemStack[] out = new ItemStack[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Object o = raw.get(i);
            if (o instanceof Map && !((Map<?, ?>) o).isEmpty()) {
                out[i] = ItemStack.deserialize((Map<String, Object>) o);
            } else {
                out[i] = new ItemStack(org.bukkit.Material.AIR);
            }
        }
        return out;
    }
}
