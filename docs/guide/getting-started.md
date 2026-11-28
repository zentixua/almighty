# Getting started

English · [Русский](getting-started.ru.md)

From a jar to an AI agent acting as game master on your server, in four steps. You need a Minecraft server you can
restart and an AI agent app on a computer you control.

## 1. Install the mod

Download the jar for your server from the [releases](https://github.com/zentixua/almighty/releases/latest):

| Minecraft | Loader | Java | Jar |
|---|---|---|---|
| 1.21.1 | NeoForge 21.1.264 or newer | 21 | `almighty-<version>+1.21.1-neoforge.jar` |
| 26.1.2 | NeoForge 26.1.2.130 or newer | 25 | `almighty-<version>+26.1.2-neoforge.jar` |
| 26.1.2 | Fabric Loader 0.20.1 or newer, with [Fabric API](https://modrinth.com/mod/fabric-api) | 25 | `almighty-<version>+26.1.2-fabric.jar` |
| 26.3 | NeoForge 26.3.0.118 or newer | 25 | `almighty-<version>+26.3-neoforge.jar` |
| 26.3 | Fabric Loader 0.20.1 or newer, with [Fabric API](https://modrinth.com/mod/fabric-api) | 25 | `almighty-<version>+26.3-fabric.jar` |

Put it into the server's `mods/` folder and start the server. Players install nothing. When the server is ready, the
console says:

```
Almighty ready - tell your AI agent: "connect to my Minecraft server at http://127.0.0.1:25650/mcp" (all ways: /almighty connect)
```

The first start writes `config/almighty-common.toml` ([settings](configuration.md)) and
`config/almighty/policy.toml` ([permissions](permissions.md)). A fresh install is a friends' server: anyone may talk
to the game master, and on its own it acts as an ordinary member would.

The bridge starts only on a dedicated server. To try Almighty in a single-player or LAN world, set
`bridge.integrated = true` in `config/almighty-common.toml` of that game instance and reopen the world.

## 2. Tell your agent to connect

Say it in your agent app, in your own words:

```
Connect to my Minecraft server (Almighty) at http://127.0.0.1:25650/mcp
```

The agent adds the server to its own settings (most apps ask you to allow that once). Some apps need a new session
before the new server's tools appear. If your app cannot change its own settings, [its page](connect/README.md) has
the one line or the link to use instead; `/almighty connect` in the server console shows them too.

The address above works when the agent runs on the same machine as the server. For a server on a VPS or a hosting
panel, see [Hosting](hosting.md).

## 3. Approve the sign-in with its code

The first time the agent calls the server, your app opens a sign-in page in the browser. It shows a four-digit code
and the app's name. In the server console (or in the game as a level-4 operator), type:

```
/almighty approve 4821 claude-code owner
```

with the code from the page, a name for the app, and `owner` if it is your own app on your own computer. The page
returns to your app, and the agent is connected. Nobody copies a token or edits a file, and only someone who sees the
page and can type on the server can approve it.

| You type | The app gets |
|---|---|
| `/almighty approve <code>` | A name made from the app's own name, with admin as the most it may do: no script code of its own (only scripts you approved), and commands only up to operator level 3 |
| `/almighty approve <code> <name>` | The same, under the name you chose |
| `/almighty approve <code> <name> owner` | Your rights as the most it may do: `[agents.<name>] ceiling = "owner"` is added to `policy.toml` |

What an agent does unasked is limited further by the preset: on a friends' server it acts as a member, so it can
look, talk, build and drive bots, but runs commands only as an ordinary player could. A request you send in game with
`/gm <text>` carries your own rights. To give your own app your rights for everything it does, see
[Your own app](permissions.md#your-own-app).

Each app you approve becomes its own agent: `/almighty agents` lists them with their last call, and
`/almighty agent remove <name>` ends one.

## 4. Make yourself an owner

Operators are admins to Almighty, not owners: owners are listed by UUID in `policy.toml`, because a name alone can be
taken by anyone on an offline-mode server. Join the server once, then type in the console:

```
/almighty owner add <your player name>
```

Your requests in game (`/gm <text>`) then carry owner rights, and you can change the policy by asking the game
master. `/almighty whoami` shows your UUID, whether it is verified, and your role.

## What next

- Ask the agent to look around: "show me a map of spawn", "who is online", "what mods does this server have".
- Players talk to the game master with `/gm <text>` or by its name in chat. To have the agent answer them as they
  write, see [Game master mode](game-master.md).
- Teach it your server: "remember that the arena is at 5000 70 5000" goes into a world note that any agent on this
  server reads next time.
- Install the [Almighty plugin](../../plugin/almighty/README.md) for the game master skills in your app (optional).
- Set who may do what before you open the server to people you do not know: [Permissions](permissions.md),
  [Safety](security.md).

## Updating

Stop the server, replace the jar in `mods/` with the new one for the same Minecraft version and loader, and start
it. Your settings, policy, agents and world data stay. Read the release notes on the
[releases page](https://github.com/zentixua/almighty/releases) first.

## Optional mods

Almighty depends on no other mod. Its support for these loads only when they are installed:

| Mod | What it adds | Builds |
|---|---|---|
| [LuckPerms](https://luckperms.net/) | Grants the `almighty.*` permission nodes to players and groups ([permissions](permissions.md#luckperms)) | Every version and loader (on Fabric through fabric-permissions-api, which LuckPerms provides) |
| [Open Parties and Claims](https://modrinth.com/mod/open-parties-and-claims) | Below the owner, the game master never builds in a claim the requesting player may not build in | Every version and loader |
| [FTB Chunks](https://www.curseforge.com/minecraft/mc-mods/ftb-chunks-forge) | The same, for FTB Chunks claims | 1.21.1 and 26.1.2 |
| [Immersive Aircraft](https://modrinth.com/mod/immersive-aircraft) | Bots fly its aircraft with the controls a player uses | 1.21.1 and 26.1.2 |
| Sable (the physics library behind Create Aeronautics) | The agent reads ships: position, rotation, velocity, bounds; building on a ship's plot is refused | 1.21.1 |

Something did not work: [Troubleshooting](troubleshooting.md).
