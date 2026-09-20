package io.github.shipyard.rental.command;

import com.sk89q.worldedit.regions.Region;
import io.github.shipyard.rental.ShipyardRentalPlugin;
import io.github.shipyard.rental.manager.SchematicService;
import io.github.shipyard.rental.model.Plot;
import io.github.shipyard.rental.model.Rental;
import io.github.shipyard.rental.model.ShipListing;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ShipyardCommand implements CommandExecutor {

    private final ShipyardRentalPlugin plugin;

    public ShipyardCommand(ShipyardRentalPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "define" -> define(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "rent" -> rent(sender, args);
            case "extend" -> extend(sender, args);
            case "trust" -> trust(sender, args);
            case "untrust" -> untrust(sender, args);
            case "trusted" -> trustedList(sender);
            case "finish" -> finish(sender, args);
            case "delist" -> delist(sender, args);
            case "shop" -> shop(sender);
            case "info" -> info(sender, args);
            case "reset" -> reset(sender, args);
            case "appraise" -> appraise(sender);
            case "tp", "visit" -> teleport(sender, args);
            case "reload" -> reload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "== Shipyard ==");
        sender.sendMessage(ChatColor.GRAY + "/shipyard define <id> <pricePerDay> " + ChatColor.DARK_GRAY + "(needs a //wand selection)");
        sender.sendMessage(ChatColor.GRAY + "/shipyard remove <id>");
        sender.sendMessage(ChatColor.GRAY + "/shipyard list");
        sender.sendMessage(ChatColor.GRAY + "/shipyard rent <id> <days>");
        sender.sendMessage(ChatColor.GRAY + "/shipyard extend <id> <days>");
        sender.sendMessage(ChatColor.GRAY + "/shipyard tp [id] " + ChatColor.DARK_GRAY + "(teleport to your rented plot, or a specific one)");
        sender.sendMessage(ChatColor.GRAY + "/shipyard trust <player> " + ChatColor.DARK_GRAY + "(let someone help you build)");
        sender.sendMessage(ChatColor.GRAY + "/shipyard untrust <player>");
        sender.sendMessage(ChatColor.GRAY + "/shipyard trusted");
        sender.sendMessage(ChatColor.GRAY + "/shipyard finish <shipName>");
        sender.sendMessage(ChatColor.GRAY + "/shipyard delist <shipName> " + ChatColor.DARK_GRAY + "(pull one of your own designs off the shop)");
        sender.sendMessage(ChatColor.GRAY + "/shipyard shop");
        sender.sendMessage(ChatColor.GRAY + "/shipyard info [id]");
        sender.sendMessage(ChatColor.GRAY + "/shipyard reset <id>");
        sender.sendMessage(ChatColor.GRAY + "/shipyard appraise " + ChatColor.DARK_GRAY + "(price your current //wand selection, anywhere - not tied to a plot)");
    }

    // ---- admin: define ----

    private void define(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard define <id> <pricePerDay>");
            return;
        }
        Player player = (Player) sender;
        String id = args[1];
        double price;
        try {
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Price must be a number.");
            return;
        }
        if (plugin.getPlotManager().get(id).isPresent()) {
            sender.sendMessage(ChatColor.RED + "A plot with id '" + id + "' already exists.");
            return;
        }

        Region selection;
        try {
            var weSession = plugin.getWorldEditPlugin().getSession(player);
            var weWorld = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getWorld());
            selection = weSession.getSelection(weWorld);
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Make a WorldEdit selection first (//wand, then //pos1 and //pos2).");
            return;
        }

        Plot plot = plugin.getPlotManager().define(id, player.getWorld(),
                selection.getMinimumPoint(), selection.getMaximumPoint(), price);
        sender.sendMessage(ChatColor.GREEN + "Defined plot '" + plot.getId() + "' ("
                + plot.getPricePerDay() + "/day), region " + plot.getRegionId() + ".");
    }

    private void remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shipyardrental.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard remove <id>");
            return;
        }
        boolean removed = plugin.getPlotManager().remove(args[1]);
        sender.sendMessage(removed ? ChatColor.GREEN + "Removed plot '" + args[1] + "'."
                : ChatColor.RED + "No such plot.");
    }

    private void list(CommandSender sender) {
        var plots = plugin.getPlotManager().all();
        if (plots.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No plots defined yet.");
            return;
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "== Shipyard plots ==");
        for (Plot plot : plots.values()) {
            Optional<Rental> rental = plugin.getRentalManager().getRental(plot.getId());
            String status = rental.map(r -> r.isExpired() ? ChatColor.RED + "expired"
                            : ChatColor.YELLOW + "rented by " + offlineName(r.getRenter()))
                    .orElse(ChatColor.GREEN + "free");
            sender.sendMessage(ChatColor.GRAY + "- " + plot.getId() + " (" + plot.getPricePerDay()
                    + "/day, " + plot.getWorld() + ") " + status);
        }
    }

    // ---- players: rent/extend ----

    private void rent(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.rent")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard rent <id> <days>");
            return;
        }
        Player player = (Player) sender;
        Optional<Plot> plotOpt = plugin.getPlotManager().get(args[1]);
        if (plotOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No such plot.");
            return;
        }
        Plot plot = plotOpt.get();

        int days;
        try {
            days = Integer.parseInt(args[2]);
            if (days <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Days must be a positive whole number.");
            return;
        }

        Optional<Rental> existing = plugin.getRentalManager().getRental(plot.getId());
        if (existing.isPresent() && !existing.get().isExpired()
                && !existing.get().getRenter().equals(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "That plot is already rented by someone else. Try /shipyard extend once it frees up.");
            return;
        }

        boolean alreadyRentsThisPlot = existing.isPresent() && !existing.get().isExpired()
                && existing.get().getRenter().equals(player.getUniqueId());
        if (!alreadyRentsThisPlot && !player.hasPermission("shipyardrental.admin.bypass")) {
            long activeCount = plugin.getRentalManager().countActiveRentalsFor(player.getUniqueId());
            int cap = plugin.getRentalManager().getMaxRentals(player);
            if (activeCount >= cap) {
                sender.sendMessage(ChatColor.RED + "You already rent " + activeCount + " plot(s) - "
                        + "the most you're allowed at once is " + cap + ".");
                return;
            }
        }

        double cost = plot.getPricePerDay() * days;
        var econ = plugin.getEconomyService();
        if (!econ.has(player, cost)) {
            sender.sendMessage(ChatColor.RED + "You need " + econ.format(cost) + " to rent this for " + days + " day(s).");
            return;
        }
        if (!econ.withdraw(player, cost)) {
            sender.sendMessage(ChatColor.RED + "Payment failed.");
            return;
        }
        plugin.getRentalManager().startOrExtend(plot, player.getUniqueId(), days);
        sender.sendMessage(ChatColor.GREEN + "Rented '" + plot.getId() + "' for " + days
                + " day(s) (" + econ.format(cost) + "). Walk in to start building!");
    }

    private void extend(CommandSender sender, String[] args) {
        // Same flow as rent, but requires the sender to already be the renter.
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.rent")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard extend <id> <days>");
            return;
        }
        Player player = (Player) sender;
        Optional<Plot> plotOpt = plugin.getPlotManager().get(args[1]);
        if (plotOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No such plot.");
            return;
        }
        Plot plot = plotOpt.get();
        Optional<Rental> rentalOpt = plugin.getRentalManager().getRental(plot.getId());
        if (rentalOpt.isEmpty() || !rentalOpt.get().getRenter().equals(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You don't currently rent that plot.");
            return;
        }
        int days;
        try {
            days = Integer.parseInt(args[2]);
            if (days <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Days must be a positive whole number.");
            return;
        }
        double cost = plot.getPricePerDay() * days;
        var econ = plugin.getEconomyService();
        if (!econ.has(player, cost) || !econ.withdraw(player, cost)) {
            sender.sendMessage(ChatColor.RED + "You need " + econ.format(cost) + " to extend by " + days + " day(s).");
            return;
        }
        plugin.getRentalManager().startOrExtend(plot, player.getUniqueId(), days);
        sender.sendMessage(ChatColor.GREEN + "Extended '" + plot.getId() + "' by " + days + " day(s).");
    }

    // ---- players: teleport ----

    private void teleport(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.teleport")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        Player player = (Player) sender;

        Plot target;
        if (args.length >= 2) {
            Optional<Plot> plotOpt = plugin.getPlotManager().get(args[1]);
            if (plotOpt.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No such plot.");
                return;
            }
            target = plotOpt.get();
            Optional<Rental> rentalOpt = plugin.getRentalManager().getRental(target.getId());
            boolean authorized = rentalOpt.isPresent() && !rentalOpt.get().isExpired()
                    && rentalOpt.get().isAuthorized(player.getUniqueId());
            if (!authorized && !player.hasPermission("shipyardrental.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have access to that plot.");
                return;
            }
        } else {
            // No id given: prefer a plot the player actively rents; else fall back to
            // one they've been trusted into, as long as that's unambiguous.
            Optional<Rental> ownRental = plugin.getRentalManager().getRentalFor(player.getUniqueId());
            if (ownRental.isPresent() && !ownRental.get().isExpired()) {
                target = plugin.getPlotManager().get(ownRental.get().getPlotId()).orElse(null);
            } else {
                List<Rental> trustedIn = plugin.getRentalManager().all().values().stream()
                        .filter(r -> !r.isExpired() && r.getTrusted().contains(player.getUniqueId()))
                        .toList();
                if (trustedIn.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "You don't have an active rental, and aren't "
                            + "trusted into anyone else's. Try /shipyard tp <id>.");
                    return;
                }
                if (trustedIn.size() > 1) {
                    String ids = trustedIn.stream().map(Rental::getPlotId)
                            .collect(java.util.stream.Collectors.joining(", "));
                    sender.sendMessage(ChatColor.YELLOW + "You're trusted into multiple plots ("
                            + ids + ") - specify one: /shipyard tp <id>.");
                    return;
                }
                target = plugin.getPlotManager().get(trustedIn.get(0).getPlotId()).orElse(null);
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Your plot no longer exists.");
                return;
            }
        }

        World world = plugin.getServer().getWorld(target.getWorld());
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "That plot's world isn't loaded.");
            return;
        }
        // Center horizontally, and always 2 blocks above the plot's own ceiling - since
        // nothing built can ever exceed the plot's own maxY, this is guaranteed clear
        // regardless of what's actually been built, no need to hunt for a safe Y.
        double centerX = (target.getMinX() + target.getMaxX()) / 2.0 + 0.5;
        double centerZ = (target.getMinZ() + target.getMaxZ()) / 2.0 + 0.5;
        Location loc = new Location(world, centerX, target.getMaxY() + 2, centerZ);
        player.teleport(loc);
        sender.sendMessage(ChatColor.GREEN + "Teleported to '" + target.getId() + "'.");
    }

    // ---- players: trust ----

    private void trust(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.trust")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard trust <player>");
            return;
        }
        Player player = (Player) sender;
        Optional<Rental> rentalOpt = plugin.getRentalManager().getRentalFor(player.getUniqueId());
        if (rentalOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "You don't have an active rental to trust someone into.");
            return;
        }
        Rental rental = rentalOpt.get();
        Optional<Plot> plotOpt = plugin.getPlotManager().get(rental.getPlotId());
        if (plotOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Your plot no longer exists.");
            return;
        }

        var target = resolvePlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Never seen a player by that name.");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You already have access to your own plot.");
            return;
        }

        boolean added = plugin.getRentalManager().trust(rental, plotOpt.get(), target.getUniqueId());
        if (!added) {
            sender.sendMessage(ChatColor.YELLOW + (target.getName() != null ? target.getName() : "That player")
                    + " is already trusted.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "Trusted " + target.getName() + " to build in '" + plot(rental) + "'.");
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            onlineTarget.sendMessage(ChatColor.GREEN + "[Shipyard] " + player.getName()
                    + " trusted you to help build in their shipyard plot '" + rental.getPlotId() + "'. Walk in to start.");
        }
    }

    private void untrust(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard untrust <player>");
            return;
        }
        Player player = (Player) sender;
        Optional<Rental> rentalOpt = plugin.getRentalManager().getRentalFor(player.getUniqueId());
        if (rentalOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "You don't have an active rental.");
            return;
        }
        Rental rental = rentalOpt.get();
        Optional<Plot> plotOpt = plugin.getPlotManager().get(rental.getPlotId());
        if (plotOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Your plot no longer exists.");
            return;
        }

        var target = resolvePlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Never seen a player by that name.");
            return;
        }

        boolean removed = plugin.getRentalManager().untrust(rental, plotOpt.get(), target.getUniqueId());
        if (!removed) {
            sender.sendMessage(ChatColor.YELLOW + "They weren't trusted in the first place.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "Revoked " + target.getName() + "'s access to '" + rental.getPlotId() + "'.");
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            if (plugin.getSnapshotManager().hasSnapshot(onlineTarget.getUniqueId())) {
                plugin.getSnapshotManager().exitBuildMode(onlineTarget);
            }
            onlineTarget.sendMessage(ChatColor.RED + "[Shipyard] Your build access to '" + rental.getPlotId() + "' was revoked.");
        }
    }

    private void trustedList(CommandSender sender) {
        if (!requirePlayer(sender)) return;
        Optional<Rental> rentalOpt = plugin.getRentalManager().getRentalFor(((Player) sender).getUniqueId());
        if (rentalOpt.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "You don't have an active rental.");
            return;
        }
        Rental rental = rentalOpt.get();
        if (rental.getTrusted().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Nobody trusted in '" + rental.getPlotId() + "' yet.");
            return;
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Trusted in '" + rental.getPlotId() + "':");
        for (UUID uuid : rental.getTrusted()) {
            sender.sendMessage(ChatColor.GRAY + "- " + offlineName(uuid));
        }
    }

    private org.bukkit.OfflinePlayer resolvePlayer(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return online;
        }
        org.bukkit.OfflinePlayer offline = plugin.getServer().getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline : null;
    }

    private String plot(Rental rental) {
        return rental.getPlotId();
    }

    // ---- players: finish ----

    private void finish(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.finish")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard finish <shipName>");
            return;
        }
        Player player = (Player) sender;
        String shipName = args[1].replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (shipName.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "That ship name isn't valid, letters/numbers only.");
            return;
        }

        Optional<Rental> rentalOpt = plugin.getRentalManager().getRentalFor(player.getUniqueId());
        if (rentalOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "You don't have an active rental to finish.");
            return;
        }
        Rental rental = rentalOpt.get();
        Optional<Plot> plotOpt = plugin.getPlotManager().get(rental.getPlotId());
        if (plotOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Your plot no longer exists.");
            return;
        }
        Plot plot = plotOpt.get();

        sender.sendMessage(ChatColor.GRAY + "Scanning your plot and pricing the build...");
        SchematicService.Priced priced;
        try {
            priced = plugin.getSchematicService().captureAndPrice(plot);
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Couldn't read that plot: " + e.getMessage());
            return;
        }
        if (priced.blockCount == 0) {
            sender.sendMessage(ChatColor.RED + "That plot is empty - nothing to finish yet.");
            return;
        }

        String schemPath;
        try {
            schemPath = plugin.getSchematicService().save(priced.clipboard, player.getUniqueId(), shipName);
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to save schematic: " + e.getMessage());
            plugin.getLogger().warning("Schematic save failed for " + player.getName() + ": " + e);
            return;
        }

        ShipListing listing = new ShipListing(player.getUniqueId(), shipName, schemPath, priced.totalPrice, priced.blockCount);
        plugin.getListingManager().add(listing);

        sender.sendMessage(ChatColor.GREEN + "'" + shipName + "' finished! " + priced.blockCount
                + " blocks, priced at " + plugin.getEconomyService().format(priced.totalPrice) + ".");
        sender.sendMessage(ChatColor.GRAY + "Run /shipyard shop to claim it as a StructureBox.");

        if (priced.unmatchedBlockCount > 0) {
            sender.sendMessage(ChatColor.YELLOW + "" + priced.unmatchedBlockCount + " of " + priced.blockCount
                    + " block(s) had no entry in worth.yml and priced at the default instead"
                    + (priced.unmatchedExamples.isEmpty() ? "." : " - e.g. "
                    + String.join(", ", priced.unmatchedExamples) + "."));
        }
        double multiplier = plugin.getConfig().getDouble("Finish Price Multiplier", 1.0);
        if (priced.rawMaterialWorth > 0 && multiplier <= 0) {
            sender.sendMessage(ChatColor.YELLOW + "" + "Raw material worth was "
                    + plugin.getEconomyService().format(priced.rawMaterialWorth)
                    + ", but 'Finish Price Multiplier' is " + multiplier
                    + " in config.yml, so the final price came out to " + (multiplier < 0 ? "negative/" : "") + "zero.");
        }

        if (plugin.getConfig().getBoolean("Clear Plot On Finish", true)) {
            plugin.evictAllFromPlot(rental);
            plugin.getSchematicService().clearPlot(plot);
            plugin.getRentalManager().end(plot);
            sender.sendMessage(ChatColor.GRAY + "The plot has been cleared and freed up for the next rental.");
        }
    }

    private void shop(CommandSender sender) {
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.shop")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        plugin.getShopGui().open((Player) sender);
    }

    private void delist(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.shop")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard delist <shipName>");
            return;
        }
        Player player = (Player) sender;
        var listingOpt = plugin.getListingManager().get(player.getUniqueId(), args[1]);
        if (listingOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "You don't have a listing named '" + args[1] + "'.");
            return;
        }
        plugin.getListingManager().remove(listingOpt.get());
        sender.sendMessage(ChatColor.GREEN + "'" + listingOpt.get().getShipName()
                + "' pulled off your shop. The saved schematic file itself isn't deleted, "
                + "so re-running /shipyard finish with the same name relists it.");
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Optional<Plot> plotOpt = plugin.getPlotManager().get(args[1]);
            if (plotOpt.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No such plot.");
                return;
            }
            Plot plot = plotOpt.get();
            Optional<Rental> rental = plugin.getRentalManager().getRental(plot.getId());
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Plot " + plot.getId() + ": " + plot.getPricePerDay() + "/day, world " + plot.getWorld());
            if (rental.isPresent()) {
                long msLeft = rental.get().getExpiresAtMillis() - System.currentTimeMillis();
                sender.sendMessage(ChatColor.GRAY + "Rented by " + offlineName(rental.get().getRenter())
                        + ", " + (msLeft > 0 ? TimeUnit.MILLISECONDS.toHours(msLeft) + "h left" : "expired"));
            } else {
                sender.sendMessage(ChatColor.GREEN + "Currently free.");
            }
            return;
        }
        if (!requirePlayer(sender)) return;
        Optional<Rental> rental = plugin.getRentalManager().getRentalFor(((Player) sender).getUniqueId());
        if (rental.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "You don't have an active rental.");
            return;
        }
        long msLeft = rental.get().getExpiresAtMillis() - System.currentTimeMillis();
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "You rent plot '" + rental.get().getPlotId() + "', "
                + (msLeft > 0 ? TimeUnit.MILLISECONDS.toHours(msLeft) + "h left" : "expired"));
    }

    private void reset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shipyardrental.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shipyard reset <id>");
            return;
        }
        Optional<Plot> plotOpt = plugin.getPlotManager().get(args[1]);
        if (plotOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No such plot.");
            return;
        }
        Plot plot = plotOpt.get();
        plugin.getRentalManager().getRental(plot.getId()).ifPresent(plugin::evictAllFromPlot);
        plugin.getSchematicService().clearPlot(plot);
        plugin.getRentalManager().end(plot);
        sender.sendMessage(ChatColor.GREEN + "Plot '" + plot.getId() + "' cleared and freed.");
    }

    private void appraise(CommandSender sender) {
        if (!requirePlayer(sender)) return;
        if (!sender.hasPermission("shipyardrental.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        Player player = (Player) sender;

        com.sk89q.worldedit.regions.Region selection;
        try {
            var weSession = plugin.getWorldEditPlugin().getSession(player);
            var weWorld = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getWorld());
            selection = weSession.getSelection(weWorld);
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Make a WorldEdit selection first (//wand, then //pos1 and //pos2).");
            return;
        }

        sender.sendMessage(ChatColor.GRAY + "Pricing your selection...");
        SchematicService.PriceReport report = plugin.getSchematicService().appraiseRegion(selection, player.getWorld());

        if (report.blockCount == 0) {
            sender.sendMessage(ChatColor.RED + "That selection is empty - nothing to price.");
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "Selection worth: "
                + plugin.getEconomyService().format(report.totalPrice) + " (" + report.blockCount + " blocks).");

        if (report.unmatchedBlockCount > 0) {
            sender.sendMessage(ChatColor.YELLOW + "" + report.unmatchedBlockCount + " of " + report.blockCount
                    + " block(s) had no entry in worth.yml and priced at the default instead"
                    + (report.unmatchedExamples.isEmpty() ? "." : " - e.g. "
                    + String.join(", ", report.unmatchedExamples) + "."));
        }
        double multiplier = plugin.getConfig().getDouble("Finish Price Multiplier", 1.0);
        if (report.rawMaterialWorth > 0 && multiplier <= 0) {
            sender.sendMessage(ChatColor.YELLOW + "" + "Raw material worth was "
                    + plugin.getEconomyService().format(report.rawMaterialWorth)
                    + ", but 'Finish Price Multiplier' is " + multiplier + " in config.yml.");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("shipyardrental.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return;
        }
        plugin.reloadConfig();
        plugin.getWorthService().load();
        for (Plot plot : plugin.getPlotManager().all().values()) {
            plugin.getPlotManager().applyPriority(plot);
            plugin.getPlotManager().clearExplicitBuildFlag(plot);
        }
        sender.sendMessage(ChatColor.GREEN + "Reloaded config.yml and worth.yml, and re-applied "
                + "region priority to all " + plugin.getPlotManager().all().size() + " plot(s).");
        double multiplier = plugin.getConfig().getDouble("Finish Price Multiplier", 1.0);
        if (multiplier <= 0) {
            sender.sendMessage(ChatColor.YELLOW + "" + "'Finish Price Multiplier' is " + multiplier
                    + " - every /shipyard finish will price at zero (or negative) from here on.");
        }
    }

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        sender.sendMessage(ChatColor.RED + "Players only.");
        return false;
    }

    private String offlineName(UUID uuid) {
        String name = plugin.getServer().getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString();
    }
}
