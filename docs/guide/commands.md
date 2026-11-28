# Commands

English · [Русский](commands.ru.md)

`<…>` is required, `[…]` optional. "Console" includes RCON. Chat buttons such as `[Allow]` and `[Undo]` follow the
player's game language.

## For every player

| Command | What it does |
|---|---|
| `/gm <text>` | A private message to the game master. Other players and operators do not see it; you see it echoed as "To Almighty: <text>" in your game language. |
| `/gm @<agent> <text>` | The same, to one agent when several are connected. |
| `/almighty whoami` | Your UUID, whether the server counts it as verified, and your role. |
| `/almighty confirm <id>`, `/almighty deny <id>` | Answer a confirmation the server asked you for. Clicking `[Allow]` or `[Deny]` in chat types these for you; only the player asked can answer. |
| `/almighty show <id>` | The exact call behind a question you were asked (the same as `[Details]`). Admins who may decide it and the console can use it too. |
| `/almighty undo <change>` | Undo a change the game master made at your request. Clicking `[Undo]` in the receipt runs it. |

Players can also address the game master in public chat by its name (`feed.names`); see
[Game master mode](game-master.md).

## For admins

| Command | Who | What it does |
|---|---|---|
| `/almighty inbox` | Admins who hold what a request needs (for commands, `almighty.approve`), the console | Requests above a player's rights that wait for an admin (`ask_admin`), with `[Allow]` and `[Deny]`. |
| `/almighty role <player> <role> [duration]` | Admins, the console | Raise a verified player to a role (`member`, `builder`, `admin`) below your own; it never lowers what the preset or operator level gives, for example `/almighty role Alex builder 3h` (`30m`, `2d`, `1h30m`). Without a duration, until cleared. |
| `/almighty role <player> clear` | Admins, the console | Take a given role back. |

## For operators of level 4 and the console

| Command | What it does |
|---|---|
| `/almighty connect` | The server's MCP address, the line to add it in each app, and links for apps that have install links. |
| `/almighty approve` | List waiting sign-ins (app and address, never the code). |
| `/almighty deny <code>` | Refuse a waiting sign-in (codes are four digits; anything else is answered as a confirmation). |
| `/almighty agents` | Every agent with its last call and address. |
| `/almighty reload` | Reread `policy.toml` and the agent token files. A file with a mistake is refused and the previous policy stays. |
| `/almighty pause [agent]` | The red button: that agent, or every agent, may only look and clean up (cancel jobs, release areas, remove rules and bots). Its builds wait and its bots stop. With a name every rule that agent made stops too; without a name every rule with a script and every rule made below the owner. |
| `/almighty resume [agent]` | Undo the pause. |
| `/almighty log [agent \| player] [count]` | The latest audit entries (10 by default, up to 100): all, one agent's calls, or the calls made for one player's requests, at the server's local time (`timezone` in `policy.toml`). |
| `/almighty undo <change>` | Undo any change by its number, shown in receipts and the audit log. Positions changed since are left as they are. |

## For owners

These need the console, or a verified owner from `policy.toml` who is also a level-4 operator. On a server whose
`policy.toml` lists no owner, a verified level-4 operator may type `/almighty approve <code> …` and becomes the owner.

| Command | What it does |
|---|---|
| `/almighty approve <code> [<name>]` | Approve the sign-in showing that code, optionally under a name of your choice. On a new server (no owner and no owner's app yet) the app gets your rights and players' `/gm`; after that, admin is the most the app may do. |
| `/almighty owner add <player>`, `/almighty owner remove <player>` | Edit `owners` in `policy.toml`. The player must have joined once. The last owner cannot be removed. |
| `/almighty approve <code> <name> owner` | Approve a sign-in as your own app: the app gets your rights as its ceiling ([permissions](permissions.md#your-own-app)). |
| `/almighty approve <code> <name> owner <player>` | Console only: the same, and that player (who must have joined once) becomes an owner. |
| `/almighty agent add <name>` | A new agent with a token in `config/almighty/agents/<name>.token`, for scripts and CI ([headless](connect/headless.md)). The token is never printed. |
| `/almighty agent remove <name>` | End an agent: its token stops working at once. |
| `/almighty revoke <name>` | Replace an agent's token; the old one stops working at once. |
| `/almighty script approve <name>` | Let agents with `almighty.scripts.run` run `config/almighty/scripts/<name>.groovy` by name, until the file changes. |
| `/almighty script revoke <name>`, `/almighty script list` | Withdraw an approval; list approved scripts. |
| `/almighty policy undo` | Put back the `policy.toml` saved before the last change made through the game master. Owner commands and hand edits are not saved in the history, so an undo discards later ones too. |
