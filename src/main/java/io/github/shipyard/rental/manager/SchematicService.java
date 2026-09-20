package io.github.shipyard.rental.manager;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import io.github.shipyard.rental.ShipyardRentalPlugin;
import io.github.shipyard.rental.model.Plot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Bridges a plot's raw Bukkit blocks and WorldEdit's clipboard/schematic format.
 * Reads blocks straight off the Bukkit world (rather than going through a full
 * WorldEdit EditSession) since plots are always simple, bounded cuboids.
 *
 * <p>Blocks are captured as plain {@link BlockState} (type + orientation only,
 * no NBT) EXCEPT for a small, explicit allowlist of purely decorative block
 * families - signs, banners, heads/skulls - which are captured with their full
 * NBT (via WorldEdit's {@code getFullBlock}) so sign text, banner patterns, and
 * skin data survive into the schematic.
 *
 * <p>This is deliberately an allowlist, not a blocklist of "known container
 * types": stripping NBT by default and only opting specific safe types back in
 * means a future Minecraft block that happens to hold an item (this has
 * happened before - Chiseled Bookshelves in 1.20 hold real books) can't slip
 * through un-stripped just because nobody thought to blocklist it yet. If you
 * add a new decorative type here, double check it can never hold an item.
 */
public class SchematicService {

    private final ShipyardRentalPlugin plugin;
    private final WorthService worthService;

    public SchematicService(ShipyardRentalPlugin plugin, WorthService worthService) {
        this.plugin = plugin;
        this.worthService = worthService;
    }

    public static class Priced {
        public final Clipboard clipboard;
        public final double totalPrice;
        /** Sum of worth.yml prices before "Finish Price Multiplier" is applied. */
        public final double rawMaterialWorth;
        public final int blockCount;
        /** Blocks that fell back to "Default Block Worth" because worth.yml had no entry for them. */
        public final int unmatchedBlockCount;
        /** A few example material names that were unmatched, for diagnostics - not exhaustive. */
        public final java.util.Set<String> unmatchedExamples;

        Priced(Clipboard clipboard, double totalPrice, double rawMaterialWorth, int blockCount,
               int unmatchedBlockCount, java.util.Set<String> unmatchedExamples) {
            this.clipboard = clipboard;
            this.totalPrice = totalPrice;
            this.rawMaterialWorth = rawMaterialWorth;
            this.blockCount = blockCount;
            this.unmatchedBlockCount = unmatchedBlockCount;
            this.unmatchedExamples = unmatchedExamples;
        }
    }

    /**
     * Purely decorative block families whose NBT is safe to keep - none of these
     * can ever hold an item, so there's no duplication risk in preserving it.
     * Matched by Material name suffix so every wood/color/wall/hanging variant
     * (OAK_SIGN, OAK_WALL_SIGN, OAK_HANGING_SIGN, RED_BANNER, WITHER_SKELETON_SKULL,
     * PLAYER_WALL_HEAD, ...) is covered without enumerating each one by name.
     */
    private static boolean isDecorativeNbtType(Material material) {
        String name = material.name();
        return name.endsWith("SIGN") || name.endsWith("BANNER")
                || name.endsWith("HEAD") || name.endsWith("SKULL");
    }

    public static class PriceReport {
        public final double totalPrice;
        public final double rawMaterialWorth;
        public final int blockCount;
        public final int unmatchedBlockCount;
        public final java.util.Set<String> unmatchedExamples;

        PriceReport(double totalPrice, double rawMaterialWorth, int blockCount,
                    int unmatchedBlockCount, java.util.Set<String> unmatchedExamples) {
            this.totalPrice = totalPrice;
            this.rawMaterialWorth = rawMaterialWorth;
            this.blockCount = blockCount;
            this.unmatchedBlockCount = unmatchedBlockCount;
            this.unmatchedExamples = unmatchedExamples;
        }
    }

    /**
     * Prices every block inside an arbitrary WorldEdit region - any shape, not just a
     * cuboid, and not tied to a shipyard plot at all - without building a schematic.
     * Backs /shipyard appraise: select any area on the map (a finished ship, someone's
     * base, whatever) and get a "what's this worth" readout using the exact same
     * worth.yml + Finish Price Multiplier pricing /shipyard finish uses, so the two
     * numbers can never quietly drift apart from having separate pricing logic.
     */
    public PriceReport appraiseRegion(com.sk89q.worldedit.regions.Region region, World world) {
        double totalPrice = 0.0;
        int blockCount = 0;
        int unmatchedBlockCount = 0;
        java.util.Set<String> unmatchedExamples = new java.util.LinkedHashSet<>();
        double multiplier = plugin.getConfig().getDouble("Finish Price Multiplier", 1.0);

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    // Non-cuboid selections (polygon, convex hull, etc.) have a
                    // rectangular bounding box bigger than their actual shape -
                    // skip anything outside the real selection.
                    if (!region.contains(BlockVector3.at(x, y, z))) {
                        continue;
                    }
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (material.isAir()) {
                        continue;
                    }
                    if (!worthService.hasPrice(material)) {
                        unmatchedBlockCount++;
                        if (unmatchedExamples.size() < 8) {
                            unmatchedExamples.add(material.name());
                        }
                    }
                    totalPrice += worthService.priceOf(material);
                    blockCount++;
                }
            }
        }

        return new PriceReport(totalPrice * multiplier, totalPrice, blockCount, unmatchedBlockCount, unmatchedExamples);
    }

    /**
     * Reads every block in the plot, builds a WorldEdit clipboard, and prices it.
     * The clipboard is trimmed to the tightest bounding box actually containing a
     * block - not the full plot cuboid. Plots are usually much bigger than the ship
     * built inside them, and StructureBoxes checks a saved structure's *dimensions*
     * (not just its block count) both against its max-size limit and when scanning
     * for room to paste; shipping the whole plot's empty air along for the ride
     * would make every ship look enormous by both those measures for no reason.
     */
    public Priced captureAndPrice(Plot plot) {
        World world = plugin.getServer().getWorld(plot.getWorld());
        if (world == null) {
            throw new IllegalStateException("World '" + plot.getWorld() + "' is not loaded");
        }

        // First pass: find the tightest bounding box that actually contains a block.
        Integer usedMinX = null, usedMinY = null, usedMinZ = null;
        Integer usedMaxX = null, usedMaxY = null, usedMaxZ = null;
        for (int x = plot.getMinX(); x <= plot.getMaxX(); x++) {
            for (int y = plot.getMinY(); y <= plot.getMaxY(); y++) {
                for (int z = plot.getMinZ(); z <= plot.getMaxZ(); z++) {
                    if (world.getBlockAt(x, y, z).getType().isAir()) {
                        continue;
                    }
                    if (usedMinX == null) {
                        usedMinX = usedMaxX = x;
                        usedMinY = usedMaxY = y;
                        usedMinZ = usedMaxZ = z;
                    } else {
                        usedMinX = Math.min(usedMinX, x);
                        usedMaxX = Math.max(usedMaxX, x);
                        usedMinY = Math.min(usedMinY, y);
                        usedMaxY = Math.max(usedMaxY, y);
                        usedMinZ = Math.min(usedMinZ, z);
                        usedMaxZ = Math.max(usedMaxZ, z);
                    }
                }
            }
        }

        if (usedMinX == null) {
            // Nothing built at all - caller checks blockCount == 0 and bails out
            // before ever touching the clipboard, so an empty placeholder is fine.
            BlockVector3 origin = BlockVector3.at(plot.getMinX(), plot.getMinY(), plot.getMinZ());
            CuboidRegion emptyRegion = new CuboidRegion(origin, origin);
            return new Priced(new BlockArrayClipboard(emptyRegion), 0.0, 0.0, 0, 0, java.util.Set.of());
        }

        BlockVector3 min = BlockVector3.at(usedMinX, usedMinY, usedMinZ);
        BlockVector3 max = BlockVector3.at(usedMaxX, usedMaxY, usedMaxZ);
        CuboidRegion region = new CuboidRegion(min, max);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

        // Origin stays aligned to the same X/Z corner as the region (min), but sits one
        // block BELOW the structure's actual floor on Y. StructureBoxes pastes at
        // (clickedBlock + (clipboard.getMinimumPoint() - clipboard.getOrigin())), so
        // shifting the origin down by "verticalOffset" blocks makes that subtraction
        // come out to (0, verticalOffset, 0) - the ship's floor lands that many blocks
        // ABOVE wherever the StructureBox itself gets placed, instead of landing flush
        // with (and overlapping/replacing) the box's own block.
        int verticalOffset = plugin.getConfig().getInt("Schematic Vertical Offset", 1);
        clipboard.setOrigin(min.subtract(0, verticalOffset, 0));

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);

        double totalPrice = 0.0;
        int blockCount = 0;
        int unmatchedBlockCount = 0;
        java.util.Set<String> unmatchedExamples = new java.util.LinkedHashSet<>();
        double multiplier = plugin.getConfig().getDouble("Finish Price Multiplier", 1.0);

        // Second pass, over the trimmed bounds only: actually populate the clipboard.
        // Pockets of air fully inside the used bounding box (a hollow hull, etc.) are
        // still skipped same as before - only the unused OUTER padding is gone now.
        for (int x = usedMinX; x <= usedMaxX; x++) {
            for (int y = usedMinY; y <= usedMaxY; y++) {
                for (int z = usedMinZ; z <= usedMaxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material material = block.getType();
                    if (material.isAir()) {
                        continue;
                    }
                    BlockVector3 pt = BlockVector3.at(x, y, z);

                    try {
                        if (isDecorativeNbtType(material)) {
                            // Full read including NBT (sign text, banner pattern, skull skin).
                            BaseBlock full = weWorld.getFullBlock(pt);
                            clipboard.setBlock(pt, full);
                        } else {
                            // Type/orientation only - deliberately drops any NBT, including
                            // inventory contents, so chests/furnaces/hoppers/etc. paste empty.
                            BlockState state = BukkitAdapter.adapt(block.getBlockData());
                            clipboard.setBlock(pt, state);
                        }
                    } catch (Exception ignored) {
                        // Skip blocks WorldEdit can't represent (rare, e.g. some tile-entity edge cases).
                    }

                    if (!worthService.hasPrice(material)) {
                        unmatchedBlockCount++;
                        if (unmatchedExamples.size() < 8) {
                            unmatchedExamples.add(material.name());
                        }
                    }
                    totalPrice += worthService.priceOf(material);
                    blockCount++;
                }
            }
        }

        return new Priced(clipboard, totalPrice * multiplier, totalPrice, blockCount, unmatchedBlockCount, unmatchedExamples);
    }

    /**
     * Writes the clipboard to WorldEdit's schematic directory, per-player, matching
     * the path StructureBoxes itself looks for: {schemDir}/{playerUUID}/{shipName}.schem
     *
     * @return the path relative to the schematic directory, without extension,
     *         e.g. "1c2b3.../MyShip" - this is what you pass to "/structurebox create".
     */
    public String save(Clipboard clipboard, java.util.UUID owner, String shipName) throws IOException {
        File schemDir = plugin.getWorldEditSchematicDir();
        File ownerDir = new File(schemDir, owner.toString());
        if (!ownerDir.exists()) {
            ownerDir.mkdirs();
        }
        File out = new File(ownerDir, shipName + ".schem");

        try (OutputStream os = new FileOutputStream(out);
             ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(os)) {
            writer.write(clipboard);
        }
        return owner.toString() + "/" + shipName;
    }

    /** Fills every block in the plot with the configured reset material (default AIR). */
    public void clearPlot(Plot plot) {
        World world = plugin.getServer().getWorld(plot.getWorld());
        if (world == null) {
            return;
        }
        Material fill;
        try {
            fill = Material.valueOf(plugin.getConfig().getString("Reset Fill Block", "AIR").toUpperCase());
        } catch (IllegalArgumentException e) {
            fill = Material.AIR;
        }
        for (int x = plot.getMinX(); x <= plot.getMaxX(); x++) {
            for (int y = plot.getMinY(); y <= plot.getMaxY(); y++) {
                for (int z = plot.getMinZ(); z <= plot.getMaxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    clearContainerContents(block);
                    block.setType(fill, false);
                }
            }
        }
    }

    /**
     * Empties a container's contents BEFORE its block gets overwritten. Even though the
     * schematic capture never includes container contents in the first place (see
     * captureAndPrice), directly overwriting a chest/furnace/hopper/etc's block type
     * still triggers Minecraft's own "container removed" handling under the hood, which
     * spills whatever was inside as item entities on the ground - a completely separate
     * duplication path with nothing to do with the schematic, and one that can't be
     * caught with a Bukkit event listener since no block-break event fires for a
     * plugin-initiated setType() swap. Checking the broad InventoryHolder interface,
     * rather than enumerating specific container materials, means any future block
     * Mojang adds that holds items this way is covered automatically. Jukeboxes are a
     * special case since their record isn't exposed as a standard Inventory.
     */
    private void clearContainerContents(Block block) {
        org.bukkit.block.BlockState state = block.getState(false); // live state, no snapshot/update ambiguity
        if (state instanceof org.bukkit.inventory.InventoryHolder holder) {
            holder.getInventory().clear();
        }
        if (state instanceof org.bukkit.block.Jukebox jukebox) {
            jukebox.setRecord(null);
            jukebox.update(true, false);
        }
    }
}
