# Blind check

How it was run: every answer below comes from a separate, fresh Claude session (`claude -p`, Sonnet) started in an
empty temporary directory holding only one copied image named `image.png`, with only the Read tool allowed. No
session saw this repository, the brief, the mod's name (except where the banner shows it) or any other answer.
Icons were shown at 128x128, banners at 1280x320, the harshness check at 512x512 (icon) or 1280x320 (banner).

Questions, word for word: "This is the icon of a Minecraft mod. In one or two sentences, what do you think this mod
does?" and "Does this look AI-generated or amateur? Be harsh."

A first round run through in-session subagents was thrown away: those subagents load this repository's AGENTS.md,
and one of them quoted "the project docs" back. Only the clean runs below count.

## Result

| Direction | Icon reads "an AI gets power/control over the game" | Banner reads it | Harshness check | Verdict |
|---|---|---|---|---|
| grasp | 3/3 (final) | 3/3 | "competent", "not sloppy", but "template / hobby tier"; icon judged "generic", one reader called it AI-generated | **reading PASS, harshness NOT cleared** |
| sovereign | 2/3 (final; third says "AI king ... control or help the player") | 3/3 | "amateur-tier rather than professional, but not sloppy"; icon "a quick placeholder" | **reading PASS, harshness NOT cleared** |
| regent (crowned robot head) | 0/3 (no letters: "robot king boss"), then "AI companion / helper" | 3/3 | "hobbyist placeholder" | **FAIL, dropped** |
| prompt (console, `>AI_`) | 0/3 ("terminal / console mod"), then "AI assistant you type to" | 3/3 | "blobby block, stray bolt, placeholder" | **FAIL, dropped** |
| overseer (eye over a stone pyramid) | 0/3 ("Illuminati temple structures") | not run | not run | **FAIL, dropped** |

So the gate is met only on meaning: two directions read correctly, none clears the harshness check. The two
final banner reviews no longer call them AI-generated (a round-2 reviewer said the clean pixel edges and exact text
rule out an image generator; the final grasp icon review still claims it is generated) but they still rate the kit "competent, template-level, not distinctive". Points they
repeat that are still open: the mark relies on a literal "AI" label; the grass block is close to Minecraft's own;
the layouts leave dead space; the tagline sits in a second, thinner pixel face; the slogan reads as stock copy.

What changed between rounds, because of these answers:
- Round 1 (no letters): every mark was read without AI. A four-point "spark" was tried as the AI sign and read as
  a waypoint or teleport star; the letters "AI" drawn into the mark were the only cue that read as AI.
- Round 2: hand redrawn (separate knuckles, two-segment fingers, attached thumb), console given a bolt that lands
  on the block, crown set down on the block, flat tile (a two-tone band was read as a rendering artefact).
- Round 3: own stylised turf block (lit rim, regular fringe, a few large stones, no speckle), strings straight to
  lit anchors, crown letters engraved instead of cyan studs, brighter tagline grey.
- After round 3: the wordmark's l read as a capital I ("AImighty") in five reviews: capitals now sit below the
  ascenders, so the l stands taller than the A.

## Verbatim answers

## Final round (the versions in this folder)

### Icons and banners, final marks

**grasp**

- `icon-1`:

> This mod likely adds AI-controlled robots or drones, shown here as a hovering "AI" machine with a claw that beams down onto a grass block. It probably automates tasks such as mining, building, or terraforming blocks, or it may connect an AI assistant to the game.

- `icon-2`:

> A robotic AI-branded claw hovers over a grass block and reaches down with green beams. The mod probably lets an AI agent or chatbot control or build in the Minecraft world, for example by placing blocks, editing terrain or running commands from natural-language prompts.

- `icon-3`:

> A robotic hand labeled "AI" hovers over a grass block, with green beams reaching down into it. The mod probably lets an AI agent or chatbot control or build in the Minecraft world, for example by placing blocks, shaping terrain or automating tasks.

- `banner-1`:

> Almighty appears to add an AI game master to a Minecraft server. The robotic hand with strings attached to a grass block suggests it lets an AI control or manipulate the world, with permissions or limits ("Infinite power. Controlled use.").

- `banner-2`:

> Almighty adds an AI game master to your Minecraft server. It appears to let an AI control and shape the world, like a puppeteer working a grass block, with permission limits on what it can do ("infinite power, controlled use").

- `banner-3`:

> Almighty looks like a server-side mod that adds an AI game master to your Minecraft server. It probably lets an AI control or modify the world and gameplay, with limits on what it can do ("infinite power, controlled use").

- `harsh-banner`:

> - **Typography:** The wordmark "Almighty" is a stock rounded pixel font. The "l" and "I" are ambiguous, so it reads as "AImighty," which may be intentional. The tagline is in a thin, generic monospace pixel face, and the two-tone teal/grey split is a template layout.
> - **Copy:** "Infinite power. Controlled use." is a classic LLM-style antithesis slogan.
> - **Composition:** The banner is a safe left-icon, right-text layout on a dark navy background with a faint dot grid. It's tidy, but there's no depth, no lighting and no personality. The empty space on the right and the loose gap between the tagline and wordmark feel unconsidered.
> - **Palette:** Teal on dark navy is the default "AI tool" palette.
> 
> **Verdict:** About 6/10 as an amateur-made README banner. It's serviceable, but it looks template-driven or AI-assisted, with no real design identity. If you want it to stop reading that way, hand-pixel the mascot on a consistent grid, use a distinctive typeface, and write less slogan-like copy.

- `harsh-icon`:

> Yes, it looks AI-generated, and it also looks amateur.
> 
> - **AI tells:** The pixel grid is inconsistent. Some "pixels" are larger than others, and the edges are soft where real pixel art would be crisp. The robotic hand is an incoherent blob. Its fingers don't connect logically, the thumb is vague, and the shading doesn't follow a single light source. The glowing tethers are suspiciously uniform.
> - **Generic concept:** A robot hand labeled "AI" holding a Minecraft-style grass block on strings is a stock, prompt-like idea. The literal "AI" text on the hand is a cliché.
> - **Weak craft:** The grass block's perspective is muddled. The dirt texture is noisy and has no deliberate dithering or palette discipline. The outlines vary in weight. The composition is centered and static, with no background or context.
> - **Overall:** It reads as a generated icon or a beginner's attempt. It has no handcrafted pixel-art intent.

**sovereign**

- `icon-1`:

> A golden crown labeled "AI" sits on top of a grass block, so I think this mod adds an AI-controlled player or companion that can play Minecraft on its own. It might also be a chatbot or assistant that helps or "rules" over the world.

- `icon-2`:

> This mod most likely adds AI-controlled features to Minecraft, such as smart NPCs, companions or an assistant that you can talk to or give commands. The icon shows a golden crown labeled "AI" on top of a grass block, which suggests an AI that is in charge of the game world.

- `icon-3`:

> This mod probably adds AI to Minecraft. The crowned grass block with an "AI" label suggests an AI-powered NPC, companion or "king" character, or a chat assistant that can control or help the player in the game.

- `banner-1`:

> Almighty adds an AI game master to your Minecraft server, so an AI can act with broad, admin-like powers (spawning things, changing the world, running events) while server owners keep control over what it's allowed to do.

- `banner-2`:

> Almighty looks like a server-side mod that adds an AI game master to a Minecraft server. It probably lets an AI carry out commands and events with broad power, while admins keep control over what it is allowed to do ("Infinite power. Controlled use.").

- `banner-3`:

> Almighty appears to add an AI-powered "game master" to a Minecraft server. Players or admins could probably give it natural-language commands to change the world, spawn things or run events, with permissions and limits keeping that power under control ("infinite power, controlled use").

- `harsh-banner`:

> - **Logo:** A crown on a grass block is the most predictable Minecraft-adjacent idea. The "AI" label on the crown band is a literal, on-the-nose touch. The crown's shading is muddy and its edges are jagged in an unintended way, not a deliberate pixel-art way. The crown also floats awkwardly above the block without sitting on it.
> - **Wordmark:** "Almighty" is set in a stock rounded pixel font with a clumsy drop shadow. The "l" and "I" are indistinguishable, so it reads as "AImighty". That may be intentional, but it's a legibility problem.
> - **Tagline:** The orange and gray all-caps lines are in a different pixel font from the title, so the hierarchy feels slapped together. The slogan "Infinite power. Controlled use." is generic marketing filler.
> - **Layout:** The text block is left-heavy with dead space on the right. The faint dot-grid background is barely visible and adds nothing. It reads as a GitHub README header, not branding.
> 
> Verdict: competent but template-level. Nothing in it is distinctive enough to count as design.

- `harsh-icon`:

> - **Generic concept:** a crown on a Minecraft-style grass block, with "AI" written on it. It's a cliché mashup with no original idea.
> - **Crown:** the shape is awkward. The spikes are uneven and flat, and the band is a plain slab with a clumsy right-side shadow. The "AI" lettering is blocky and looks stuck on rather than part of the crown.
> - **Placement:** the crown floats above the block with no contact shadow or connection, so the two halves feel pasted together.
> - **Grass block:** it's a derivative copy of Minecraft's block. The perspective is off, and the grass overhang is lumpy and unconvincing. The dirt texture is sparse and repetitive.
> - **Outline and shading:** the black outline is inconsistent, and the shading is flat with only a couple of tones. Nothing separates it from a thousand free icon-pack assets.
> - **Overall:** it reads as a quick logo or avatar placeholder, not finished art.

### Harshness check of the final banners (after the wordmark fix)

**grasp**

> - **Tagline:** The two lines use different fonts, a teal pixel face and a thin gray one. The gray line is also much lighter than the headline, so the type hierarchy feels inconsistent.
> - **Icon:** The hand is a gray blob with no real form or shading. The strings are thin straight lines with no tension. The grass block is a decent isometric cube but is rendered at a different pixel scale and detail level than the hand, so the two don't look like one piece.
> - **Layout:** The icon sits at the far left with a large empty gap before the text. The dotted-grid background is barely visible. The overall banner looks like a default GitHub README header.
> - **Copy:** "Infinite power. Controlled use." is generic marketing filler.
> 
> The execution is clean and legible, but it has no distinctive style. It also borrows Minecraft's look without any original touch.

**sovereign**

> - **The tagline hierarchy is weak.** Orange all-caps is followed by grey all-caps in a thin font that's much lighter than the wordmark. The second line is a bit hard to read, and the orange and grey lines compete.
> - **The layout is loose.** There's a lot of dead space, with the logo pushed to the left third and nothing anchoring the right side. The dotted background grid is nearly invisible, so it adds nothing.
> - **The pixel style is inconsistent.** The block has shaded, dithered pixel art. The wordmark has a heavy outlined look, and the tagline is a thin monoline font. Three different pixel treatments don't quite feel like one system.
> - **The crown is slightly crude.** Its spikes are uneven and the highlights are heavy-handed.
> 
> **Verdict:** It's passable as a hobby-project banner for a Minecraft plugin. It's amateur-tier rather than professional, but it's not sloppy. The concept is derivative and the typography is the weakest part.

## Round 2

### Hand, crown and console after the first redraw

**grasp**

- `icon-1`:

> This mod probably adds AI-controlled behavior to Minecraft. The icon shows a robotic hand labeled "AI" holding strings over a grass block, like a puppeteer, so it likely lets an AI agent or bot control or automate things in the world, such as a smart NPC, a companion, or a player-like bot.

- `icon-2`:

> A robotic hand marked "AI" hovers over a grass block and seems to control it with glowing strings, like a puppeteer. The mod probably adds an AI agent or assistant that controls or automates things in the Minecraft world, such as building, playing for you, or acting on commands you give it.

- `icon-3`:

> This mod probably adds AI-controlled behavior to Minecraft. The icon shows a robotic hand labeled "AI" hovering over a grass block, with strings hanging down like a puppeteer's, so it likely lets an AI agent or chatbot control, build in or manipulate the world, or act as a companion or NPC.

- `banner-1`:

> Almighty appears to be a server-side mod that adds an AI game master to a Minecraft server. It likely lets an AI act on the world, with controls on what it's allowed to do ("infinite power, controlled use"). The image shows a robotic AI hand hovering over a grass block, as if puppeteering it with strings.

- `banner-2`:

> Almighty appears to be a server-side mod that adds an AI game master. An AI-controlled hand, shown puppeteering a grass block, suggests it can act on the world and players, probably through commands or events. The tagline "Infinite power. Controlled use." suggests that server admins can set limits on what it is allowed to do.

- `banner-3`:

> Almighty appears to add an AI "game master" to a Minecraft server. An AI-controlled hand, shown manipulating a grass block like a puppeteer, would use broad powers to shape the world or run events. The tagline "Infinite power. Controlled use." suggests server admins can set limits on what the AI is allowed to do.

- `harsh-banner`:

> - The pixel edges are clean and the shading is consistent.
> - The text is exactly spelled and aligned.
> 
> Image generators usually fail at both of those, so this looks like hand-made or programmatically drawn work.
> 
> It's tidy but a beginner's banner, and nothing about it is distinctive.

- `harsh-icon`:

> - **Concept:** A robot hand labelled "AI" puppeteering a Minecraft grass block is a cliché. The "AI" text on the palm is a lazy, literal label that tells the viewer what to think instead of showing it.
> - **Anatomy:** The hand has no coherent structure. The thumb is a detached blob, the palm is a boxy slab, and the fingers hang down like stilts. The glowing "strings" are rigid vertical lines of uneven length, so they read as legs, not wires. The hand also looks like it is standing on the block, not controlling it.
> - **Pixel-art craft:** The pixel density is inconsistent. The hand's shading is flat and chunky, while the grass block has finer dithering and highlights. There is no consistent light source. The outlines are uneven, and there is no deliberate palette discipline or anti-aliasing logic. Real pixel artists keep a fixed grid and a limited palette, and this image does neither.
> - **Block:** The Minecraft grass block is a near-copy of a well-known asset, and the grass overhang looks generic. The block is also oddly proportioned for an isometric view.
> - **Composition:** The subject floats in a flat dark void with no depth, no shadow and no context. Without an environment it is a sticker, not a scene.
> - **Overall:** It is a competent-looking but soulless mash-up of buzzwords and a recognizable game asset. It looks like the output of a prompt, not a considered design.

**sovereign**

- `icon-1`:

> This mod likely adds AI features to Minecraft, such as AI-controlled NPCs or companions, or a chat assistant. The crown labeled "AI" floating above a grass block suggests an AI that rules over or manages the world.

- `icon-2`:

> A crown labeled "AI" floats over a grass block, so this mod probably adds AI-controlled features to the game. That could be AI-powered NPCs or companions, or a chatbot or assistant that helps run or "rule" your world.

- `icon-3`:

> It's probably a mod that adds AI-controlled features, such as AI-driven NPCs or companions, or a chatbot or command assistant you can talk to inside the game. The golden crown labeled "AI" floating above a grass block suggests it gives the player or the world an AI "ruler" or overseer.

- `banner-1`:

> "Almighty" appears to add an AI-powered game master to a Minecraft server. It likely lets an AI carry out powerful actions, such as running commands, spawning things and changing the world, with permissions or limits controlling how it's used.

- `banner-2`:

> "Almighty" is a server-side mod that adds an AI game master to your Minecraft server. It apparently lets an AI carry out powerful actions, such as running commands or changing the world, while admins keep control over how much it's allowed to do ("Infinite power. Controlled use.").

- `banner-3`:

> Almighty adds an AI "game master" to your Minecraft server. It likely lets players or admins give natural-language commands that the AI carries out with powerful, admin-like abilities, while permissions and limits keep that power controlled.

- `harsh-banner`:

> - **Typography:** The "Almighty" wordmark uses a rounded pixel font that suits the theme. But the capital "I" and lowercase "l" look identical, so it reads as "AImighty", which is arguably a happy accident. The font itself looks like a default, not custom lettering.
> - **Tagline:** The orange line is fine. The grey subtitle is a thin, low-contrast pixel font that is noticeably harder to read, and the spacing is loose.
> - **Layout:** The composition is serviceable but flat. There's a large empty gap on the left and right, the icon is small relative to the canvas, and the vertical alignment of icon to text is loose. The faint dot-grid background is barely visible and adds nothing.
> - **Colour:** The palette is limited to dark navy, gold, orange and grey. It's safe, but it has no depth, no glow and no accent that makes it memorable.
> 
> **Verdict:** It looks like a competent hobbyist or template-driven banner, not a professional brand asset. It's functional for a GitHub README or a Minecraft plugin page, but it won't stand out.

- `harsh-icon`:

> - **Muddy outline:** The dark outline varies in thickness and has stray stair-steps. Some edges, like the crown's right side and the grass drip, are lumpy instead of deliberate.
> - **Generic composition:** "Minecraft block plus crown plus AI" is a stock-prompt concept. The glowing cyan "eyes" on the crown band are a random addition that serves no purpose.
> - **Weak lettering:** The "AI" is plain and cramped, and it sits on the band with no relation to the crown's form. It looks pasted on.
> - **Trademark look:** It leans directly on Minecraft's grass block with no original take, which looks amateur and is a possible IP risk.
> 
> It's passable as a placeholder icon, but it wouldn't pass as polished or original branding.

**prompt**

- `icon-1`:

> This mod probably adds an AI assistant or command-line interface to Minecraft. The icon shows a terminal window with a ">AI" prompt, a lightning bolt, and a grass block, so it likely lets players type commands or prompts to an AI that acts in the game world.

- `icon-2`:

> This mod probably adds an AI assistant to Minecraft that you talk to through a command prompt or chat. The terminal window with a ">AI" prompt, the lightning bolt and the grass block suggest it takes AI-generated commands and uses them to control or build in the game world.

- `icon-3`:

> This mod appears to add an AI assistant to Minecraft. The icon shows a terminal window with an ">AI" prompt, a lightning bolt, and a grass block, which suggests a command-line or chat interface where players can type natural-language requests and the AI carries out actions in the game.

- `banner-1`:

> Almighty appears to add an AI game master to a Minecraft server. It likely lets players or admins give natural-language commands that the AI carries out, such as running commands or changing the world, within controlled permissions ("infinite power, controlled use").

- `banner-2`:

> Almighty is an AI game master for your Minecraft server. It looks like an in-game AI command console that lets players or admins give the AI powerful abilities, such as running commands or changing the world, within controlled limits.

- `banner-3`:

> Almighty is an AI game master for your Minecraft server. It lets players or admins give natural-language commands to an AI that can carry out powerful in-game actions, with controls on what it's allowed to do.

- `harsh-banner`:

> - **Tagline.** The two lines use different type styles and colors, with no real hierarchy beyond that. The gray line is low-contrast and a little too long.
> - **Wordmark.** "Almighty" in a default-looking pixel font has no custom touches. The capital "I" reads like a lowercase "l", so it looks like "Almighty" with an "l".
> - **Empty space.** The right side and the faint dot-grid background are dead space, and the composition isn't balanced.
> - **Overall.** It's clean and competent, but it reads as a template-grade or hobbyist project banner rather than professional branding.
> 
> Verdict: human-made, passable, but amateur and unmemorable. I'd give it about 5/10.

- `harsh-icon`:

> - **Grass block:** It's a blobby mound, not a proper isometric cube. The shading is muddy, the dirt drips are random, and the dark outline is missing or broken. It looks like a generator's attempt at Minecraft.
> - **Lightning bolt:** It's mispositioned and awkwardly shaped. It dangles off the block like a stray antenna and has a floating "flag" at the top that doesn't read as a bolt.
> - **Layout:** The composition is off-balance. The text sits top-left, the block is centered low, and the bolt floats in between with no clear hierarchy.
> - **Window chrome:** The three dots are generic, with one orange and two grey for no reason. The scanline effect is barely visible, so it adds nothing.
> - **Palette:** It's the default "retro-tech" mix of teal, gold and slate, safe but unconsidered.
> - **Overall:** It would pass as a placeholder or a quick asset-generator output, but not as a polished logo or icon. A pixel artist would redraw it from scratch.

## Round 1 (with the AI letters)

### All four with the letters AI

**regent**

- `icon-1`:

> It looks like an AI-powered mod. The icon is a robot wearing a gold crown labeled "AI", so it probably adds an AI companion, assistant or ruler to the game. That could be a chatbot or an autonomous bot that plays or helps the player.

- `icon-2`:

> A pixel-art robot wearing a gold crown marked "AI" suggests a mod that adds an AI assistant or AI-controlled companion, perhaps a chatbot or an automated helper that follows commands, to the game.

- `icon-3`:

> This mod probably adds an AI-driven companion or NPC to Minecraft: a robot, shown with a gold crown marked "AI". It likely lets players talk to it or give it commands, and it may be able to act as a helper or "king" over other mobs.

- `banner-1`:

> "Almighty" appears to be a server-side mod that adds an AI-powered game master, a crowned robot, to your Minecraft server. It likely lets players or admins make requests, such as commands, events or world changes, that the AI carries out under controlled limits ("infinite power, controlled use").

- `banner-2`:

> "Almighty" looks like an AI-powered game master for Minecraft servers. It probably lets an AI assistant run server commands and events, such as building, spawning and changing the world, within controlled limits ("infinite power, controlled use").

- `banner-3`:

> Almighty appears to add an AI-powered game master to a Minecraft server. Players or admins could probably give it natural-language commands and it would carry them out in the world, with permission limits on what it can do ("infinite power, controlled use").

- `harsh-banner`:

> - **Typography.** The orange and gray tagline lines are fine on their own. But the orange line is wider than the title and the gray line is wider again, which makes the block feel unbalanced.
> - **Muddy contrast.** The gray tagline sits low against the dark navy background and is hard to read.
> - **Wordmark problem.** In this font the "l" in "Almighty" looks like a capital "I", so it reads as "AImighty."
> - **Awkward details.** The crown's "AI" text is tiny and barely legible at this size. The crown is also much more detailed than the head under it, so the two don't match stylistically.
> 
> Overall it reads as a decent README banner from a hobbyist, not a professional brand asset. It's serviceable, but nothing about it is distinctive.

- `harsh-icon`:

> - **Concept:** A robot wearing a crown with "AI" on it is a cliché. The "AI" label is on the nose, and the idea has no personality or story.
> - **Shading:** The head is a flat gray box with minimal shading. The crown has only two or three gold tones, and the highlights are inconsistent.
> - **Anatomy:** The neck and shoulders at the bottom are a vague dark blob. Nothing there reads as a body, and the crop looks arbitrary.
> - **Palette and background:** The cyan, gold and gray scheme is stock. The background is a flat dark void with no depth, and the faint horizontal band behind the head looks accidental.
> - **Face:** The eyes are the only appealing element. The three vent slots on the mouth are bland.
> - **Overall:** It looks like a stock game-asset avatar or a quick placeholder, not a distinctive piece. It would pass as a hobbyist's work, and the weak concept is what gives it away.

**grasp**

- `icon-1`:

> This mod most likely lets an AI control or build in the Minecraft world. The icon shows a robotic hand labeled "AI" hovering over a grass block with strings hanging from it, like a puppeteer, so it probably adds an AI agent or assistant that plays, builds or manipulates blocks for the player.

- `icon-2`:

> This mod appears to bring AI into Minecraft. The icon shows a robotic hand labeled "AI" hovering over a grass block, so it probably lets an AI agent or assistant build, modify or control the world and perform in-game tasks.

- `icon-3`:

> It looks like a mod that adds AI-controlled agents to Minecraft. The icon shows a robotic hand labeled "AI" hovering over a grass block, so the AI probably controls or edits the world, for example by building structures or running NPC-like bots.

- `banner-1`:

> Almighty appears to add an AI game master to a Minecraft server. The AI hand hovering over a grass block, with the tagline "Infinite power. Controlled use.", suggests it can act on the world (run commands, change things, run events) but only within limits that server admins set.

- `banner-2`:

> Almighty is an AI game master for a Minecraft server. It looks like an AI-controlled assistant that can manipulate the world and gameplay, with the "controlled use" tagline suggesting admin-set limits on what the AI can do.

- `banner-3`:

> Almighty looks like a server-side mod that adds an AI game master to a Minecraft server. An AI-controlled hand manipulates the world, and the tagline "Infinite power. Controlled use." suggests admins can let an AI change or run things in-game within set limits.

- `harsh-banner`:

> - **Pixel art:** It's clean and consistent, with no melted or warped details. That's a sign it was made by hand or in code, not by a diffusion model.
> - **Hand and cube:** The mechanical hand is crude and ambiguous. It's a blob with a hat-like block on top, and it's hard to tell it's a hand. The grass cube is the generic Minecraft block.
> - **Lasers:** The dotted cyan lines are thin and weak, so the "control" idea barely reads.
> - **Layout:** The text block sits too far right, and there's a big dead gap between the icon and the title. The faint skyline line at the bottom looks like an artifact.
> - **Typography:** "Almighty" in that pixel font has an "l" that looks like a capital "I", so it reads as "AImighty". That could be a pun or a legibility flaw. The tagline lines are fine, but the grey subtitle has low contrast.
> - **Overall:** It reads as a competent but generic indie or GitHub README banner. The concept is cliché (a Minecraft block plus an "AI" label), and the polish is mid-tier.

- `harsh-icon`:

> - **Concept:** A robot hand labeled "AI" dangles puppet strings over a Minecraft grass block. The "AI controls the game" idea is clumsy and cliché. The literal "AI" label is a crutch, and the whole thing reads as a stock-icon mashup.
> - **Hand:** It is the worst element. The shape is ambiguous, with a blocky slab, a floating thumb and stubby cylinder fingers that don't read as a hand. The top is cropped awkwardly at the canvas edge.
> - **Strings:** They are thin dotted cyan lines, and they are inconsistent in length and spacing. They don't connect to anything on the block, so the puppet-string idea doesn't land.
> - **Block:** It is the strongest part, but it is a generic Minecraft-style isometric cube. The grass-to-dirt transition is noisy, and the shading and outline are inconsistent with the hand.
> - **Style:** The pixel resolution and outline treatment aren't consistent between the two elements, which is a common mark of AI-generated or hobbyist pixel art. The flat dark background with a faint horizontal band adds nothing.
> - **Overall:** There is no composition, lighting or story beyond the label. Anyone who has seen real pixel art will spot it as generic and low-effort.

**sovereign**

- `icon-1`:

> A crown labeled "AI" floating above a grass block suggests a mod that adds an AI-powered ruler or overseer to the Minecraft world. It could be an AI assistant or chatbot, or an AI-controlled NPC or companion that manages or "rules" the game.

- `icon-2`:

> This mod most likely adds AI-controlled behavior to Minecraft. The golden crown labeled "AI" sitting above a grass block suggests an AI "ruler" or overseer for the world, such as smarter NPCs or mobs, or an AI assistant that can control or manage the game.

- `icon-3`:

> A mod that adds AI-controlled behavior to the game, likely an AI "king" or ruler (shown by the crown labeled "AI") over a Minecraft grass block. It probably puts an AI in charge of the world, for example by controlling NPCs, mobs, or server management.

- `banner-1`:

> "Almighty" looks like a server-side mod that adds an AI game master to a Minecraft server. It would let an AI carry out powerful actions, such as running commands, building, or shaping events, while server admins limit what it's allowed to do.

- `banner-2`:

> "Almighty" looks like a server-side mod that adds an AI game master, probably an LLM that can run commands and change the world, and likely respond to player requests. The tagline "Infinite power. Controlled use." suggests it comes with permissions or limits on what the AI is allowed to do.

- `banner-3`:

> Almighty looks like a server-side mod that adds an AI game master to a Minecraft server. It probably lets an AI carry out powerful actions, such as running commands or shaping the world, while server admins keep control over what it can do. This is based on the tagline "Infinite power. Controlled use. An AI game master for your server."

- `harsh-banner`:

> - **Typography:** The title is "Almighty". The lowercase "l" and capital "I" are indistinguishable in this font, so it reads as "AImighty". That may be deliberate, but it comes across as a flaw. The font is a generic pixel font. The tagline uses two different fonts, and the line spacing is cramped.
> - **Colors:** The orange and grey-blue on near-black navy is a safe, default "gamer" palette. The contrast on the grey subline is mediocre.
> - **Background:** The faint jagged horizon strip at the bottom is barely visible. It looks like an afterthought and does nothing for the image.
> - **Copy:** "Infinite power. Controlled use." is a stock AI-marketing slogan, which adds to the generic feel.
> 
> Overall it's a competent but template-like banner. It has no distinctive identity, and the spacing and layout are poor. A designer would redo the layout and drop the borrowed Minecraft block for original art.

- `harsh-icon`:

> - **Crown shape:** The crown has odd proportions and no real volume. The band is flat, and the jagged spikes have inconsistent highlights.
> - **Cube perspective:** The grass block is a bit off in its isometric perspective. The left and right faces have uneven edges, and the grass drip line is arbitrary.
> - **Composition:** The two objects float apart with no connection. The empty dark background and the faint horizontal band behind the cube feel like leftover artifacts.
> - **Concept:** The crown over a Minecraft-style block with "AI" on it is a derivative, low-effort idea. It borrows Minecraft's look without any original work.
> 
> Overall it's competent at a glance but soulless and generic. I'd say AI-assisted or template-quality, not the work of a skilled pixel artist.

**prompt**

- `icon-1`:

> This mod most likely adds AI features to Minecraft. The icon shows a terminal window with an ">AI_" prompt above a grass block, so it probably lets players type commands or chat with an AI assistant from inside the game.

- `icon-2`:

> It looks like a mod that adds an AI assistant or chatbot to Minecraft. The icon shows a terminal window with an ">AI_" prompt and a grass block, so players probably type commands or prompts that the AI turns into in-game actions, such as running commands, building or answering questions.

- `icon-3`:

> This mod most likely adds an AI assistant to Minecraft. The icon shows a terminal window with an "AI_" command prompt above a grass block, so players probably type commands or chat with an AI inside the game. It could also be an AI-driven helper or NPC that carries out tasks in the world.

- `banner-1`:

> Almighty is an AI game master for Minecraft servers. It appears to let players or admins give natural-language commands to an AI, which carries them out in the game under controlled permissions, as the tagline "Infinite power. Controlled use." suggests.

- `banner-2`:

> Almighty appears to add an AI "game master" to a Minecraft server. Players or admins would probably type natural-language prompts into a command-style interface, and the AI would run powerful in-game actions for them. The tagline "Infinite power. Controlled use." suggests those actions are limited by permissions or safeguards.

- `banner-3`:

> "Almighty" looks like a server-side mod that gives you an AI game master. You'd type natural-language prompts into a terminal-style interface and the AI would carry out commands and world changes for you. The tagline "Infinite power. Controlled use." suggests it comes with permission controls or limits on what the AI is allowed to do.

- `harsh-banner`:

> - The terminal window is a cliché. It has three dots, a `>AI_` prompt and a Minecraft grass block, and the block is the only part with any detail. The scanline texture is barely visible.
>   - The tagline has two lines in different colors and fonts. They are both thin, and the gray line has poor contrast on the dark background.
>   - The composition is flat. The icon and the text sit side by side with no depth or focal interest.
>   - The bottom strip is a dull dark band with a faint, jagged "skyline" that reads as an unfinished placeholder.
>   - The wide empty margins make the banner look like a README header made quickly.
> - **Verdict:** It's a competent, tidy template-grade banner. It is not distinctive or polished enough to look professional.

- `harsh-icon`:

> - **Awkward composition:** The block floats in a big empty terminal area, and the prompt text is crammed at the top. The two elements don't relate to each other. The empty black space looks unfinished.
> - **Mediocre typography:** The "AI" lettering is crude and unevenly spaced against the chevron. The orange cursor bar sits oddly far from the text.
> - **Cliché palette:** Teal on dark slate with an orange accent is the default "tech" look. The scanline overlay adds a faint, cheap retro effect.
> - **Meaningless details:** The three title-bar dots are different colors for no reason (one orange, two gray). The background strip behind the window looks like a rendering artifact.
> 
> Overall, it's competent as a placeholder, but it has no polish or personality.

## Round 0 (first marks, no letters)

**regent**

- `icon`:

> A crowned robot head suggests a mod about commanding or automating robots or machines, such as a "king" or "ruler" of automation. It could add robot or golem entities that you control, or admin-style tools for managing the server or world.

- `icon`:

> A crowned robot head suggests a mod about robots or automation, possibly adding controllable or "king" and boss-style robot mobs, or a tool that automates tasks like mining and crafting.

- `icon`:

> This mod probably adds a robot or golem "king" boss or companion, a crowned robot with glowing cyan eyes. It likely brings in new mechanical mobs, bosses, or automation-themed content.

**grasp**

- `icon`:

> A mechanical claw or hand hovers over a grass block with dotted lines running down to it, so I think this mod lets you pick up, move, or place blocks remotely. It could be a telekinesis or block-manipulation tool, or an automated block placer or breaker.

- `icon`:

> Judging by the icon, a mechanical claw or hand hangs over a grass block with beams pointing down at it. The mod probably lets you pick up, move or place blocks or terrain remotely, something like a block-manipulation or terraforming tool.

- `icon`:

> A mechanical claw or hand hovers over a grass block and appears to be lifting or moving it, so the mod probably lets you pick up, move, or transport blocks or chunks of terrain. Examples would be a block-moving tool, a world-editing tool, or a mod that can relocate land.

**overseer**

- `icon`:

> This mod likely adds a mystical or "all-seeing" structure, perhaps a pyramid or temple, with Illuminati-style themes. It might add a new dimension, boss, or structure with eye-related items, or let you see or track players and mobs through an "all-seeing eye" mechanic.

- `icon`:

> It looks like a mod built around an "all-seeing eye" pyramid, probably a Illuminati or Eye of Providence theme. It likely adds a stone-brick pyramid structure or altar with mystical or surveillance features, such as an observation or detection block, or a structure to explore.

- `icon`:

> An all-seeing eye on top of a stone pyramid suggests a mod that adds pyramid or temple structures, probably with mysterious or "illuminati"-themed loot, bosses, or an eye-based mechanic. It could also be a mod about seeing or tracking things, such as a vision or detection ability.

**prompt** (console with a grass block, no letters)

- `icon`:

> This mod most likely adds a command-line or terminal interface to Minecraft, such as an in-game console, a command runner or a server console. The icon shows a terminal window with a ">" prompt next to a grass block.

## Screening of AI cues (one or two readers each, between rounds 0 and 1)

- spark over strings to a block:

> A grass block with a glowing star hovering above it and beams or lines descending onto it suggests a mod that lets you teleport, warp, or travel to locations. It could also be a mod that marks or claims land.

- spark over strings to a block:

> It looks like a mod that adds a way to teleport or mark waypoints. The icon shows a grass block with a glowing star hovering over it, and beams of light come down from the star onto the block, which suggests beacons, warp points, or a "send me to this location" feature.

- robot hand with a spark:

> This mod likely adds a mechanical claw or hand that hovers over a block of land and works on it, such as picking up, moving or copying chunks of terrain. It could also be an automated terraforming or block-manipulation tool.

- robot hand with a spark:

> A mechanical hand or claw hovers above a grass block and projects beams down onto it. My guess is that the mod grabs, moves or manipulates blocks remotely. It could be a block mover, a remote-control tool, or a quarry-style tool that picks up chunks of terrain.

- crown with a cyan spark over a block:

> This mod probably adds a ruling or "king" system to the game: players can claim land or become a ruler of a kingdom. The crown and gem over a grass block suggest territory ownership, ranks, or a kingdom/faction-style gameplay.

- crown with a cyan spark over a block:

> This mod probably adds a ruler, king or "crown" system to the game, for example a kingdom, ranks or titles for players. It might also work as a server-management or leaderboard-style mod that crowns a top player. The grass block under the crown suggests it's tied to ruling over land or territory.

- console with a spark:

> This mod probably lets you run commands or use a console or terminal inside Minecraft. The icon shows a terminal window with a command prompt arrow next to a grass block, and the block has what looks like a pin or marker above it, so it may also have something to do with locations or world management.

- console with a spark:

> It looks like a mod that adds a command-line or terminal interface to Minecraft. The icon shows a console window with a prompt arrow next to a grass block, so it probably lets you run commands or control the game through an in-game terminal.

- robot hand with the letters AI:

> This mod probably adds AI-controlled help to Minecraft. The icon shows a robotic hand labeled "AI" hovering over a grass block with beams pointing down at it, so it likely lets an AI agent build, edit, or otherwise manipulate the world and its blocks.

- robot hand with the letters AI:

> This mod probably adds AI-controlled features to Minecraft. The icon shows a robotic hand labeled "AI" hovering over a grass block, which suggests an AI that builds, edits or manipulates the world, for example generating structures or terrain from prompts.

- console `>AI_`:

> This mod most likely adds an AI assistant or command-line interface to Minecraft. The icon shows a terminal window with an "AI" prompt and a grass block, so it probably lets players type commands or chat with an AI to control or interact with the game.

- console `>AI_`:

> This mod probably adds an AI assistant or chatbot to Minecraft, with a command-line style interface where you type prompts or commands. It likely lets an AI help with in-game tasks or interact with the world.

- crown with AI over a block:

> This mod probably improves or replaces mob and NPC behavior with smarter AI, since the icon shows a crown labeled "AI" above a grass block. It may also add AI-driven features such as LLM-powered chat or companions.

- crown with AI over a block:

> This mod likely adds AI-controlled behavior to the game. The "AI" crown over a grass block suggests an AI "ruler" or assistant that manages or plays in the world, such as smarter NPCs or an AI player companion.

- crowned robot, AI on the crown:

> A robot wearing a crown labeled "AI" suggests a mod that adds AI-controlled companions or bots, or one that lets you command them. It might also let an AI assistant (such as an LLM) act as a "king" or boss that automates player tasks.

- crowned robot, AI on the crown:

> This mod most likely adds AI-controlled companions or robot helpers, probably a king-like or leader AI that can automate tasks or command other entities. The pixel-art robot wearing a crown labeled "AI" suggests it brings an AI assistant or ruler character into the game.
