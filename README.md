# DisplayNames

Replaces the vanilla username plate above every player with a **multi-line `TextDisplay`**
that supports **MiniMessage** formatting and **PlaceholderAPI** placeholders.

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
mvn clean package     # -> target/DisplayNames-1.0.0.jar
```

Drop the jar in `plugins/` and restart. `config.yml` is written on first start.

## How it works

The design is driven by two constraints: it has to be correct under Folia's regionised
threading, and it has to stay cheap with hundreds of players online.

**One mounted entity per player.** The tag is a single `TextDisplay` added as a *passenger*
of the player. The client interpolates a passenger's position itself, so the server never
teleports the tag and never sends a movement packet for it — the only packet a nametag ever
costs after spawning is a metadata update when its text actually changes. Extra lines are
newlines inside one component, not extra entities.

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

### Per-permission formats

The first profile — highest `priority` — whose `permission` the player has wins. Players
matching nothing get `nametag.lines`.

```yaml
profiles:
  admin:
    permission: "displaynames.profile.admin"
    priority: 100
    lines:
      - "<gradient:#ff5555:#ffaa00><bold>ADMIN</bold></gradient>"
      - "<white>%player_name%"
```

### Position and appearance

`offset.y` is measured in blocks **from the player's feet**, so `2.5` sits just above the
head of a 1.8-block-tall player. If a resource pack or a plugin changes player height and the
tag ends up misaligned, `offset.mount-anchor` (default `1.35`) tunes where a passenger
attaches; it is not in the default file because it is rarely needed.

The `display` section covers `billboard`, `scale`, `background`, `text-opacity`, `shadow`,
`see-through`, `alignment`, `line-width`, `view-range` and `full-brightness`.

Lowering `view-range` is the cheapest way to reduce client-side cost on a crowded server:
`0.75` renders tags to ~48 blocks instead of ~64.

### Hiding

`visibility` controls when a tag is suppressed: `hide-from-self` (on by default — the tag
would otherwise float in the wearer's face in first person), `hide-while-sneaking`,
`hide-while-invisible`, `hide-in-spectator`, `hide-while-vanished` (reads the standard
`vanished` metadata used by Essentials, CMI and SuperVanish) and `disabled-worlds`.

`hide-vanilla-nametag` removes the built-in username plate. The only way to do this without
packet manipulation is a scoreboard team with `NAME_TAG_VISIBILITY = NEVER`, so **turn it off
if another plugin already puts players in teams** — a player can only belong to one. The team
is unregistered when the plugin disables, restoring vanilla nametags.

Players with `displaynames.hidden` never get a nametag.

## Commands

`/displaynames`, aliased `/dn` and `/nametags`.

| Command | Permission | |
|---|---|---|
| `/dn reload` | `displaynames.command.reload` | Re-read `config.yml` and rebuild every tag |
| `/dn refresh [player\|*]` | `displaynames.command.refresh` | Force a re-render |
| `/dn toggle [player]` | `displaynames.command.toggle` | Hide or show one player's tag |
| `/dn status` | `displaynames.command.status` | Runtime counters |

`displaynames.admin` (default: op) grants all four. `/dn toggle` is not persisted across
restarts — use the `displaynames.hidden` permission for a permanent opt-out.

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
mvn test      # 28 tests over the colour converter, template compiler and viewer grid
```

The parts worth testing are pure Java and covered without a server: legacy-code conversion
(including hex runs and malformed input), placeholder detection and component caching, and
the viewer grid's bit-packing, negative-coordinate handling, neighbourhood coverage guarantee
and concurrent updates.

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
