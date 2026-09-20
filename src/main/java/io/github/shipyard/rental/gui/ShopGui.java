package io.github.shipyard.rental.gui;

import io.github.shipyard.rental.ShipyardRentalPlugin;
import io.github.shipyard.rental.model.ShipListing;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ShopGui implements Listener {

    private static final String TITLE = ChatColor.DARK_PURPLE + "Your Finished Ships";

    private final ShipyardRentalPlugin plugin;
    /** Tracks which listing is in which slot for each currently-open shop inventory. */
    private final Map<Inventory, List<ShipListing>> openShops = new HashMap<>();

    public ShopGui(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        List<ShipListing> listings = plugin.getListingManager().getFor(player.getUniqueId());
        int size = Math.max(9, ((listings.size() - 1) / 9 + 1) * 9);
        size = Math.min(size, 54);
        Inventory inv = plugin.getServer().createInventory(null, size, TITLE);

        for (int i = 0; i < listings.size() && i < size; i++) {
            ShipListing listing = listings.get(i);
            inv.setItem(i, buildIcon(listing));
        }

        openShops.put(inv, listings);
        player.openInventory(inv);
    }

    private ItemStack buildIcon(ShipListing listing) {
        ItemStack item = new ItemStack(Material.OAK_BOAT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + listing.getShipName());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Blocks: " + ChatColor.WHITE + listing.getBlockCount());
        lore.add(ChatColor.GRAY + "Price: " + ChatColor.GOLD + plugin.getEconomyService().format(listing.getPrice()));
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to buy as a StructureBox");
        lore.add(ChatColor.DARK_GRAY + "Buy as many copies as you'd like");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        List<ShipListing> listings = openShops.get(event.getInventory());
        if (listings == null) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= listings.size()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ShipListing listing = listings.get(slot);
        purchase(player, listing, event.getInventory());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        openShops.remove(event.getInventory());
    }

    private void purchase(Player player, ShipListing listing, Inventory inv) {
        if (!listing.getOwner().equals(player.getUniqueId())) {
            // Shop only ever shows the viewer's own listings, but double-check anyway.
            return;
        }
        var econ = plugin.getEconomyService();
        if (!econ.has(player, listing.getPrice())) {
            player.sendMessage(ChatColor.RED + "[Shipyard] You need " + econ.format(listing.getPrice())
                    + " to claim this ship.");
            return;
        }
        if (!econ.withdraw(player, listing.getPrice())) {
            player.sendMessage(ChatColor.RED + "[Shipyard] Payment failed.");
            return;
        }
        if (plugin.getConfig().getString("Purchase Payment Destination", "ECONOMY_SINK")
                .equalsIgnoreCase("SERVER_ACCOUNT")) {
            org.bukkit.OfflinePlayer server = plugin.getServer()
                    .getOfflinePlayer(plugin.getConfig().getString("Server Account Name", "bank"));
            econ.deposit(server, listing.getPrice());
        }

        boolean gaveItem = giveStructureBox(player, listing);
        if (!gaveItem) {
            // Refund - never take payment without delivering the box.
            econ.deposit(player, listing.getPrice());
            player.sendMessage(ChatColor.RED + "[Shipyard] Couldn't create the StructureBox "
                    + "(is StructureBoxes installed, and do you have structureboxes.create?). Refunded.");
            return;
        }

        // Deliberately NOT removing the listing here. The saved .schem file on disk isn't
        // consumed by a purchase - it's a design a player can buy copies of indefinitely,
        // like a blueprint rather than a one-off item. /shipyard delist lets a player pull
        // their own design down once they're done selling it.
        player.sendMessage(ChatColor.GREEN + "[Shipyard] Bought " + listing.getShipName()
                + " for " + econ.format(listing.getPrice()) + "!");
        open(player); // refresh the GUI in place
    }

    private boolean giveStructureBox(Player player, ShipListing listing) {
        if (plugin.getServer().getPluginManager().getPlugin("StructureBoxes") == null) {
            return false;
        }
        // StructureBoxes' /structurebox create command must run as the buyer: it resolves
        // "<schemDir>/<sender-uuid>/<name>.schem" itself, so pass the bare ship name (no
        // uuid prefix) - this only works because the shop only ever shows a player's own
        // listings, so buyer == owner == the uuid folder the schematic was saved under.
        //
        // Players never hold "structureboxes.create" standing - if they did, they could
        // just type the command themselves and mint a StructureBox for ANY schematic on
        // the server for free, bypassing the whole worth/economy system. Instead we grant
        // that one permission node for the exact duration of this single dispatch via a
        // PermissionAttachment, then immediately revoke it. Bukkit's command dispatch is
        // synchronous on the main thread, so nothing else can run in the gap.
        String cmd = "structurebox create " + listing.getShipName();
        org.bukkit.permissions.PermissionAttachment attachment =
                player.addAttachment(plugin);
        attachment.setPermission("structureboxes.create", true);
        try {
            return plugin.getServer().dispatchCommand(player, cmd);
        } finally {
            player.removeAttachment(attachment);
        }
    }
}
