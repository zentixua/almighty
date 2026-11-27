# Commands

English · [Русский](commands.ru.md)

`<…>` is required, `[…]` optional. "Console" includes RCON.

## For every player

| Command | What it does |
|---|---|
| `/gm <text>` | A private message to the game master. Other players and operators do not see it. |
| `/gm @<agent> <text>` | The same, to one agent when several are connected. |
| `/almighty whoami` | Your UUID, whether the server counts it as verified, and your role. |
| `/almighty confirm <id>`, `/almighty deny <id>` | Answer a confirmation the server asked you for. Clicking `[Allow]` or `[Deny]` in chat types these for you; only the player asked can answer. |
| `/almighty undo <change>` | Undo a change the game master made at your request. The receipt in chat has an `[Undo]` button. |

Players can also address the game master in public chat by its name (`feed.names`); see
[Game master mode](game-master.md).

## For admins

| Command | Who | What it does |
|---|---|---|
| `/almighty inbox` | Holders of `almighty.approve`, the console | Requests above a player's rights that wait for an admin (`ask_admin`), with `[Allow]` and `[Deny]`. |
| `/almighty role <player> <role> [duration]` | Admins, the console | Give a role (`guest`, `member`, `builder`, `admin`) below your own, for example `/almighty role Alex builder 3h` (`30m`, `2d`, `1h30m`). Without a duration, until cleared. |
| `/almighty role <player> clear` | Admins, the console | Take a given role back. |

## For operators of level 4 and the console

| Command | What it does |
|---|---|
| `/almighty connect` | The server's MCP address, the line to add it in each app, and links for apps that have install links. |
| `/almighty approve` | List waiting sign-ins (app and address, never the code). |
| `/almighty approve <code> [<name>] [owner]` | Approve the sign-in showing that code, optionally under a name of your choice; `owner` gives the app your rights as its ceiling ([permissions](permissions.md#your-own-app)). |
| `/almighty deny <code>` | Refuse a waiting sign-in. |
| `/almighty agents` | Every agent with its last call and address. |
| `/almighty reload` | Reread `policy.toml` and the agent token files. A file with a mistake is refused and the previous policy stays. |
| `/almighty pause [agent]` | The red button: that agent, or every agent, may only look. Its builds wait, its bots stop, rules made below the owner stop. |
| `/almighty resume [agent]` | Undo the pause. |
| `/almighty log [agent] [count]` | The latest audit entries (10 by default, up to 100). |
| `/almighty undo <change>` | Undo any change; the agent lists them with the `changes` method. Positions changed since are left as they are. |

## For owners

These need the console, or a verified owner from `policy.toml` who is also a level-4 operator.

| Command | What it does |
|---|---|
| `/almighty owner add <player>`, `/almighty owner remove <player>` | Edit `owners` in `policy.toml`. The player must have joined once. |
| `/almighty agent add <name>` | A new agent with a token in `config/almighty/agents/<name>.token`, for scripts and CI ([headless](connect/headless.md)). The token is never printed. |
| `/almighty agent remove <name>` | End an agent: its token stops working at once. |
| `/almighty revoke <name>` | Replace an agent's token; the old one stops working at once. |
| `/almighty script approve <name>` | Let agents with `almighty.scripts.run` run `config/almighty/scripts/<name>.groovy` by name, until the file changes. |
| `/almighty script revoke <name>`, `/almighty script list` | Withdraw an approval; list approved scripts. |
| `/almighty policy undo` | Put back the `policy.toml` saved before the last change. |
