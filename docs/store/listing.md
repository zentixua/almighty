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
| Dependencies | none required; optional: Immersive Aircraft, Sable | none required; optional: Immersive Aircraft, Sable |

The mod has no blocks, items or network packets, so clients never need it. Sable is on both sites (on CurseForge
with the slug `sable`, by the same authors, checked 2026-10-12). Check the category names against the forms: both
sites rename categories from time to time.

## License and links

- **License:** GPL-3.0-only (GNU General Public License v3.0 only). The jar bundles Groovy 4 under Apache 2.0.
- **Source:** https://github.com/zentixua/almighty
- **Issues:** https://github.com/zentixua/almighty/issues
- **Wiki:** none; the README on GitHub is the documentation.
- **Discord:** none.

## Files

- Upload `almighty-<version>.jar` from https://github.com/zentixua/almighty/releases, 0.4.0 or later (the first release with English texts).
- Release type: Release (the publish workflow uploads every version as Release). Changelog: the matching
  `docs/releases/<version>.md`, in English from 0.4.0 (in Russian up to 0.3.1). The workflow reads this file from the
  branch it runs on.

## Gallery

The icon is `docs/media/icon.png` (512×512), the same picture as the mod's logo `almighty.png` in the jar. The
gallery shows the bridge's own pictures from a server, enlarged three times without smoothing. In this order (the first
one is featured):

| File | Caption |
|---|---|
| `docs/media/eye.png` | What a bot sees: the eye view of an airport, with the crosshair in the middle |
| `docs/media/map.png` | The map view: an airport from above, drawn like a vanilla map |
| `docs/media/look.png` | An airport terminal from the side in the box view; the checkerboard marks chunks that are not loaded |

A new image goes under `docs/media/` and into `gallery` in [modrinth.json](modrinth.json) as
`{"file": "docs/media/<file>.png", "title": "<caption>"}`. In the description, images are referenced only by absolute
URLs of the form `https://raw.githubusercontent.com/zentixua/almighty/main/docs/media/<file>.png`.

## Publishing

The workflow `.github/workflows/publish.yml` creates the Modrinth page and uploads the jar of a GitHub release. It
runs only when started by hand. [modrinth.json](modrinth.json) holds the Modrinth fields from this file, the icon
path and the gallery with captions; change it together with this file.

Secrets and variable, in Settings → Secrets and variables → Actions:

- `MODRINTH_TOKEN`: New repository secret. A Modrinth personal access token with the scopes Create projects, Read
  projects, Write projects, Create versions and Read versions.
- `CURSEFORGE_TOKEN`: New repository secret. A CurseForge API token for file uploads.
- `CURSEFORGE_ID`: Variables tab → New repository variable. The project ID shown on the CurseForge project page.

Without `MODRINTH_TOKEN` the workflow skips Modrinth, and without `CURSEFORGE_TOKEN` or `CURSEFORGE_ID` it skips
CurseForge. The run does not fail because of that.

To start it: Actions → publish → Run workflow, choose the step, enter the version (for example `0.4.0`; empty means
the latest GitHub release) and press Run workflow.

- `draft`: creates the Modrinth project as a draft, or updates the draft: the fields, the page text from
  [description.md](description.md), the icon and the gallery images from `modrinth.json` that exist in the
  repository, and the jar. Only the project members see a draft and its versions. Run it again after changing the
  images or the texts: it uploads only new images and skips a jar that is already uploaded.
- `submit`: sends the Modrinth draft for review and uploads the jar to CurseForge. On CurseForge the first file sends
  a new project for review.
- `release`: for later versions, after Modrinth has approved the project: updates the Modrinth page and uploads the
  jar to Modrinth and CurseForge.

Each uploaded file is marked server-only and lists Immersive Aircraft and Sable as optional dependencies. An image
removed from `gallery` is removed from the site on the next `draft` or `release`; captions of uploaded images are
changed on the site. Running `submit` or `release` twice for the same version uploads the file to CurseForge twice.

A CurseForge project can only be created on the site. Create it by hand first with the fields above (title, slug
`almighty-game-master`, summary, CurseForge categories, license, links, and description.md as the description), then
add its ID as `CURSEFORGE_ID`.
