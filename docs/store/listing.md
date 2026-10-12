# Store listing: Modrinth and CurseForge

Fields to paste into the project forms. The long description is [description.md](description.md).

## Name and summary

- **Title:** "Almighty: AI Game Master" (the name stays Almighty)
- **Slug:** `almighty` on Modrinth (free, checked 2026-10-12). On CurseForge `almighty` is taken by a 2023 Fabric
  modpack named "Almighty" (https://www.curseforge.com/minecraft/modpacks/almighty), so use `almighty-game-master`
  there; the title with "AI Game Master" keeps the two apart.
- **Summary** (206 of 256 characters):

  ```
  Server-side NeoForge mod that lets an AI agent run your Minecraft server as game master: it sees the world, follows events, runs commands, builds with undo, adds rules on game events and spawns bot players.
  ```

## Classification

| Field | Modrinth | CurseForge |
|---|---|---|
| Project type | Mod | Mod |
| Categories | Management (primary), Utility | Server Utility (primary), Utility & QoL |
| Loader | NeoForge | NeoForge |
| Game version | 1.21.1 | 1.21.1 |
| Environment | Client: unsupported. Server: required | Server |
| Dependencies | none required; optional: Immersive Aircraft, Sable | none required; optional: Immersive Aircraft |

The mod has no blocks, items or network packets, so clients never need it. Sable is published on Modrinth; check
whether it exists on CurseForge before linking it there. Check the category names against the forms: both sites
rename categories from time to time.

## License and links

- **License:** GPL-3.0-only (GNU General Public License v3.0 only). The jar bundles Groovy 4 under Apache 2.0.
- **Source:** https://github.com/zentixua/almighty
- **Issues:** https://github.com/zentixua/almighty/issues
- **Wiki:** none; the README on GitHub is the documentation.
- **Discord:** none.

## Files

- Upload `almighty-<version>.jar` from https://github.com/zentixua/almighty/releases (the first release after 0.3.1, the one with English texts).
- Release type: Release or Beta, owner's choice. Changelog: the matching `docs/releases/<version>.md`, which is in
  Russian; an English changelog has to be written for the first public upload.

## Gallery

No images exist yet. The repository has no icon, screenshot or banner, and the jar has no `logoFile`. Missing:

- **Icon:** a square PNG, 512×512 or larger, for both sites.
- **Map view:** a top-down map from the bridge, for example
  `uv run mcp/almighty.py call map '{"center": [0, 0], "size": 256}' --png map.png`.
- **Box view:** a `look` picture of a building, front and top.
- **Eye view:** a `see` picture through a bot's or player's eyes, with entity boxes and the crosshair.
- **A bot in the game:** a client screenshot of a bot with the `[bot]` marker in the tab list or chat, ideally doing
  something visible such as building or flying an Immersive Aircraft plane.

Put the images under `docs/store/img/` on `main` and reference them in the description only by absolute URLs of the
form `https://raw.githubusercontent.com/zentixua/almighty/main/docs/store/img/<file>.png`.
