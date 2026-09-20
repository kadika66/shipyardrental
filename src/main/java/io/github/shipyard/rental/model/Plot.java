package io.github.shipyard.rental.model;

/**
 * A shipyard plot: a cuboid area, backed by a WorldGuard region, that can be rented.
 * The WorldGuard region id is always "shipyard_" + id so it's easy to spot in /rg list.
 */
public class Plot {

    private final String id;
    private final String world;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private final double pricePerDay;

    public Plot(String id, String world, int minX, int minY, int minZ,
                int maxX, int maxY, int maxZ, double pricePerDay) {
        this.id = id;
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.pricePerDay = pricePerDay;
    }

    public String getId() {
        return id;
    }

    public String getRegionId() {
        return "shipyard_" + id;
    }

    public String getWorld() {
        return world;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public double getPricePerDay() {
        return pricePerDay;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
