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
   empty. See "Why decorative data is allowlisted, not blocklisted" below for
   why that split exists and how it's implemented.
6. **`/shipyard shop`** opens a GUI of the player's own finished, priced ships.
   Clicking one withdraws the price via Vault and runs StructureBoxes'
   `/structurebox create <name>` *as that player* to hand them the finished
   ship as a placeable StructureBox item. Players are never granted
   StructureBoxes' `structureboxes.create` permission as a standing grant -
   see "How the StructureBoxes handoff works" below for why that matters.

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

## Why decorative data is allowlisted, not blocklisted

`SchematicService` captures each block one of two ways:

- **Bare block state** (type + orientation, no NBT) - the default for
  everything. This is what makes a finished chest, furnace, hopper, dispenser,
  dropper, shulker box, barrel, brewing stand, jukebox, lectern, or spawner
  paste back in empty: their contents live in NBT, not block state, so it's
  simply never read off them.
- **Full block including NBT** - only for materials matching `SIGN`,
  `BANNER`, `HEAD`, or `SKULL` (as a name suffix, so it covers every
  wood/color/wall/hanging variant automatically). That's what carries sign
  text, banner patterns, and skull skins into the schematic without a rewrite
  every time a ship gets bought.

This is deliberately an *allowlist* of "known safe to preserve" rather than a
blocklist of "known dangerous to preserve," for the same reason the command
restrictions in `BuildModeGuardListener` are an allowlist: a blocklist has to
be perfectly complete to be safe, and Minecraft keeps adding new block types -
Chiseled Bookshelves (1.20) hold real books, for instance. A blocklist authored
before that update shipped would have missed it and started leaking real
items through finished schematics. An allowlist just doesn't grant NBT to
anything until someone deliberately adds it here, so a missed block type fails
safe (pastes empty) instead of failing open (leaks an item).

If you ever add a block family to `isDecorativeNbtType` in `SchematicService`,
the thing to verify first is that it can never hold an item under any
circumstance - not "doesn't currently," but structurally can't.

## Exploit prevention: keeping creative items out of survival

The inventory swap only protects the player's *own inventory container*.
Anything that lets an item leave that container into something the swap
doesn't touch is a duplication vector. `BuildModeGuardListener` closes the
three most obvious ones:

- **Ender chests** are vanilla, per-player storage that exists independently
  of any block, so it isn't wiped when the plot resets. Opening one (by
  command like `/enderchest`/`/echest`, or by physically placing and
  right-clicking one) is blocked outright while a build session is active.
- **Any other command** is blocked unless it's on `Build Mode Allowed
  Commands` in `config.yml` (just `/shipyard` and its aliases by default).
  This is deliberately an *allowlist*, not a blocklist of known-bad plugins
  like PlayerVaults' `/pv` - there's no way to enumerate every storage, bank,
  kit, or trade command that might exist on your server (now or after a
  future plugin update), so instead nothing runs mid-session except what you
  explicitly permit.
- **Dropping items** is blocked entirely (`Block Item Drop In Build Mode`),
  which closes the simplest exploit of all: toss a stack over the plot
  boundary for an accomplice standing just outside to walk over and grab -
  WorldGuard's `BUILD` flag doesn't govern item pickup, so this needed its
  own check.

Teleports (ender pearls, `/tpa`, warps, chorus fruit) are also watched
alongside normal walking, so a player can't sidestep the exit swap entirely by
teleporting out of the plot instead of walking out.

**A separate one, found during play-testing: plot-clearing itself could leak
container contents.** The schematic capture never includes what's inside a
chest/furnace/hopper/etc in the first place (see `captureAndPrice`'s NBT
handling above) - but `SchematicService.clearPlot`, which runs right after
`/shipyard finish` (and on `/shipyard reset` and the expiry sweep), used to
just overwrite every block's type directly. Doing that to a container still
triggers Minecraft's own "container removed" handling under the hood, which
spills its contents as item entities on the ground - and since the player is
switched back to survival *before* the plot gets cleared, they'd be standing
right there to pick them up. This has nothing to do with what made it into
the schematic; it's a completely separate leak in the cleanup step, and one
that can't be caught with a normal Bukkit event listener, since no
block-break event fires for a plugin-initiated block swap. `clearPlot` now
empties any `InventoryHolder` block's inventory (chests, furnaces, hoppers,
dispensers, droppers, shulker boxes, brewing stands, barrels, lecterns, and
any future block Mojang adds that holds items the same way - checked broadly
rather than enumerated, same reasoning as everywhere else in this doc) plus
jukeboxes as a special case, before ever changing the block itself.

**Still worth play-testing / not fully closed here:**
- **Hoppers/pistons piercing the plot boundary.** WorldGuard's `BUILD` flag
  governs *player* actions, not block-to-block automation - a hopper placed
  at the very edge of the plot (which the renter, as a region member, is
  allowed to place) can push items into a chest sitting just outside the
  boundary in unclaimed territory, with no player action to intercept.
  Consider banning hoppers/droppers inside shipyard plots entirely, or a
  dedicated anti-hopper-piercing plugin, if this matters on your server.
- **Trade/backpack/kit plugins that build a custom UI without going through
  Bukkit's `Inventory` API** won't fire `InventoryOpenEvent` and so won't be
  caught by that specific check - they'd still be blocked by the command
  allowlist if triggered by a command, but not if triggered by, say, an NPC
  right-click.
- **Giving items directly to another player** standing at the plot boundary
  (if any plugin/vanilla mechanic allows a direct player-to-player transfer)
  isn't specifically covered.

## How the StructureBoxes handoff works

Giving a player `structureboxes.create` as a standing permission would let
them run `/structurebox create <any schematic on the server>` themselves,
completely bypassing the worth calculation and Vault charge - a free item
duplication/creation exploit. So players never get that permission normally.

Instead, when a player clicks "buy" in `/shipyard shop`, `ShopGui` grants
`structureboxes.create` to that player via a `PermissionAttachment` scoped to
just that one `dispatchCommand` call, then immediately removes it. Bukkit's
command dispatch is synchronous on the main thread, so there's no window for
the player to sneak in a different `/structurebox create` call while they
transiently hold the permission.

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
Maven Central for FAWE) need to be reachable from wherever you build - they
weren't reachable from the sandbox this was written in, so **this project has
not actually been compiled here**. The exact Purpur API version string
(`26.2.build.2618-stable`) is a best-effort match for "Purpur 26.2-2618"
rather than one confirmed against a live build - if it 404s, check
`https://repo.purpurmc.org/snapshots` for the closest available version and
update `pom.xml`. Read through the rest (especially `SchematicService` and
`PlotManager`, which touch the most WorldEdit/WorldGuard API surface) before
trusting it in production, and fix up anything that's shifted if you're on
different WorldGuard/FAWE versions than 7.0.18 / 2.15.3.

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

## If a renter shows as region owner but still can't build

This bit us during live testing, so it's worth documenting properly rather
than leaving the outdated theory in here. Two separate WorldGuard issues can
produce the exact same "Hey! Sorry, but you can't place that block here"
symptom with a correctly-listed owner, and it took a full debugging session
(complete with WorldGuard's own `/wg debug testplace` simulator) to tell them
apart:

**1. Priority, if another region overlaps the same spot.** Being a region's
owner only bypasses *that specific region's* flags. If any other region also
covers the same location - a spawn-protect zone, a world-wide "claim
everything by default" region, anything - and the renter isn't a member of
*that* region too, its `build: deny` still wins unless the shipyard region
outranks it on priority. The plugin sets `Plot Region Priority` (default
`10`, see `config.yml`) on every region it creates for exactly this reason.
This turned out not to be the actual problem in testing, but it's a real
failure mode worth ruling out with `/rg setpriority <region id> 10` if you
hit this.

**2. An explicit `build: DENY` flag can block the owner too.** This was the
actual bug, and it's counterintuitive enough to call out directly: a
*freshly created* WorldGuard region already denies build to non-owners with
zero flags set - that's the built-in protection every new region gets, and it
comes bundled with an implicit exemption for the region's own owners and
members. This plugin used to explicitly set `build: DENY` on every region it
created, on the (very common, very wrong in this case) assumption that doing
so was redundant with that default and harmless. It isn't. On this server's
WorldGuard/FAWE setup, explicitly setting the flag overrode the owner
exemption and applied to *everyone*, owner included - confirmed by testing
with two accounts side by side: the owner, correctly listed in `/rg info`,
was denied exactly like a random non-member, and clearing the flag (leaving
it unset) immediately fixed the owner while correctly continuing to block
everyone else.

The fix: `PlotManager` no longer sets `build` at all, on new regions or on
rent/extend. It relies entirely on the default protection new regions ship
with. This self-heals existing plots too - automatically on the next rent or
extend, or immediately for every plot via `/shipyard reload`.

For a plot that's stuck **right now** on an already-built jar, clear it
directly:

```
/rg flag <region id> build
```

(no value after `build` - that clears the flag rather than setting it to
`allow`.) e.g. `/rg flag shipyard_yard1 build` for the plot from this
debugging session.

**If neither of those is it**, the most useful next step isn't more guessing -
it's WorldGuard's own diagnostic tool, which tells you definitively which
plugin (if any) is actually responsible:

```
/wg debug testplace -t <player>
```

Run by someone else *targeting* the stuck player by name (the `<player>`
argument is whose permissions get tested, not the command-runner's - critical,
because if the stuck player is made op just to get the
`worldguard.debug.event.*` permission needed to run this themselves, WorldGuard
bypasses all of its own checks for ops and the test becomes meaningless). Read
only the **first** line of the plugin-reaction list; WorldGuard prints
last-run-first, so that's the one whose decision actually stuck. And make sure
whatever block/location you're testing is actually inside the region's bounds
- an off-by-one on the Y coordinate (testing the floor block one layer below
where the region starts) will get "no plugin cancelled it" every time, which
looks like a false negative but is really just testing the wrong spot.

## If ships keep pricing at 0 (or suspiciously low)

`/shipyard finish` now tells you directly when this happens - it reports how
many blocks had no `worth.yml` entry and priced at `Default Block Worth`
(0.0) instead, with a few example material names. If that number is small,
you're probably just missing a handful of entries. If it's *every* block on
every ship regardless of what's built, the cause is almost always this:

**`worth.yml` only gets (re)written to disk if it doesn't already exist.**
`WorthService.load()` intentionally never overwrites an existing
`plugins/ShipyardRental/worth.yml` - that's correct behavior for not
clobbering an admin's customizations, but it means updating the plugin jar
alone does **not** refresh a `worth.yml` that already exists from an earlier
install, even if the bundled default has since changed completely. Check the
server log on startup/`/shipyard reload` for `"Loaded N material price(s)
from worth.yml"` - if N is 0 or far smaller than expected, that's confirming
this. Delete (or manually replace the contents of) the on-disk file and
`/shipyard reload` to pick up the current bundled default.

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
see "How the StructureBoxes handoff works" above.

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
