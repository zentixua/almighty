# Game master on Claude Code

This kit runs the game master of a server as one Claude Code session with all its tools. The world is reached
through the MCP adapter [`../almighty.py`](../almighty.py); this directory adds the rest. Whatever belongs to one
server (its CLAUDE.md, skills, recipes, session settings) lives in that session's working directory.

| Part | File | How it is connected |
|---|---|---|
| Chat channel | `almighty-chat/`, a Claude Code plugin in the `almighty` marketplace (this directory) | `--channels plugin:almighty-chat@almighty` |
| Crash guard hook | `crash_guard.py` | PreToolUse on `mcp__almighty__.*` in the session's `.claude/settings.json` |
| Recipes | `recipes.py`: format and runner | a command from the server's skill |

## Chat channel

The channel ([channels](https://code.claude.com/docs/en/channels-reference)) reads the bridge's event feed and wakes
the session when a player addresses the game master:

- a private `/gm` message;
- a public chat message that names the game master, even with stretched letters or, for long names, one typo;
- after that, the player's messages without the name for 3 minutes after they last addressed the game master or got
  an answer;
- any message from a player who is alone on the server.

The rest of the public chat and rule errors are not forwarded on their own: up to 10 recent lines come with the next
message to the game master. Joins, leaves, deaths, rules that were turned off, stalls of 2 s or more (at most once a
minute) and bridge restarts are forwarded as they happen.

Permission requests of the session go to the owners as private messages in the game; an owner answers
`/gm yes <id>` or `/gm no <id>` (`y`, `n`, `да` and `нет` work too). The plugin's hooks (`hooks/hooks.json`, type
http, port 25680) tell the channel when the game master replies in chat and when its turn ends. Until then, the
player who is waiting sees a grey line above the hotbar.

Settings are environment variables of the session:

| Variable | Meaning |
|---|---|
| `ALMIGHTY_URL`, `ALMIGHTY_TOKEN_FILE` | The bridge, as for the adapter. |
| `GM_OWNERS` | Comma-separated owner names: they get permission requests and their answers count. |
| `GM_LANG` | Language of what players see from the plugin: `en` (default) or `ru`. It sets the default names below, the line above the hotbar and the permission request. |
| `GM_NAMES` | Names that address the game master in public chat, compared after lower-casing and folding repeated letters. |
| `GM_TYPO_NAMES` | Names that are also recognised with one typo; meant for long names that no other word is one letter away from. |
| `GM_BOT_PLAYERS` | Bots to listen to as if they were players, for testing. |

With `GM_LANG=en`, the game master answers to `claude` and `claud`, and the line above the hotbar is "the game
master is thinking…". With `GM_LANG=ru`, the default names are Russian and Ukrainian words for "game master"
and "Claude" in Cyrillic and Latin letters, and the texts are in Russian.

## Crash guard hook

`crash_guard.py` is a [hook](https://code.claude.com/docs/en/hooks). It denies, with a reason, only what has already
crashed or frozen a server (the list and where each entry comes from are in the file), and asks the owner before
`op`, `deop`, `whitelist`, `ban`, `ban-ip`, `pardon`, `pardon-ip` and `kick`. Subagents do not talk to players: the
hook denies their `say`, chat commands and bot chat. If the hook itself fails, the call is denied, not let through.

## Recipes

A recipe is a way of doing something that has worked in the game, written down so it can be repeated without
improvising: the steps are bridge calls, and the check is Groovy run in the world afterwards. Each step goes through
the crash guard as a subagent call would, so recipes neither talk to players nor do what needs the owner's approval.
Recipes use the bridge like the adapter does (`ALMIGHTY_URL`, `ALMIGHTY_TOKEN_FILE`); for a test server, point them
at its address and token.

```sh
uv run --script mcp/gm/recipes.py list ~/gm/recipes
uv run --script mcp/gm/recipes.py run ~/gm/recipes ship_stop '{"ship": "Grand"}'
```

A recipe is a `.md` file in the recipe directory:

````markdown
---
name: clear_weather
description: Clear weather (one line: when to use it)
kind: instant
params: {"seconds": {"type": "int", "min": 60, "max": 7200, "default": 1200}}
---
What it does, the pitfalls, how to tell that it worked.

```steps
[{"method": "command", "params": {"commands": ["weather clear ${seconds}"]}}]
```

```check
return !server.overworld().isRaining()
```
````

- `name` matches the file name and uses `a-z`, `0-9` and `_`.
- `kind`: `instant` takes seconds; `task` takes longer and is run in the background; `show` leaves a rule running in
  the game. `task` and `show` need a `check`.
- Header lines are `key: value`, where the value is JSON or a string. Parameter types: `int`, `number`, `bool`,
  `name`, `block`, `text`.
- Steps call the bridge methods listed in `ALLOWED` in `recipes.py`; `say` is not among them. `${name}` is a
  parameter: a whole string becomes the value with its type, inside a string it is inserted as text.
- `check` is Groovy that returns `true` or `{ok: true, ...}` when the recipe worked.
- Long Groovy code, such as a script or an autopilot rule, goes into a named block ```` ```groovy <name> ```` and is
  referenced from a step as the whole string `"${code:<name>}"`. In Groovy, `${name}` of a declared parameter is
  replaced with its value; any other `${...}` is left to Groovy.

## Install

```sh
(cd mcp/gm/almighty-chat && npm ci)
claude plugin marketplace add /path/to/almighty/mcp/gm && claude plugin install almighty-chat@almighty
```

The channel is not on the official allowlist. A managed policy can allow it (`allowedChannelPlugins` with the
`almighty` marketplace and the `almighty-chat` plugin), or the session can be started with
`--dangerously-load-development-channels`. The hook goes into `.claude/settings.json` of the working directory:

```json
{"hooks": {"PreToolUse": [{"matcher": "mcp__almighty__.*", "hooks": [
  {"type": "command", "command": "python3 /path/to/almighty/mcp/gm/crash_guard.py", "timeout": 30}]}]}}
```

Start the session with `claude --mcp-config mcp.json --channels plugin:almighty-chat@almighty`, where `mcp.json`
runs the adapter (`almighty.py mcp`) under the name `almighty`. The channel needs Node.js 20 or newer.

## Tests

`python3 -m unittest discover -s mcp/gm/tests` tests the hook and the recipes (CI runs it on every pull request).
`node --test` in `almighty-chat/` tests who a chat message is addressed to and when the game master's turn ends.
CI runs both suites and checks the syntax of `mcp/almighty.py` on every pull request.
