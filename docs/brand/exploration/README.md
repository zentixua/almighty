# Brand exploration (archive)

The rounds that led to the final mark in [`../README.md`](../README.md). Each round's sheet and verbatim blind reads are kept here. The scripts in `scripts/brand/` that made them now write into this folder.


**Round nine (current):** clean rerun of the top four: [`scores3.png`](scores3.png), [`scores3.md`](scores3.md). The first round-nine pool ([`scores2.md`](scores2.md)) is contaminated and superseded.

**Round eight:** blind scoring pool, name + icon only: [`scores.png`](scores.png), ranking and reads in [`scores.md`](scores.md).

**Round seven:** four face treatments for the cloud on strings: [`cloud-faces.png`](cloud-faces.png), reads in [`cloud-faces.md`](cloud-faces.md).

**Round six:** from above, against no. 2: [`finalists3.png`](finalists3.png), reads in [`finalists3.md`](finalists3.md).

**Round five:** finalists from four concept families: [`finalists2.png`](finalists2.png), process and reads in [`finalists2.md`](finalists2.md).

**Round four:** finalists: [`finalists.png`](finalists.png), reads in [`finalists.md`](finalists.md).

**Round three:** Puppeteer variants: [`puppeteer.png`](puppeteer.png), reads in [`puppeteer.md`](puppeteer.md).

**Round two:** six quick mark sketches for Artem to choose from: [`sketches.png`](sketches.png), notes and blind reads in [`sketches.md`](sketches.md). The round-one kits below stay as they are until he picks one.

Every image here is pixel art drawn on a grid in code (`scripts/brand/`, Python + Pillow: rectangles, 1-px lines and
hand-typed sprites on a 64x64 mark grid) and enlarged only by whole nearest-neighbour factors. No image generation,
no filters, no gradients. Rebuild everything with `python3 scripts/brand/build.py`. Side-by-side view at real use
sizes: [`overview.png`](overview.png). Blind test and its verbatim answers: [`blind-check.md`](blind-check.md).

Two directions are kept. Both read as "an AI gets control over the game" to readers who knew nothing about the mod;
neither cleared the "AI-generated or amateur?" check (verdict: clean and competent, but template-level). Three more
directions (a crowned robot head, a console with an `>AI_` prompt, an eye over a stone pyramid) were drawn and
dropped because readers saw an AI companion, an AI chatbot or an Illuminati temple; `blind-check.md` keeps their answers.

## Grasp (`grasp/`)

A steel hand with "AI" on its back plate holds a turf block on four straight control strings: an agent that works the
world like a puppeteer, and the strings say the control is wired, not loose. It is the clearer of the two: every blind
reader said an AI agent controls, builds in or manipulates the world. Palette: night `#12151C`; steel `#23283A`
`#363D52` `#56607A` `#8892AB` `#C5CCDC`; agent cyan `#1B6F67` `#43D9C2` `#D8FFF8`; turf `#2C5A24` `#3D7A2E` `#56A03C`
`#7DC452`; soil `#33201A` `#563826` `#764E33` `#956843`; outline ink `#0A0B10`; wordmark `#ECEFF4` / `#9AA3B5`.

## Sovereign (`sovereign/`)

A gold crown engraved "AI" set down on a turf block: the agent's sovereignty over the world, read in one glance as an
AI ruling or managing the game. The crown is the mark's power; the block is the world it rests on. Palette: night
`#13151D`; gold `#6E3F12` `#B7771F` `#E9AE36` `#FFE08A`; turf and soil as Grasp; outline ink `#0A0B10`; wordmark as
Grasp; tagline accent gold `#E9AE36`.

## Files (the same set in each folder)

| File | Size | Native grid x factor | Where it goes |
|---|---|---|---|
| `icon-64.png` | 64x64 | 64x64 x1 | pixel source (round one; the shipped mod logo is now `../logo/modlist-logo.png`) |
| `icon-128.png` | 128x128 | x2 | small store icon |
| `icon-512.png` | 512x512 | x8 | Modrinth and CurseForge project icon |
| `logo.png`, `logo-native.png` | 4x / 1x | mark + wordmark on transparency, ink edge so it sits on dark and light | README top, docs, store descriptions |
| `banner-1280x320.png` | 1280x320 | 320x80 x4 | README and pack-list header |
| `modrinth-banner-1920x480.png` | 1920x480 | 320x80 x6 | Modrinth listing banner / description header |
| `modrinth-cover-1920x1080.png` | 1920x1080 | 240x135 x8 | Modrinth gallery, featured image |
| `curseforge-header-1600x400.png` | 1600x400 | 320x80 x5 | CurseForge project header / description banner |
| `github-social-1280x640.png` | 1280x640 | 256x128 x5 | GitHub repository social preview |
| `avatar-400.png` | 400x400 | 80x80 x5 | profile picture (X, YouTube, Discord) |
| `x-header-1500x500.png` | 1500x500 | 300x100 x5 | X/Twitter header; the lockup sits right of centre, clear of the profile picture |
| `youtube-2560x1440.png` | 2560x1440 | 512x288 x5 | YouTube channel art; the lockup fits the 1546x423 safe area (checked in the build) |
| `thumbnail-title-1280x720.png` | 1280x720 | 160x90 x8 | video thumbnail with a short title (title text is a placeholder) |
| `thumbnail-plain-1280x720.png` | 1280x720 | 160x90 x8 | video thumbnail without text |

Type: the wordmark face ("Almighty" only: 3-px stems, 1-px chamfers, ascenders taller than capitals) and a 5x7
capital face for taglines and titles are drawn in `scripts/brand/fonts.py`. Taglines: "INFINITE POWER. CONTROLLED
USE." (accent) and "AN AI GAME MASTER FOR YOUR SERVER" (grey `#AEB6C6`). Banner backgrounds are flat night with a
sparse 8-px dot grid.
