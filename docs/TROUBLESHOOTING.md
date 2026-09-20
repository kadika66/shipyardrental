# Troubleshooting

See the main [README](../README.md) for setup and normal usage.

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
