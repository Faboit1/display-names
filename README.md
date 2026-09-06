# DisplayNames

Replaces the vanilla username plate above every player with a **multi-line `TextDisplay`**
that supports **MiniMessage** formatting and **PlaceholderAPI** placeholders.

Formats *and* appearance can be switched per player by permission or by **TAB-style
conditions**.

Built for **Folia**, and runs unchanged on **Paper**.

```yaml
nametag:
  lines:
    - "<yellow>%luckperms_prefix%<white>%player_name% <#ffffde>%luckperms_suffix%"
    - "<green>%vault_eco_balance%<white>$"

refresh-interval: 10   # ticks

offset:
  x: 0.0
  y: 2.5               # blocks above the player's feet
  z: 0.0
```

## Requirements

| | |
|---|---|
| Server | Folia or Paper 1.21.8+ |
| Java | 21 |
| PlaceholderAPI | optional — MiniMessage still works without it |

## Building

```bash
mvn clean package     # -> target/DisplayNames-<version>.jar
```

Drop the jar in `plugins/` and restart. `config.yml` is written on first start.

## How it works

The design is driven by two constraints: it has to be correct under Folia's regionised
threading, and it has to stay cheap with hundreds of players online.

**One entity per player.** The tag is a single `TextDisplay`; extra lines are newlines inside
one component, not extra entities. Text is only re-sent when the rendered string actually
changes, so a tag that says the same thing costs nothing to keep saying it.

**Mounted, not positioned — and that is a trade, not a win.** There is no anchoring that is
right on both counts, so `display.anchor` picks which artefact you'd rather have.

`MOUNT` (the default) makes the tag a passenger of the player. The *client* carries a passenger,
so the tag is welded to the player at any speed — sprinting, elytra, horse, boat — and the server
never moves it at all. The cost is geometric: a passenger attaches at vanilla's mount anchor
(chest height, `height × 0.75` = 1.35), so the remaining height has to be a transformation, and a
display's billboard rotates its *transformation* along with it. That keeps the offset pointing up
on the **screen** rather than up in the **world**, so looking steeply down at someone slides their
tag off to one side. Below about 40° of pitch you will not notice; past 60° you will.
`billboard: VERTICAL` removes it outright — that billboard has no pitch for an offset to swing on,
and despite the name the text still faces you square-on and still reads left-to-right ("vertical"
is the axis it spins around).

`FOLLOW` is the geometrically exact one: the entity is *positioned* above the head with the
transformation left at zero, so a `CENTER` billboard pivots about the text itself and the tag is
dead centre above the head from every angle. It pays for that by having the server move it, once
per `follow-interval`, from a player position that is already a tick old — so it trails and
jitters when the player is moving. Fine standing still, visibly wrong on an elytra.

Positioning uses `teleportAsync`, the only form region threading allows — the synchronous
`Entity#teleport` throws rather than blocking, which on a per-tick follow loop means one stack
trace per player per tick and a tag stranded where it spawned. If a move ever does fail, that tag
falls back to riding its player and the cause is logged once for the whole server. A build-time
guard (`FoliaApiGuardTest`) fails the build if the synchronous form, `BukkitScheduler` or
`BukkitRunnable` reappears in main sources.

**Region-local by construction.** Each player's upkeep runs on that player's
`EntityScheduler`. Under Folia that *is* the thread that owns the player, the tag and the
placeholder lookups, so no work is ever dispatched to a foreign region and no per-player
state needs a lock. Work spreads itself across region threads for free. The two entry points
callable from elsewhere (`requestRefresh`, `requestRespawn`) hop onto that thread first.

**Do nothing whenever possible.** In order of how much they save:

- A format with no `%placeholder%` in it is parsed **once at config load** and never again.
- A dynamic format is resolved, then compared against the last resolved string. Text is only
  re-sent when it genuinely changed — a rank that never changes costs one string compare per
  refresh, forever.
- Placeholders are not evaluated **at all** for players nobody is close enough to see. This
  is the big one on a large server, where a single expansion can mean a database round trip.
  See below.
- Parsed components are cached by resolved string, so everyone sharing a rank shares one
  MiniMessage parse.
- Refreshes are staggered across the interval so they never all land on the same tick.

`/dn status` reports how many updates were sent versus avoided.

### The viewer grid

Answering "can anyone see this player?" by scanning the online player list would be O(n) per
refresh, O(n²) per interval across the server. Instead every player keeps a counter in a
coarse grid cell, and the question is answered by summing the 3×3 neighbourhood around them:
nine map lookups, short-circuited as soon as one viewer is found, independent of player count.

The cell size is clamped to at least the tag's render distance, so a neighbourhood always
covers a full render distance in every direction. The answer is therefore a safe
over-approximation — it can report a viewer who is slightly too far away (harmless, the tag
just refreshes anyway) but never misses one who can actually see the tag.

Every mutation is a single atomic `ConcurrentHashMap` operation, so region threads update it
concurrently without synchronising. Turn it off with
`performance.skip-updates-without-viewers: false`.

## Configuration

Everything below lives in `config.yml`, which is commented in full.

### Formats

`nametag.lines` is the default. Each list entry is one line, parsed with
[MiniMessage](https://docs.advntr.dev/minimessage/format.html) — `<yellow>`, `<#ffffde>`,
`<gradient:black:white>`, `<bold>`, `<rainbow>` and so on.

`%placeholders%` are resolved by PlaceholderAPI when it is installed. Legacy colour codes
coming *out* of a placeholder (LuckPerms prefixes, Vault, chat plugins) are converted to
MiniMessage automatically — see `text.legacy-colors`. Placeholders that already contain
MiniMessage work too.

### Conditions

Named per-player tests, using **TAB's condition grammar** — conditions copied out of a TAB
config work here unchanged. A condition can drive text *and* settings: it gates a whole
profile, so it picks the offset, scale, billboard and view range too, not just the format.

```yaml
conditions:
  is_afk:
    conditions:
      - "%essentials_afk%=true"
    true: "<gray>[AFK] "
    false: ""

  low_health:
    type: OR                       # AND is the default
    conditions:
      - "%player_health% < 6"
      - "%player_is_on_fire%"
    true: "<red>!"
    false: ""
```

| Operator | |
|---|---|
| `>=` `>` `<=` `<` | numeric; a side that is not a number never matches |
| `=` `!=` | equals / does not equal |
| `<-` `!<-` | contains / does not contain |
| `\|-` `!\|-` | starts with / does not start with |
| `-\|` `!-\|` | ends with / does not end with |
| `permission:<node>` | holds the permission |
| `!permission:<node>` | does not hold it |

Either side may be a placeholder, or a literal, or both. A check that is nothing but
`%placeholder%` passes when it resolves to `true`, and `!%placeholder%` is its opposite. Text
comparisons **ignore case** here, which is the one deliberate difference from TAB.

Three shorthands save a block: a bare list is an `AND`, a bare string is a single check, and a
whole condition fits on one line by joining checks with `;` for AND or `|` for OR.

```yaml
conditions:
  in_the_nether:
    - "%player_world%=world_nether"
  is_staff: "permission:displaynames.staff"
  afk_civilian: "%essentials_afk%=true;!permission:displaynames.staff"
```

`%condition:name%` inserts a condition's `true:` or `false:` output, and works anywhere a
placeholder does — inside `nametag.lines`, inside a profile's lines, and inside another
condition's output:

```yaml
nametag:
  lines:
    - "%condition:is_afk%<white>%player_name% %condition:low_health%"
```

Conditions may reference each other; a reference loop is found and dropped at **startup**, and
a runtime depth cap backs it up, because a stack overflow on a Folia region thread would take
the region with it. A condition nothing points at is never evaluated, so declaring one costs
nothing until it is used.

`/dn debug <player>` prints every declared condition's live verdict for that player, which is
usually faster than guessing at a placeholder that is not resolving.

### Profiles: per-player formats and settings

The first profile — highest `priority` — that the player matches wins. Players matching
nothing get `nametag.lines`.

A profile is gated by a `permission`, a `condition`, or both; it needs at least one, and when
it has both, both must pass. `condition` takes the name of a condition declared above,
`%condition:name%`, or an inline expression.

```yaml
profiles:
  admin:
    permission: "displaynames.profile.admin"
    priority: 100
    lines:
      - "<gradient:#ff5555:#ffaa00><bold>ADMIN</bold></gradient>"
      - "<white>%player_name%"
```

`lines` are optional. A profile without them keeps `nametag.lines` and exists purely to change
**settings** — a bigger, glowing tag while a player is in combat, a higher one while they are
riding something:

```yaml
profiles:
  combat:
    condition: "%combat_time% > 0"
    priority: 200
    display:
      scale: 1.25
      glow-color: "#ff5555"
```

A profile that would change nothing (no lines *and* no appearance override) or that would apply
to everybody (no permission *and* no condition) is refused at load with a warning, rather than
quietly replacing every nametag on the server.

Profiles ship **commented out**, and every profile permission is registered at startup with a
default of `false`. Both matter: Bukkit resolves a permission nobody has declared as
`PermissionDefault.OP`, so an undeclared profile node is held by *every operator* — which
silently hands admins the highest-priority profile and overrides the nametag they configured.
Grant profile nodes explicitly in your permission plugin, the same as any other node.

### Position and appearance

`offset.y` is measured in blocks **from the player's feet**, so `2.5` sits just above the
head of a 1.8-block-tall player. If every tag sits at the wrong height — a resource pack or a
plugin changing player height will do it — `offset.mount-anchor` (default `1.35`, vanilla's
`height * 0.75`) tunes where a passenger attaches.

The `display` section exposes every property a `TextDisplay` has:

| Key | |
|---|---|
| `anchor` | `MOUNT` (rides the player: smooth, drifts at steep angles) or `FOLLOW` (positioned above the head: exact, lags in motion) — see above |
| `follow-interval` | ticks between position updates; `FOLLOW` only |
| `billboard` | `CENTER` / `VERTICAL` / `HORIZONTAL` / `FIXED` — which axes turn to face the viewer |
| `rotation` | `yaw` / `pitch` / `roll` in degrees, for the axes the billboard does *not* follow the viewer on |
| `scale` | one number, or a `x`/`y`/`z` block |
| `background` | `mode: CUSTOM \| CLIENT \| NONE`, plus `color` and `opacity` (0–255) |
| `text-opacity` | 0–255 on the glyphs themselves |
| `text-shadow` | drop shadow on the glyphs |
| `see-through` | render through terrain |
| `alignment` | `LEFT` / `CENTER` / `RIGHT` |
| `line-width` | wrap width in pixels |
| `view-range` | `1.0` ≈ 64 blocks |
| `brightness` | `enabled`, `block` and `sky` light levels (0–15) |
| `entity-shadow` | `radius` and `strength` of the shadow cast on the ground |
| `culling` | `width` / `height` of the off-screen test box; `0` never culls |
| `glow-color` | `#RRGGBB` outline, or `none` |
| `interpolation` | `delay`, `duration`, `teleport-duration` |

`rotation` is the only thing that aims a fixed tag: the entity is always spawned facing due
south, so a tag never inherits the direction its owner happened to be looking when it was
created. It only matters when the billboard is not `CENTER`, and each mode ignores the axis it
follows the viewer on: `VERTICAL` uses pitch and roll, `HORIZONTAL` uses yaw and roll, `FIXED`
uses all three. Yaw follows Minecraft's own convention — 0 faces south, 90 west, 180 north,
270 east.

Lowering `view-range` is the cheapest way to reduce client-side cost on a crowded server:
`0.75` renders tags to ~48 blocks instead of ~64.

A profile may override `offset` and any part of `display`; anything it does not mention is
inherited from the global block. Changing a player's profile rebuilds their entity, because
appearance is baked in when the entity is created.

### Hiding

Tags are **see-through by default**, so they stay readable underground and behind walls.
Sneaking or drinking an invisibility potion drops them back to line-of-sight only rather than
hiding them outright — `see-through-while-sneaking` and `see-through-while-invisible` control
that, and `hide-while-sneaking` / `hide-while-invisible` hide the tag entirely instead. Because
see-through is a live entity property, switching it is a metadata flip, not a respawn.

`visibility` controls the rest: `hide-from-self` (on by default — the tag
would otherwise float in the wearer's face in first person),
`hide-in-spectator`, `hide-while-vanished` (reads the standard
`vanished` metadata used by Essentials, CMI and SuperVanish) and `disabled-worlds`.

`visibility.hide-condition` hides the tag whenever a condition passes — the same grammar as
above, either a declared name or an inline expression. It is checked last, after everything
else here, and only when it is set.

```yaml
visibility:
  hide-condition: "%player_world%=creative_plots"
```

### Removing the vanilla username plate

`visibility.hide-vanilla-nametag` handles this, and it is worth knowing why it is not a
one-liner. The only lever without packet manipulation is a scoreboard team with
`NAME_TAG_VISIBILITY = NEVER` — but a team only affects the plates a player sees if it lives on
**the scoreboard that player is currently viewing**. Any sidebar, tab or scoreboard plugin calls
`setScoreboard` and moves players off the main scoreboard, at which point a team registered only
there is invisible to them and every vanilla nametag comes back.

So DisplayNames sweeps every scoreboard actually in use, not just the main one, and repeats on a
timer because those plugins rebuild their scoreboards constantly.

| `mode` | |
|---|---|
| `ADOPT` *(default)* | Leave existing team membership alone; switch off nametag visibility on whatever team the player is already in. Compatible with tab-list sorting, which is usually driven by team names. Use this if you run a tab or sidebar plugin. |
| `TEAM` | Put every player into our own team. Self-contained, but a player can only be in one team, so this fights anything else that uses them. |
| `NONE` | Do nothing; keep vanilla nametags. |

`team-name` (max 16 characters) names the team used for players who are in none.
`reassert-interval` is how often the sweep repeats, in ticks (default `20`, one second); `0`
applies once at startup and on join only. It has to *win a race* against rank and tab plugins that
rebuild their teams continuously, so it is deliberately frequent. If a plugin keeps undoing it,
the console says so after ten contested sweeps in a row, and `/dn status`'s **teams adopted**
stays non-zero every sweep. Teams adopted from other plugins have their previous visibility restored when
DisplayNames disables.

If plates are still showing, `/dn status` reports how many scoreboards the last sweep reached,
how many players it covered, and the last error if there was one.

## Commands

`/displaynames`, aliased `/dn` and `/nametags`.

| Command | Permission | |
|---|---|---|
| `/dn reload` | `displaynames.command.reload` | Re-read `config.yml` and rebuild every tag |
| `/dn refresh [player\|*]` | `displaynames.command.refresh` | Force a re-render |
| `/dn status` | `displaynames.command.status` | Runtime counters |

| `/dn cleanup` | `displaynames.command.cleanup` | Remove stray nametags left in the world |
| `/dn debug` | `displaynames.command.debug` | Report why placeholders or plates misbehave |

`displaynames.admin` (default: op) grants all of them.

There is deliberately **no per-player opt-out** — not by command, not by permission. Every online
player in an enabled world gets a tag, and `visibility.disabled-worlds` is the only thing that
stops it.

An earlier version had a `displaynames.hidden` permission. It sat in the same namespace as the
command nodes, so any staff rank granted `displaynames.*` — the usual way admin gets set up —
silently lost its nametag. A switch whose only effect is to make tags disappear is not worth the
ways it can be tripped by accident.

`/dn debug` is the one to reach for when something looks wrong: it prints the active resolver,
PlaceholderAPI's version, the raw template beside the resolved string, and the scoreboard and
team the player is actually on with that team's nametag visibility.

## Notes and limits

- **The tag is one entity, shown identically to everyone.** Placeholders are resolved for the
  player wearing it, which is what `%luckperms_prefix%`, `%vault_eco_balance%` and friends
  are for. Per-viewer text (relational placeholders, "your rank vs. mine") would need
  per-viewer packets and is out of scope.
- **Passengers are ejected by death, world changes and teleports.** Those are all hooked, and
  the periodic pass re-mounts anything that slips through, so the worst case is one refresh
  interval without a tag.
- Tags are non-persistent and never written to region files. Any that somehow survive an
  unclean shutdown carry a marker and are swept when their chunk's entities load.
- If a placeholder returns broken MiniMessage, the line falls back to plain text and logs
  once rather than throwing every refresh.

## Development

```bash
mvn test      # 66 tests
```

The parts worth testing are pure Java and covered without a server: legacy-code conversion
(including hex runs and malformed input), placeholder detection and component caching, the
viewer grid's bit-packing, negative-coordinate handling, neighbourhood coverage guarantee and
concurrent updates, and the whole `display` config surface — inheritance, background modes,
opacity encoding, brightness clamping, the yaw convention, and that an un-overridden profile
inherits the *same instance* so it does not needlessly rebuild entities.

## Continuous integration

`.github/workflows/build.yml` runs on every push to `main`, every pull request,
and on demand from the Actions tab. It has three jobs.

**`build`** compiles and tests on Temurin 21 with a cached Maven repository, then
uploads the plugin jar as a workflow artifact named `DisplayNames-jar` — that is
the download link on the run's summary page. Failing runs also upload the
surefire reports so a red build can be diagnosed without reproducing it locally.

**`automerge`** squash-merges the pull request and deletes its branch once the
build is green. Two things hold a pull request back:

- **Draft status.** Drafts build but never merge — draft is the "not ready"
  signal, so marking a pull request ready for review is what arms the merge.
- **The `no-automerge` label**, for holding back a pull request that is ready
  but should wait for a human.

Fork pull requests never auto-merge.

**`autofix`** runs only when the build fails, and asks Claude to fix it, commit
and push. It attempts this **once per human push** — if the last commit on the
branch is already an automated fix, it stops and leaves the failure for a person,
so a fix that does not work cannot loop.

### Turning autofix on

`autofix` needs an `ANTHROPIC_API_KEY` repository secret
(*Settings → Secrets and variables → Actions*). Without one it does nothing but
write a note on the run summary explaining why — the rest of the workflow is
unaffected.

Auto-merging also needs *Settings → Actions → General → Workflow permissions* set
to **Read and write permissions**. If it is not, the build still passes and the
merge step fails with a summary saying exactly that.
