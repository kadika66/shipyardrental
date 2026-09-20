package io.github.shipyard.rental.model;

import java.util.UUID;

/**
 * A finished ship: priced, saved to a schematic on disk, and waiting for the
 * builder to "buy" it from their own shop GUI to receive it as a StructureBox.
 */
public class ShipListing {

    private final UUID owner;
    private final String shipName;
    /** Path relative to WorldEdit's schematic directory, e.g. "<uuid>/MyShip" (no extension). */
    private final String schematicRelativePath;
    private final double price;
    private final long createdAtMillis;
    private final int blockCount;

    public ShipListing(UUID owner, String shipName, String schematicRelativePath,
                        double price, int blockCount) {
        this.owner = owner;
        this.shipName = shipName;
        this.schematicRelativePath = schematicRelativePath;
        this.price = price;
        this.blockCount = blockCount;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public UUID getOwner() {
        return owner;
    }

    public String getShipName() {
        return shipName;
    }

    public String getSchematicRelativePath() {
        return schematicRelativePath;
    }

    public double getPrice() {
        return price;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public int getBlockCount() {
        return blockCount;
    }
}
