package io.github.shipyard.rental.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An active (or recently expired) rental of a plot by a player, plus whichever
 * other players the renter has trusted to help build.
 */
public class Rental {

    private final String plotId;
    private final UUID renter;
    private long expiresAtMillis;
    private final Set<UUID> trusted = new LinkedHashSet<>();

    public Rental(String plotId, UUID renter, long expiresAtMillis) {
        this.plotId = plotId;
        this.renter = renter;
        this.expiresAtMillis = expiresAtMillis;
    }

    public String getPlotId() {
        return plotId;
    }

    public UUID getRenter() {
        return renter;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public void extend(long millisToAdd) {
        long base = Math.max(expiresAtMillis, System.currentTimeMillis());
        this.expiresAtMillis = base + millisToAdd;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAtMillis;
    }

    public Set<UUID> getTrusted() {
        return trusted;
    }

    public boolean trust(UUID uuid) {
        return trusted.add(uuid);
    }

    public boolean untrust(UUID uuid) {
        return trusted.remove(uuid);
    }

    /** True if this player is allowed to build here at all: the renter, or someone they trusted. */
    public boolean isAuthorized(UUID uuid) {
        return renter.equals(uuid) || trusted.contains(uuid);
    }
}
