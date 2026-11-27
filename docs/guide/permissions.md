# Permissions and policy

English · [Русский](permissions.ru.md)

The agent can do everything an operator can, and more. One file, `config/almighty/policy.toml`, decides how much of
that it uses, for whom and where. It is written with comments on first start. Edit it and run `/almighty reload`, or
ask the game master to change it ([below](#changing-the-policy-by-asking)). A file with a mistake is refused with the
exact error, and the previous policy stays in force.

## Roles

Every player and every agent has one of five roles. Each includes everything below it.

| Role | Who has it without a permission plugin | What it allows by default |
|---|---|---|
| owner | Players listed in `owners` (verified), the console | Everything: scripts, rules with scripts, any command |
| admin | Operators of level 3 or 4 | Look at other players, creative bots, undo anyone's changes, read the audit log, decide requests above a player's rights |
| builder | Operators of level 2 | Keep areas loaded, add rules without scripts, run scripts the owner approved |
| member | Every verified player on a friends' server, operators of level 1 | Build, give themselves items, bots in survival, speak to everyone, write world notes |
| guest | Every other verified player on a public server, every unverified player | Look at the world, read notes, get private answers, run commands at their own operator level |

Commands always go through vanilla's own permission checks: a request runs its commands at the requester's own
operator level, or at the level of their role if that is higher (owner 4, admin 3, builder 2, member and guest 0),
never above the agent's ceiling. Unasked, the agent runs commands at the level of its floor.

Owners are listed by UUID, never by name. Add one with `/almighty owner add <player>` in the console (the player must
have joined once). `/almighty role <player> <role> [duration]` gives a role for a while, for example
`/almighty role Alex builder 3h`; admins may give only roles below their own, and nobody can give owner this way.

### Verified players

`identity` in `policy.toml` sets which players count as verified:

| Value | Verified |
|---|---|
| `auto` (default) | Players with a Mojang account UUID on an online-mode server, and Bedrock players through Floodgate |
| `mojang` | The same, without Floodgate |
| `proxy` | As `auto`, for a server behind Velocity with modern forwarding (its own `online-mode` is off) |
| `offline` | Everyone: you vouch for every name, for example on an offline LAN among friends |

With `auto` or `mojang` on a server with `online-mode=false`, nobody is verified, because any client can claim any
UUID there. An unverified player is a guest whatever their role or a permission plugin says.

## Presets

`preset` sets what an agent does when nobody asked: its own initiative, or text from players it reads in chat.

| Preset | Unasked, an agent acts as | For |
|---|---|---|
| `private` | Its ceiling (below) | A server where only owners talk to the agents |
| `friends` | A member | A server among people you know. A fresh install starts here |
| `public` | A guest | A server open to anyone |

A request from a player is different: the agent attaches the ticket of that player's message, and the call runs with
that player's rights. A ticket only narrows: it never gives more than the agent's ceiling.

## Agents: ceiling and floor

```toml
[agents.gm]
ceiling = "owner"

[agents.codex]
ceiling = "admin"
floor = "builder"
```

- **ceiling** is the most an agent may ever do, whoever asks. Unlisted agents have `admin`; the default agent `gm`
  has `owner` in the template.
- **floor** is what it may do unasked. Without it the preset decides; it is never above the ceiling.

### Your own app

`/almighty approve <code> <name> owner` adds `ceiling = "owner"` for that app. On a friends' or public server the app
still acts unasked as a member or a guest, because player text can reach it through the event feed. When you ask in
game with `/gm`, your request carries your owner rights. To give the app your rights for everything, add
`floor = "owner"` under its `[agents.<name>]` and run `/almighty reload`; do that only for an app you watch, since
players' chat can then lead it to act with your rights.

## Capabilities

Each capability is a permission node `almighty.<name>`. Without a permission plugin each one answers from the role
ladder at its default role.

| Node | Default role | Allows |
|---|---|---|
| `almighty.knowledge` | guest | Read notes, the server catalog and the event feed |
| `almighty.observe.world` | guest | Look at the world: status, maps, views, blocks, entities, jobs |
| `almighty.speak.reply` | guest | Answer players privately |
| `almighty.command` | guest | Run commands and functions at the requester's own operator level |
| `almighty.policy` | guest | Read and propose changes to Almighty's own files |
| `almighty.notes.write` | member | Write world notes |
| `almighty.speak.all` | member | Speak to everyone, titles |
| `almighty.build` | member | Build, and undo one's own builds |
| `almighty.give.self` | member | Give items to the requester |
| `almighty.bots` | member | Bots in survival or adventure |
| `almighty.area` | builder | Keep areas loaded |
| `almighty.rules` | builder | Rules without scripts |
| `almighty.scripts.run` | builder | Run scripts the owner approved |
| `almighty.observe.players` | admin | Look at other players: where they are and what they see |
| `almighty.bots.admin` | admin | Creative or spectator bots, client packets, skins |
| `almighty.undo.any` | admin | Undo other agents' and players' changes |
| `almighty.audit` | admin | Read the audit log |
| `almighty.approve` | admin | Decide players' commands above their rights (`ask_admin`) |
| `almighty.engine` | owner | Scripts and rules with scripts: code in the server's Java process |

### LuckPerms

With LuckPerms installed it decides these nodes for players, for example
`/lp group default permission set almighty.build false`. On Fabric, LuckPerms is reached through
fabric-permissions-api, which LuckPerms provides. An unverified player stays a guest whatever a plugin grants.
Without a permission plugin, roles come from the ladder above and `/almighty role`.

## Confirmations

A player's request to run a command runs at once when every command in it is harmless or aimed only at the requester:

- harmless: `time`, `weather`, `say`, `me`, `msg`, `tell`, `w`, `teammsg`, `tm`, `list`, `locate`, `playsound`,
  `stopsound`, `particle`, `help`, `trigger`, `seed`, `setblock`, `fill`, `clone`, `place`, `fillbiome`;
- only at the requester (`@s`, their name or UUID): `tp`, `teleport`, `give`, `effect`, `clear`, `gamemode`, `xp`,
  `experience`, `enchant`, `kill`, `spawnpoint`, `title`, `ride`, `attribute`, `item`, `advancement`, `recipe`.

Anything else (another player as a target, any mod command, `data`, `summon`, `function`, `ban` and so on) waits for
the requester to confirm. The server shows them the exact call with `[Allow]` and `[Deny]`; only that player can
answer, within 2 minutes. At most 3 requests wait per player and 20 per agent.

`[safety]` adjusts this:

```toml
[safety]
ask_admin = ["kick"]       # these may go to an admin when the player lacks the right
confirm_first = ["tp"]     # these always need the requester's confirmation
```

A request in `ask_admin` above the player's rights goes to admins who hold what it needs (for a command,
`almighty.approve`) as one quiet chat line
at most every 10 minutes; they see it with `/almighty inbox`. An approved request runs once with that admin's rights.
Empty `ask_admin` (the default) means such requests are simply refused.

## Zones

```toml
[zones.spawn]
dim = "overworld"
from = [-64, -64, -64]
to = [64, 320, 64]
deny = ["build", "area"]

[zones.arena]
from = [5000, -64, 5000]
to = [5400, 320, 5400]
agent_may = ["build", "bots"]
```

- `deny` (any of `build`, `area`, `bots`, `bots.admin`, `command`) refuses those capabilities inside the box for
  everyone below the owner. A command is checked where it runs and at every coordinate it names; commands that move
  where they run (`execute as`, `at`, `positioned`, `in`) are refused while any zone denies `build` or `command`.
  Entity selectors are not checked.
- `agent_may` (any of `build`, `area`, `bots`, `bots.admin`) lets the agent use those capabilities on its own inside
  the box even when its floor would not: its own arena.
- `dim` defaults to the Overworld.

## Quotas

```toml
[quotas]
build_blocks = 50000
give_items = 128
```

How many blocks one player's requests may build, and how many items they may be given, per hour. Owners have no
quota; the agent's own builds share one budget.

## Claims

With Open Parties and Claims or FTB Chunks installed, a build below the owner runs only on chunks the requesting
player may build in; an unasked build only on unclaimed chunks.

## Content limits

Below the owner, whatever the policy says:

- builds place plain block states only (no block entity data), and never a block in the tag `#almighty:never_build`:
  command blocks, structure and jigsaw blocks, spawners, barrier, light, bedrock, portals, end portal frames and
  reinforced deepslate;
- `give` hands the requester at most one stack of a plain item, never an item in `#almighty:never_give` (command
  blocks, structure blocks, barrier, light, debug stick, spawners, bedrock and the like) nor a spawn egg;
- chat text and commands may not carry click actions that run or suggest commands, nor the `[Almighty]` prefix the
  server uses for its own prompts.

Datapacks may add entries to both tags.

## Rules made for players

A rule without a script (conditions on an event and actions: cancel, write to the feed, say, run a command) belongs to
whoever asked for it. Its creator's rights are checked again at every event: it cancels only where they may build,
runs commands as them, stops when they lose `almighty.rules`, and expires after at most 7 days. Rules made by owners
have no time limit.

Scripts in `config/almighty/scripts/<name>.groovy` run by name for anyone with `almighty.scripts.run` once you approve
them with `/almighty script approve <name>`, and only until the file changes. An approved script runs with full
rights: it is your code.

## Changing the policy by asking

The game master can read and edit `policy.toml` (and, for owners only, scripts in `config/almighty/scripts/`):

- **Your request** (you are an owner and ask in game with `/gm`) applies at once.
- **The agent's own proposal** applies at once only if it restricts. Anything that grants more (a more open preset,
  a new owner, a higher ceiling or floor, a narrower deny zone, a wider arena, larger quotas) waits for a holder of
  `almighty.engine` to allow it in game; you see a summary of the change.
- **A player's request** to change the policy is refused.

Every write is checked first; the previous file goes to `config/almighty/policy.history/` (50 kept), and
`/almighty policy undo` puts it back. Raising an agent to an owner ceiling is done only in the file or with
`/almighty approve <code> <name> owner`, which the console and level-4 operators can type: give level 4 only to
people you trust as owners.
