# Store listing: Modrinth and CurseForge

Fields to paste into the project forms. The long description is [description.md](description.md).

## Name and summary

- **Title:** "Almighty: AI Game Master" (the name stays Almighty)
- **Slug:** `almighty` on Modrinth. On CurseForge `almighty` is taken by a 2023 Fabric
  modpack named "Almighty" (https://www.curseforge.com/minecraft/modpacks/almighty), so use `almighty-game-master`
  there; the title with "AI Game Master" keeps the two apart.
- **Summary** (223 of 256 characters; CurseForge prefers one sentence, this is one):

  ```
  An AI game master for your Minecraft server: connect Claude Code, Codex or any MCP app and it sees the world, talks to players, builds with undo, runs events and drives bot players. Server-side, with per-player permissions.
  ```

## Classification

| Field | Modrinth | CurseForge |
|---|---|---|
| Project type | Mod | Mod |
| Categories | Management (primary), Utility | Server Utility (primary), Utility & QoL |
| Loaders | NeoForge, Fabric | NeoForge, Fabric |
| Game versions | 1.21.1 (NeoForge), 26.1.2 and 26.3 (NeoForge and Fabric) — per file, from the release jars | the same |
| Environment | Client: unsupported. Server: required | Server |
| Dependencies | none required; optional: Immersive Aircraft, Sable | none required; optional: Immersive Aircraft, Sable |

The mod has no blocks, items or network packets, so clients never need it. Sable is on both sites (on CurseForge
with the slug `sable`, by the same authors). Check the category names against the forms: both
sites rename categories from time to time.

## License and links

- **License:** GPL-3.0-only (GNU General Public License v3.0 only). The jar bundles Groovy 4 under Apache 2.0.
- **Source:** https://github.com/zentixua/almighty
- **Issues:** https://github.com/zentixua/almighty/issues
- **Wiki:** none; the README on GitHub is the documentation.
- **Discord:** none.
- **Disclosures (Modrinth, Settings → Disclosures, set by hand before `submit`; Artem's decision D5: full disclosure):**
  - *AI-generated content*: **Code** and **Text** (the code, the documentation and the page texts are written by
    Claude through Claude Code under the author's direction, review and tests). Not **Assets**: the icon, banners and
    gallery are code-drawn pixel art and the mod's own renders, with no image generation (`docs/brand/README.md`).
  - *AI functionality*: yes — the mod exists for an external AI agent the owner runs; it contains no model and calls
    no AI service.
  - *External system interactions*: yes — an HTTP/MCP endpoint (loopback by default), OAuth sign-in, and owner-only
    code execution (Groovy scripts) on the server machine.
  - *Telemetry*: none.
  The same facts are the "AI disclosure" section of [description.md](description.md), so CurseForge (which has no
  such field) shows them too. Modrinth's rule against projects "primarily or entirely a product of AI output"
  (§6.2.2) may still lead to a refusal; D5 accepts that risk.
- **CurseForge review note** (paste into the first file's changelog and keep in the description, plan 10.1): the page
  says plainly, under "What it is, plainly" and "Security", that this is owner-operated remote control: a token- or
  code-gated HTTP listener on loopback by default, code execution only for the owner's own agent, and a switch to
  turn scripts off. This is what a static scan of a network listener plus Groovy class loading otherwise looks like.
- **Third-party distribution** (CurseForge, License tab): **on**, so server installers and Docker images can fetch the
  server-only jar (Artem's decision D5).

## Files

- A release carries one jar per game version and loader: `almighty-<version>+<game version>-<loader>.jar` (from the
  build workflow's nodes, `versions/*`). The publish workflow uploads each as its own file with that game version,
  loader and Java version (21 for 1.21.1, 25 for 26.x); the file's name says the same.
- Release type: Release (the publish workflow uploads every version as Release). Changelog: the matching
  `docs/releases/<version>.md`, in English from 0.4.0 (in Russian up to 0.3.1). The workflow reads this file from the
  branch it runs on.

## Gallery

The icon is the brand icon `docs/brand/icon/icon-512.png` (512×512, code-drawn pixel art). CurseForge takes a
400×400 avatar: `docs/brand/social/avatar-400.png`. The description starts with the brand banner
(`docs/brand/store/modrinth-banner-1920x480.png`; on CurseForge `docs/brand/store/curseforge-header-1600x400.png`).
The gallery opens with the brand cover, then shows the bridge's own pictures from a server, enlarged three times
without smoothing. In this order (the first one is featured):

| File | Caption |
|---|---|
| `docs/brand/store/modrinth-cover-1920x1080.png` | Almighty: an AI game master for your Minecraft server |
| `docs/media/eye.png` | What a bot sees: the eye view of an airport, with the crosshair in the middle |
| `docs/media/map.png` | The map view: an airport from above, drawn like a vanilla map |
| `docs/media/look.png` | An airport terminal from the side in the box view; the checkerboard marks chunks that are not loaded |

A new image goes under `docs/media/` and into `gallery` in [modrinth.json](modrinth.json) as
`{"file": "docs/media/<file>.png", "title": "<caption>"}`. In the description, images are referenced only by absolute
URLs of the form `https://raw.githubusercontent.com/zentixua/almighty/main/docs/media/<file>.png`.

## Publishing

The workflow `.github/workflows/publish.yml` creates the Modrinth page and uploads the jars of a GitHub release. It
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
  repository, and the release's jars. Only the project members see a draft and its versions. Run it again after changing the
  images or the texts: it uploads only new images and skips jars that are already uploaded.
- `submit`: sends the Modrinth draft for review, then uploads the jars to CurseForge. On CurseForge the first file sends
  a new project for review. Turn on the Modrinth disclosures (above) by hand before the first `submit`.
- `release`: for later versions, after Modrinth has approved the project: updates the Modrinth page and uploads the
  jars to Modrinth and CurseForge.

Each uploaded file is named `Almighty <version> (<game version> <loader>)`, has the version number
`<version>+<game version>-<loader>`, and is marked server-only and lists Immersive Aircraft and Sable as optional dependencies. An image
removed from `gallery` is removed from the site on the next `draft` or `release`; captions of uploaded images are
changed on the site. Running `submit` or `release` twice for the same version uploads the files to CurseForge twice (Modrinth skips a jar
it already has).

A CurseForge project can only be created on the site. Create it by hand first with the fields above (title, slug
`almighty-game-master`, summary, CurseForge categories, license, links, the avatar `docs/brand/social/avatar-400.png`,
and description.md as the description), then add its ID as `CURSEFORGE_ID`.
