# ShipyardRental

A Paper/Spigot plugin for a "rent a plot, build your ship, cash it out as a
StructureBox" workflow.

## What it does

1. **Admins define plots** dynamically: make a WorldEdit selection (`//wand`,
   `//pos1`, `//pos2`) over an empty area, then `/shipyard define <id> <pricePerDay>`.
   This creates a WorldGuard region `shipyard_<id>` with `build: DENY` for everyone
   by default.
2. **Players rent a plot**: `/shipyard rent <id> <days>` charges `pricePerDay * days`
   via Vault and makes the player the sole owner of that plot's WorldGuard region. By
   default each player can only actively rent one plot at a time (`Max Rentals Per
   Player` in config.yml) - a player holding a `shipyardrental.rent.<N>` permission
   (e.g. `shipyardrental.rent.4`) gets a cap of N instead, whichever such permission
   they hold is highest, rather than stacking on top of the default. Everyone still
   just needs the base `shipyardrental.rent` permission (default true) to rent at all;
   the numbered ones only raise the cap for whoever's specifically been granted one.
   `/shipyard tp [id]` teleports to a rented/trusted plot - your own if you don't give
   an id, or a specific one if you do (or if you rent none but are trusted into more
   than one, in which case you need to specify which).
3. **Walking into the plot** (only for the current renter) saves their real
   inventory + GameMode to disk, clears their inventory, and switches them to
   creative (configurable). **Walking out** restores everything and puts them back
   in survival. Anyone else just gets blocked from building by WorldGuard's `DENY` flag.
4. Renters can come and go, and extend the rental (`/shipyard extend <id> <days>`)
   as many times as needed while they finish the build.
5. **`/shipyard finish <shipName>`** scans every block in the plot, prices it
   against `worth.yml` (material -> price per block), saves it as a `.schem`
   file into WorldEdit's schematic folder (in the same per-player subfolder
   StructureBoxes itself looks in), and lists it in that player's shop. By
   default the plot is then cleared and freed for the next renter
   (`Clear Plot On Finish` in config.yml).

   The saved schematic is trimmed to the tightest bounding box that actually
   contains a block, not the full plot cuboid - a plot is usually much bigger
   than the ship built inside it, and StructureBoxes checks a structure's
   *dimensions* (not just its block count) both against its max-size limit
   and when scanning for a clear spot to paste, so shipping the plot's unused
   air along for the ride would make every ship look artificially enormous by
   both of those measures.

   The ship's floor is also saved to land `Schematic Vertical Offset` blocks
   (default `1`) above wherever the StructureBox ends up placed, rather than
   flush with it - otherwise the ship's bottom layer would land right on top
   of (and overwrite) the box itself. This works by saving the clipboard's
   *origin* one block below its actual lowest block while keeping it aligned
   to the same X/Z corner, which is the reference point StructureBoxes'
   `pasteClipboard` uses to compute where the structure lands relative to the
   clicked block - see `WorldEditHandler.pasteClipboard` in StructureBoxes'
   own source if you want the exact math.

   Signs, banners, and heads/skulls keep their real data (text, patterns,
   skins) in the saved schematic. Everything else - chests, barrels,
   furnaces, hoppers, dispensers, droppers, shulker boxes, brewing stands,
   jukeboxes, lecterns, spawners, and any future block that turns out to hold
   an item - is captured as bare block type only, so it always pastes back in
   empty. See [docs/DESIGN_NOTES.md](docs/DESIGN_NOTES.md#why-decorative-data-is-allowlisted-not-blocklisted)
   for why that split exists and how it's implemented.
6. **`/shipyard shop`** opens a GUI of the player's own finished, priced ships.
   Clicking one withdraws the price via Vault and runs StructureBoxes'
   `/structurebox create <name>` *as that player* to hand them the finished
   ship as a placeable StructureBox item. Players are never granted
   StructureBoxes' `structureboxes.create` permission as a standing grant -
   see [docs/DESIGN_NOTES.md](docs/DESIGN_NOTES.md#how-the-structureboxes-handoff-works)
   for why that matters.

   Buying doesn't remove the listing - a finished ship is a design a player
   can buy as many copies of as they want, not a one-off. `/shipyard delist
   <shipName>` pulls a listing off the shop if a player wants to retire a
   design; the saved `.schem` file itself isn't deleted, so re-running
   `/shipyard finish` with the same name later relists it.
7. **`/shipyard trust <player>`** lets the current renter add another player
   as a trusted helper: they're added as a WorldGuard region *member* (can
   build, can't touch region flags/ownership) and get the same
   inventory-save / creative-mode treatment as the renter when they walk in
   and out. `/shipyard untrust <player>` revokes it (and immediately restores
   their real inventory if they're online and mid-session). `/shipyard trusted`
   lists who's currently trusted.
8. **`/shipyard appraise`** (admin) prices whatever's inside the admin's
   current WorldEdit selection - any shape, not just a cuboid, and not tied
   to a shipyard plot at all. Select an area anywhere on the map (an already-
   built ship, someone's base, whatever) and get a "what's this worth"
   readout using the exact same `worth.yml` + `Finish Price Multiplier`
   pricing `/shipyard finish` uses under the hood, so the two numbers can
   never quietly drift apart from having separate pricing logic. Useful for
   sanity-checking `worth.yml` itself, or pricing something without needing
   to put it through a full rent-and-build cycle first.

See [docs/DESIGN_NOTES.md](docs/DESIGN_NOTES.md) for the reasoning behind the
safety-relevant design decisions above: why decorative block data (signs,
banners, heads/skulls) is allowlisted rather than blocklisted, the full list
of exploit-prevention measures in `BuildModeGuardListener`, and how the
StructureBoxes purchase handoff avoids granting players a standing
duplication-capable permission.

## Requirements

- Purpur 26.2 (Java 25+) - a Paper fork, so plain Paper/Spigot work too, just update
  the `pom.xml` server API dependency back to `paper-api`/`spigot-api` if you're not
  on Purpur specifically
- [FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit) 2.15.x -
  vanilla WorldEdit works too, just swap the `pom.xml` dependency back to
  `worldedit-core`/`worldedit-bukkit`; no Java source changes needed either way since
  FAWE republishes the same `com.sk89q.worldedit.*` classes
- [WorldGuard](https://enginehub.org/worldguard) 7.0.x
- [Vault](https://www.spigotmc.org/resources/vault.34315/) + any economy plugin
  Vault supports (EssentialsX Economy, etc.)
- [StructureBoxes](https://github.com/eirikh1996/StructureBoxes) 4.x - **not a
  hard dependency**, but `/shipyard shop` purchases silently fail (and refund)
  without it.

## Building

```
mvn clean package
```

The dependencies (`repo.purpurmc.org`, `maven.enginehub.org`, `jitpack.io`, and
Maven Central for FAWE) need to be reachable from wherever you build.
This project has been built successfully and bug-tested on a live Purpur
26.2 server, including a manual exploit-hunting pass over the item-duping
concerns described above. Two features are still pending live testing:
`/shipyard tp` and the per-player rental cap (`Max Rentals Per Player` /
`shipyardrental.rent.<N>`, which defaults to one active rental per player).
Treat those two specifically as unverified until confirmed.

The built jar lands in `target/ShipyardRental.jar` - a fixed filename on
purpose (not versioned) - drop that into your server's `plugins/` folder
alongside WorldEdit, WorldGuard, Vault, and StructureBoxes, **replacing**
whatever's there already.

**Swapping the jar file requires a full server restart, not `/reload` or
`/shipyard reload`** - Bukkit/Paper/Purpur load a plugin's compiled code once
at startup, so replacing the file on disk while the server keeps running has
no effect until the next actual restart; `/shipyard reload` only re-reads
`config.yml`/`worth.yml`, not the jar itself. `/version ShipyardRental` is
only a reliable way to check you're on the current build if the version
number in `pom.xml` actually changes between builds - bump it (e.g.
`1.1.0` -> `1.1.1`) whenever you rebuild locally, or that command will keep
reporting the same string regardless of which jar is actually loaded.

## Troubleshooting

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) for detailed
walkthroughs of two issues hit during live testing: a renter showing as
region owner but still unable to build, and ships pricing at 0 (or
suspiciously low).

## Permissions

| Permission | Default | Does |
|---|---|---|
| `shipyardrental.admin` | op | define/remove/reset/reload plots, `/shipyard appraise` |
| `shipyardrental.admin.bypass` | op | exempt from the inventory/gamemode swap, and from the rental cap |
| `shipyardrental.rent` | true | rent a plot at all - see `Max Rentals Per Player` for how many at once |
| `shipyardrental.rent.<N>` | (unset) | tiered: raises this player's rental cap to N instead of the config default |
| `shipyardrental.teleport` | true | `/shipyard tp` to your own rented/trusted plot |
| `shipyardrental.trust` | true | trust another player into your own rented plot |
| `shipyardrental.finish` | true | price up and save a build |
| `shipyardrental.shop` | true | open the ship shop GUI, and `/shipyard delist` |

Players do **not** need StructureBoxes' own `structureboxes.create` permission -
see [docs/DESIGN_NOTES.md](docs/DESIGN_NOTES.md#how-the-structureboxes-handoff-works).

## Known limitations / things to double-check before relying on this

- **StructureBoxes purchase feedback is best-effort.** `Bukkit.dispatchCommand`
  only tells you a command handler ran, not that it actually succeeded (its
  `onCommand` returns `true` even on some failure paths). The temporary
  permission attachment guarantees the permission check passes, so a failure
  at this point most likely means the `.schem` file isn't where StructureBoxes
  expects it - check `Finish Price Multiplier` math and the save path in
  `SchematicService` if purchases keep refunding.
- **Plot geometry is a simple stored cuboid**, not a live link to the
  WorldGuard region - if you manually edit the region in WorldGuard later,
  update/redefine the plot too so the two stay in sync.
- **`SchematicService` reads/writes/clears plots block-by-block on the main
  thread.** Fine for typical shipyard-sized plots; for very large plots (tens
  of thousands of blocks) you may want to chunk this work across ticks the
  way StructureBoxes itself does for `Incremental placement`.
- **No safeguard against a plot overlapping another plot or existing builds**
  at define-time - it just trusts the admin's WorldEdit selection.
- Rentals persist through disconnects. If a player logs back in still
  standing inside a plot they're actually authorized to build in - the normal
  case for a server restart mid-session - `PlayerSessionListener.onJoin`
  leaves their stashed inventory alone and just re-asserts `Build GameMode`
  defensively, since no boundary-crossing event ever fires for "was already
  inside when they logged in." Only a genuinely orphaned snapshot (rental
  expired while offline, plot got reset, they ended up somewhere else
  entirely) triggers `Restore On Rejoin If Stuck Inside`'s force-restore to
  survival.
