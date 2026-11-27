# Clean rerun of the top four (round nine, corrected)

Sheet: [`scores3.png`](scores3.png). Single icons: `scores3/`. This file replaces the ranking in [`scores2.md`](scores2.md), which is contaminated (see below).

## What was wrong with round nine

- **The image files were correct.** I checked the pool map and every judge's sheet: the control "cloud-visor" is the haloless round-seven icon, and the halo cloud is a different file (the images differ across the whole cloud area).
- **The judges confused the marks.** Three near-identical cloud marks sat side by side at 64 px on one sheet. Judges 1, 3 and 7 described the haloless control as "with a halo", and the halo cloud and the control received the same clarity scores from every judge (5, 6, 5, 5, 4, 5, 6). They were copying scores across look-alike marks.
- **The exact tie at 6.24 was therefore an artifact,** not a result. The round-nine ranking of the cloud marks should not be used. The other marks on that sheet were visually distinct, but their scores were taken next to the confusable trio, so treat them with caution too.

## Clean method

- **Marks:** the top four from round nine: halo cloud, cloud with the visor, narrow cloud, and halo bot over the world.
- **Single-mark scoring:** each judge saw **one** mark only, as icon + "Almighty". Each mark had 7 fresh judges, 28 sessions in all, each a `claude -p` session in an empty directory. Same axes as before: clarity (name + icon say what the mod does), click and look, each 1–10, plus one line on what the mod does.
- **Pair test:** three fresh judges each saw the halo cloud and the cloud with the visor side by side. They were labelled A and B in a random order per judge. The question: "Which one says 'an almighty AI in Minecraft' better at a glance?" The brief asked for one pair judge; I ran three so that a single coin flip could not decide it.

## Results

| Rank | Mark | Average | Clarity | Click | Look |
|---|---|---|---|---|---|
| 1 | cloud-visor | 5.71 | 5.14 | 5.43 | 6.57 |
| 2 | halo-bot-world | 5.52 | 5.14 | 5.29 | 6.14 |
| 3 | halo-cloud | 5.33 | 4.86 | 5.00 | 6.14 |
| 4 | narrow-cloud | 5.29 | 4.57 | 5.14 | 6.14 |

**Pair test:** the halo cloud won **3 of 3** (labels randomized: judge 1: A=cloud-visor, B=halo-cloud → halo-cloud; judge 2: A=halo-cloud, B=cloud-visor → halo-cloud; judge 3: A=cloud-visor, B=halo-cloud → halo-cloud).

## What it means, honestly

- **In isolation the four are close:** 5.29–5.71, a spread of 0.42. A single judge's scores vary by about one point, so the ranking is weak evidence. The original **cloud with the visor** scores highest (5.71), mostly on look (6.57).
- **Side by side, the halo decides it.** In a head-to-head on "an almighty AI in Minecraft", every pair judge chose the **halo cloud**, because the halo adds the almighty cue.
- **What judges see:** alone, the halo cloud reads as "a robot or drone lifting a grass block … god/admin power over the world". The narrower cloud plus the halo is seen as a machine rather than a cloud. The cloud with the visor reads as "sky / weather / god-like world control". The halo bot over the world reads as "god/admin powers … a haloed robot", with no strong "AI" read this time.
- **None reaches clarity 6 in isolation.** By name + icon alone, all four stay at "god-like/admin power in Minecraft", and "AI" is mostly implied by "robot". The owner's control does not come through in any of them.

Recommendation: the clean data does not separate the cloud with the visor from the halo cloud by score. The halo cloud wins only when the two are compared directly for the target meaning. Either is defensible. If the one thing to optimize is "almighty AI", take the **halo cloud**. If it is the look in a list, the **cloud with the visor**, at the original width, edges ahead. Artem decides.

## Every single-mark judge (verbatim)

### 1. cloud-visor (5.71)

Round-seven cloud on strings with the small visor (no halo, original width).

- Judge 1 (7/6/7): Probably adds a god-like or sky-themed feature, such as weather or cloud control, or some all-powerful admin tool, but it's vague.
- Judge 2 (4/5/6): Probably a sky or weather mod, or a god-powers mod, since it shows a floating grass island under a cloud.
- Judge 3 (5/5/6): A mod that adds god-like or sky powers, such as weather control or floating islands, though the name is vague.
- Judge 4 (4/5/6): Probably adds a god-like or sky-themed feature (maybe weather or island/world control), but the name is vague.
- Judge 5 (5/5/7): Seems to add a sky-island or god-like world control feature, maybe a floating-island or cloud-themed world or power mod.
- Judge 6 (6/6/7): Probably a sky or island mod that adds a floating grass island with a cloud or storm above it, maybe god-like world control or weather powers.
- Judge 7 (5/6/7): Adds a god-like powers or sky/floating-island theme, maybe creative flight or world-control tools.

### 2. halo-bot-world (5.52)

Round-nine combo: the haloed agent looking out over the edge of the world (a grass block in front).

- Judge 1 (5/5/6): Probably a creative/admin-style mod that gives you god-like powers, such as cheats or invincibility, though the name and icon only hint at it.
- Judge 2 (7/5/6): The name is vague, but the icon shows a robot with a halo over a grass block, so I'd guess it's a creative or admin-style mod that gives god-like powers such as flying, invincibility or world editing.
- Judge 3 (5/5/6): Seems to be a god/admin-powers mod (maybe creative-style abilities or a halo-themed player buff), but the name and icon are vague.
- Judge 4 (6/6/7): Probably an admin or god-mode mod that gives you creative-style powers, such as invincibility and flying, in survival. The robot with a halo on a grass block suggests this.
- Judge 5 (5/6/6): Probably a creative or admin-style mod that gives you god-like powers, such as invincibility, flying or world control.
- Judge 6 (4/5/6): Probably adds an all-powerful admin or god-like player or helper (the robot with a halo), though it's unclear what it actually does.
- Judge 7 (4/5/6): A mod about a god-like or admin-powers robot character, maybe giving creative-style powers or an angelic helper mob, but it's unclear.

(Judge 7 wrote a stray half-sentence before its score line; its own line "4 | 5 | 6 | …" was used.)

### 3. halo-cloud (5.33)

Round-nine combo: the narrower visor cloud with a halo, working the island on strings.

- Judge 1 (4/5/6): Probably a god-like or admin mod: a robot or drone lifting a grass block, maybe for editing or moving terrain.
- Judge 2 (5/5/6): Probably a god-mode or admin-style mod that lets you lift or control the world, since the icon shows a floating grass block held by a robot or drone-like device.
- Judge 3 (5/5/6): Probably an admin or god-like power mod. The icon shows a grass block being lifted or held by a floating robot or machine, so it may be about moving or editing the world, or about creative-style abilities.
- Judge 4 (5/5/6): Probably a god-like or admin-style mod that lets you control or edit the world, perhaps terrain or land, since the icon shows a robot-like device hovering over a grass block.
- Judge 5 (4/5/6): Probably an admin or god-style mod where a floating robot or beam device lifts or edits terrain, maybe world-editing or creative tools.
- Judge 6 (6/5/6): Seems to be a mod that lets you lift or control terrain, maybe a god-like tool for moving blocks or islands, going by the floating grass block with a hovering device above it.
- Judge 7 (5/5/7): Probably a god/admin-style mod, maybe a robot or drone lifting a grass block, so something like world editing or terrain control.

### 4. narrow-cloud (5.29)

Round-nine combo: the narrower visor cloud without a halo.

- Judge 1 (4/5/6): Seems to be a sky or weather-themed mod, maybe adding cloud or sky-island features or god-like powers over the world, but it's vague.
- Judge 2 (4/5/6): Probably a sky or weather mod, maybe one that adds powerful god-like abilities or floating islands.
- Judge 3 (5/6/7): Probably a sky or god-powers mod, with a floating grass island under a cloud, adding powerful abilities or items.
- Judge 4 (5/5/6): Probably a sky or island mod that adds a floating grass island and a cloud, but "Almighty" doesn't say whether it's about gods, powers or world generation.
- Judge 5 (5/5/6): Probably adds god-like or admin powers, maybe weather or sky control given the cloud over a grass block, but the name is vague.
- Judge 6 (4/5/6): Probably a sky or weather mod, or a god-powers mod that lets you control the world, going by the floating grass island with a cloud above it.
- Judge 7 (5/5/6): Probably a god-like or sky-themed mod that gives you powerful abilities or world and weather control, going by the floating grass island under a cloud.

## Pair judges (verbatim)

- Judge 1 (A = cloud-visor, B = halo-cloud): **B.** Both show a robot-faced cloud over a floating grass block, but B adds a golden halo above the cloud. That halo gives it the "almighty" divine feel, and it still reads clearly at small sizes.
- Judge 2 (A = halo-cloud, B = cloud-visor): **A.** Both show a robot-like cloud hovering over a grass block, but A adds a golden halo above the head. That halo reads as "almighty" at a glance, and B's version is the same image without it, so it's a plainer "AI over Minecraft."
- Judge 3 (A = cloud-visor, B = halo-cloud): B. The halo over the cloud-AI face is what marks it as "almighty" (a godlike presence). Both icons already have the AI face and the Minecraft-style grass-block island, so A is missing that cue.
