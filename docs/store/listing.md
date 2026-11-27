# Store listing: Modrinth and CurseForge

Fields to paste into the project forms, and what the publish workflow uploads. Nothing here publishes by itself.

| File | What |
|---|---|
| [description.md](description.md) | Modrinth page (Markdown with a few `<img>` tags) |
| [description.curseforge.md](description.curseforge.md) | CurseForge page, generated from description.md by `python3 scripts/store.py` (paste it into CurseForge's Markdown editor) |
| [description.ru.md](description.ru.md) | Russian page, linked from the top of both; same sections as English |
| [modrinth.json](modrinth.json) | Modrinth fields, icon, CurseForge avatar, gallery (read by the publish workflow) |

Edit description.md and description.ru.md together, then run `python3 scripts/store.py`. CI runs
`python3 scripts/store.py --check`: the CurseForge page is current, the summary fits and matches this file, the
categories exist, every icon, gallery and page image is a file in the repository within Modrinth's size limits, page
images are absolute `raw.githubusercontent.com/zentixua/almighty/main/...` URLs, links are absolute, and the Russian
page has the same sections.

## Page style

What the best pages do (Sodium, Create, Distant Horizons, Simple Voice Chat, Chunky, spark, LuckPerms on Modrinth
and CurseForge, and Artem's Incoming), and how this page follows it:

- **A hero banner, then one bold sentence** that says what the mod does for you (LuckPerms, Distant Horizons,
  Incoming). Ours: the brand banner, the pitch, and one line of facts (server-side, loaders, versions, Russian).
- **Short feature blocks with real pictures**, grouped, each bullet a bold keyword and one sentence (LuckPerms,
  Simple Voice Chat, Incoming). Ours: the three layers (body, operator, engine), each with the bridge's own pictures.
- **Honest limits and setup in plain sight**: Simple Voice Chat's "needs server setup" note, Chunky's permissions,
  Distant Horizons' FAQ. Ours: the security note, the "you decide" and "nothing behind your back" sections, a FAQ.
- **Install in a few steps, versions and integrations as tables**, and links last (guide, source, issues), as
  CurseForge's moderation asks (outside links below the description, modest size). No donation or Discord badges:
  we have none.
- **Renderers.** Modrinth renders GitHub-flavoured Markdown and a safe HTML subset (`<img width>`, `<center>`; Sodium
  and Distant Horizons use them). CurseForge documents no HTML subset for its Markdown editor, so its page is plain
  Markdown: `![alt](url)` images at full width, tables, no HTML. Neither site resolves relative paths:
  images and links are absolute. CurseForge avatars are 400×400 PNG or JPEG (no WebP). Check both previews before
  the first submit.

## Name and summary

- **Title:** "Almighty: AI Game Master" (the name stays Almighty).
- **Slug:** `almighty` on Modrinth. On CurseForge `almighty` is taken by a 2023 Fabric modpack named "Almighty"
  (https://www.curseforge.com/minecraft/modpacks/almighty), so use `almighty-game-master` there; the title with
  "AI Game Master" keeps the two apart.
- **Summary** (238 of 256 characters; the same on both sites):

  ```
  Let an AI agent run your Minecraft server as game master: it sees the world, talks to players, builds with undo, runs events and plays with bot players, within the rights you set. Server-side, NeoForge and Fabric; players install nothing.
  ```

## Classification

| Field | Modrinth | CurseForge |
|---|---|---|
| Project type | Mod | Mod |
| Categories | Management, Utility, Game Mechanics | Server Utility (main), Utility & QoL |
| Loaders | NeoForge, Fabric | NeoForge, Fabric |
| Game versions | 1.21.1, 26.1.2, 26.3 (per file, see below) | the same |
| Environment | Client: unsupported. Server: required ("server only") | Server |
| Dependencies | Fabric files require Fabric API; optional per file (see below) | the same |

Category ids are checked against `GET https://api.modrinth.com/v2/tag/category` and CurseForge's Minecraft mod
filter (27.11.2026); `scripts/store.py` keeps Modrinth's list. The mod has no blocks, items or network packets, so
clients never need it.

Files, one per node (`versions/<node>`), each its own Modrinth version `<version>+<node>`:

| File | Loader | Minecraft | Java | Required | Optional |
|---|---|---|---|---|---|
| `almighty-<v>+1.21.1-neoforge.jar` | NeoForge 21.1.264+ | 1.21.1 | 21 | | LuckPerms, Immersive Aircraft, Sable, Open Parties and Claims, FTB Chunks |
| `almighty-<v>+26.1.2-neoforge.jar` | NeoForge 26.1.2.130+ | 26.1.2 | 25 | | LuckPerms, Immersive Aircraft, Open Parties and Claims, FTB Chunks |
| `almighty-<v>+26.3-neoforge.jar` | NeoForge 26.3.0.118+ | 26.3 | 25 | | LuckPerms, Open Parties and Claims |
| `almighty-<v>+26.1.2-fabric.jar` | Fabric Loader 0.20.1+ | 26.1.2 | 25 | Fabric API | LuckPerms, Immersive Aircraft, Open Parties and Claims, FTB Chunks |
| `almighty-<v>+26.3-fabric.jar` | Fabric Loader 0.20.1+ | 26.3 | 25 | Fabric API | LuckPerms, Open Parties and Claims |

The optional list comes from each node's `compat_*` keys (`.github/scripts/release_nodes.sh`). FTB Chunks is not on
Modrinth, so it is listed on CurseForge only (`ftb-chunks-forge` for NeoForge, `ftb-chunks-fabric` for Fabric).

## Disclosure

Say it plainly: the code, the documentation and the page texts are written with Claude Code under Artem's direction
and review; the artwork is pixel art drawn by code from the same sessions (`scripts/brand/`, no image generation);
the mod exists for AI agents. The page says so in "Made with AI, for AI".

On Modrinth, set by hand in the project's Settings before the first `submit` (the API has no documented field for it):

- AI-generated code (`ai_content_code`), AI-generated text (`ai_content_text`), AI-generated assets
  (`ai_content_assets`: the brand art is drawn by code an agent wrote), AI functionality (`ai_functionality`: the mod
  is a bridge for AI agents).
- System interactions (`system_interactions`): the bridge opens a network port, and scripts, when on, can reach files
  and processes as the server's user.

CurseForge has no such field; the page text covers it.

## License and links

- **License:** GPL-3.0-only. The jar bundles Groovy 4 under Apache 2.0 (and NightConfig, LGPL-3.0, on Fabric).
- **Source:** https://github.com/zentixua/almighty
- **Issues:** https://github.com/zentixua/almighty/issues
- **Wiki:** https://github.com/zentixua/almighty/blob/main/docs/guide/README.md
- **Discord:** none.

## Media

All brand images are from [docs/brand](../brand/README.md) (pixel art enlarged by whole factors, never smoothed).

| Use | File |
|---|---|
| Modrinth icon | `docs/brand/icon/icon-512.png` (512×512) |
| CurseForge avatar | `docs/brand/social/avatar-400.png` (400×400) |
| Modrinth page banner | `docs/brand/store/modrinth-banner-1920x480.png` (top of description.md) |
| CurseForge page header | `docs/brand/store/curseforge-header-1600x400.png` (top of description.curseforge.md) |

Gallery, in this order (the first is featured); upload the same images to CurseForge's Images page, with the cover
as its first image:

| File | Title | Description |
|---|---|---|
| `docs/brand/store/modrinth-cover-1920x1080.png` | Almighty | An AI game master for your Minecraft server. |
| `docs/media/eye.png` | Through a bot's eyes | The first-person picture the agent gets from a bot at an airport, drawn by the server, with the crosshair in the middle. |
| `docs/media/map.png` | Map view | An airport from above, drawn by the server like a vanilla map. |
| `docs/media/look.png` | Box view | An airport terminal from the side; the checkerboard marks chunks that are not loaded, which the agent never loads by looking. |

The three in-game pictures are the bridge's own output on a server, enlarged three times without smoothing. A new
image goes under `docs/media/` and into `gallery` in [modrinth.json](modrinth.json); in the pages, only by its
absolute `raw.githubusercontent.com` URL.

### Needs the laptop

Real game-client screenshots that would show what text cannot. Do not mock them up; take them on a test server
(GUI scale 2, HUD visible, 1920×1080, PNG):

1. **Confirmation in chat:** a player asks the game master for something that affects others, and the chat shows
   the exact command with **[Allow]** / **[Deny]**.
2. **Receipt with undo:** "[Almighty] Done for you: … [Undo]" after a build, then the build gone after the click.
3. **Sign-in:** the browser page with the four-digit code next to the console line `/almighty approve 4821 … owner`.
4. **Bots in the tab list:** `[bot]` names among players, and a bot flying an Immersive Aircraft plane in
   third person.
5. **A build in progress** seen from a player, with the agent's map view of the same place beside it.
6. **`/gm` conversation:** a player's private line and the game master's reply in chat.

Then add them to the gallery and put the first two in the "You decide how much" section.

## Publishing

`.github/workflows/publish.yml` creates the Modrinth page and uploads the jars of a GitHub release. It runs only
when started by hand; preparing these files publishes nothing.

Secrets and variable, in Settings → Secrets and variables → Actions:

- `MODRINTH_TOKEN`: New repository secret. A Modrinth personal access token with the scopes Create projects, Read
  projects, Write projects, Create versions and Read versions.
- `CURSEFORGE_TOKEN`: New repository secret. A CurseForge API token for file uploads.
- `CURSEFORGE_ID`: Variables tab → New repository variable. The project ID shown on the CurseForge project page.

Without `MODRINTH_TOKEN` the workflow skips Modrinth, and without `CURSEFORGE_TOKEN` or `CURSEFORGE_ID` it skips
CurseForge. The run does not fail because of that.

To start it: Actions → publish → Run workflow, choose the step, enter the version (for example `0.5.0`; empty means
the latest GitHub release) and press Run workflow. The first job resolves the release and updates the page; then one
job per node uploads that node's jar (`almighty-<version>+<node>.jar`; a node missing from the release is skipped).

- `draft`: creates the Modrinth project as a draft, or updates the draft: the fields, the page text from
  [description.md](description.md), the icon and the gallery from `modrinth.json`, and every node's jar. Only the
  project members see a draft and its versions. Run it again after changing the images or the texts: it uploads
  only new images and skips jars already uploaded.
- `submit`: sends the Modrinth draft for review and uploads every jar to CurseForge. On CurseForge the first file
  sends a new project for review. Set the Modrinth disclosures by hand before the first `submit`.
- `release`: for later versions, after Modrinth has approved the project: updates the Modrinth page and uploads
  every jar to Modrinth and CurseForge.

Each file is marked server-only, with its node's loader, game version, Java and dependencies (table above). An image
removed from `gallery` is removed from the site on the next `draft` or `release`; captions of uploaded images are
changed on the site. If a node's job fails, use **Re-run failed jobs**: re-running the whole run uploads the other
nodes' files to CurseForge again (Modrinth skips jars it has).

The CurseForge description is not uploaded by the workflow (its API takes files only): paste
[description.curseforge.md](description.curseforge.md) into the project's description in the Markdown editor after
each change.

A CurseForge project can only be created on the site. Create it by hand first with the fields above (title, slug
`almighty-game-master`, summary, CurseForge categories, license, links, the avatar
`docs/brand/social/avatar-400.png`, description.curseforge.md as the description), then add its ID as
`CURSEFORGE_ID`.
