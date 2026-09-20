# Design notes

Deeper background on why some of the safety-relevant code is shaped the way
it is. See the main [README](../README.md) for what the plugin does day to
day.

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
