#!/usr/bin/env python3
"""Store pages (docs/store): derive the CurseForge description and check the listing.

    python3 scripts/store.py           write docs/store/description.curseforge.md from description.md
    python3 scripts/store.py --check   fail if it is stale or the listing is inconsistent (CI)

The CurseForge page is the Modrinth page with three changes: the CurseForge header banner, plain Markdown images
instead of HTML <img> rows (CurseForge documents no HTML subset for its Markdown editor), and links to other mods' CurseForge
pages instead of their Modrinth pages. Checks: modrinth.json fields and limits, every icon, gallery and banner file
exists and fits Modrinth's size limits, images in the descriptions are absolute raw.githubusercontent URLs to files
in this repository, no relative links, and the English and Russian descriptions have the same sections.
"""
import json
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STORE = ROOT / "docs/store"
RAW = "https://raw.githubusercontent.com/zentixua/almighty/main/"
BLOB = "https://github.com/zentixua/almighty/blob/main/"

MODRINTH_BANNER = RAW + "docs/brand/store/modrinth-banner-1920x480.png"
CURSEFORGE_BANNER = RAW + "docs/brand/store/curseforge-header-1600x400.png"
# Other mods' pages: Modrinth URL → CurseForge URL.
MOD_PAGES = {
    "https://modrinth.com/mod/immersive-aircraft": "https://www.curseforge.com/minecraft/mc-mods/immersive-aircraft",
    "https://modrinth.com/mod/sable": "https://www.curseforge.com/minecraft/mc-mods/sable",
    "https://modrinth.com/mod/open-parties-and-claims":
        "https://www.curseforge.com/minecraft/mc-mods/open-parties-and-claims",
    "https://modrinth.com/mod/fabric-api": "https://www.curseforge.com/minecraft/mc-mods/fabric-api",
}
# Modrinth's current mod categories (GET https://api.modrinth.com/v2/tag/category, 27.11.2026).
MODRINTH_CATEGORIES = {
    "adventure", "cursed", "decoration", "economy", "equipment", "food", "game-mechanics", "library", "magic",
    "management", "minigame", "mobs", "optimization", "social", "storage", "technology", "transportation",
    "utility", "worldgen",
}
ICON_LIMIT = 512 * 1024          # Modrinth project icon (the limit modrinth.sh enforces)
GALLERY_LIMIT = 5 * 1024 * 1024  # Modrinth gallery image

IMG_TAG = re.compile(r'<img src="([^"]+)" alt="([^"]*)"[^>]*>')


def curseforge(text: str) -> str:
    text = text.replace(MODRINTH_BANNER, CURSEFORGE_BANNER)
    out = []
    for line in text.split("\n"):
        images = IMG_TAG.findall(line)
        if images and IMG_TAG.sub("", line).strip() == "":
            out.append("\n\n".join(f"![{alt}]({src})" for src, alt in images))
        else:
            out.append(line)
    text = "\n".join(out)
    for modrinth, cf in MOD_PAGES.items():
        text = text.replace(f"({modrinth})", f"({cf})")
    return text


def png_size(path: Path):
    head = path.read_bytes()[:24]
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def check() -> list:
    errors = []
    meta = json.loads((STORE / "modrinth.json").read_text(encoding="utf-8"))
    summary = meta.get("description", "")
    if not 1 <= len(summary) <= 256:
        errors.append(f"modrinth.json: summary is {len(summary)} characters (1 to 256)")
    listing = (STORE / "listing.md").read_text(encoding="utf-8")
    if f"({len(summary)} of 256 characters" not in listing or summary not in listing:
        errors.append(f"listing.md: summary or its length ({len(summary)} of 256 characters) differs from modrinth.json")
    cats = meta.get("categories", []) + meta.get("additional_categories", [])
    for c in cats:
        if c not in MODRINTH_CATEGORIES:
            errors.append(f"modrinth.json: unknown category {c}")
    if len(meta.get("categories", [])) > 3:
        errors.append("modrinth.json: at most 3 featured categories")
    for key in ("source_url", "issues_url", "wiki_url"):
        if meta.get(key) and not meta[key].startswith("https://github.com/zentixua/almighty"):
            errors.append(f"modrinth.json: {key} is not this repository")
    if meta.get("wiki_url", "").startswith(BLOB) and not (ROOT / meta["wiki_url"][len(BLOB):]).exists():
        errors.append(f"modrinth.json: wiki_url {meta['wiki_url']} has no file")

    files = [(meta["icon"], ICON_LIMIT)] + [(g["file"], GALLERY_LIMIT) for g in meta.get("gallery", [])]
    if meta.get("curseforge_avatar"):
        files.append((meta["curseforge_avatar"], ICON_LIMIT))
    for name, limit in files:
        path = ROOT / name
        if not path.is_file():
            errors.append(f"modrinth.json: {name} does not exist")
        elif path.stat().st_size > limit:
            errors.append(f"modrinth.json: {name} is over {limit // 1024} KiB")
    if meta.get("curseforge_avatar") and (ROOT / meta["curseforge_avatar"]).is_file():
        if png_size(ROOT / meta["curseforge_avatar"]) != (400, 400):
            errors.append("modrinth.json: the CurseForge avatar must be a 400x400 PNG")
    featured = [g for g in meta.get("gallery", []) if g.get("featured")]
    if len(featured) != 1:
        errors.append("modrinth.json: exactly one gallery image must be featured")
    for g in meta.get("gallery", []):
        if not g.get("title") or len(g["title"]) > 255:
            errors.append(f"modrinth.json: {g['file']} needs a title of at most 255 characters")
        if g.get("description") and len(g["description"]) > 2048:
            errors.append(f"modrinth.json: {g['file']} description is over 2048 characters")

    pages = ["description.md", "description.ru.md", "description.curseforge.md"]
    for page in pages:
        text = (STORE / page).read_text(encoding="utf-8")
        for url in re.findall(r"!\[[^\]]*\]\(([^)\s]+)\)|<img src=\"([^\"]+)\"", text):
            url = url[0] or url[1]
            if not url.startswith(RAW):
                errors.append(f"{page}: image {url} is not an absolute {RAW} URL")
            elif not (ROOT / url[len(RAW):]).is_file():
                errors.append(f"{page}: image {url} has no file in the repository")
        for url in re.findall(r"\]\(([^)\s]+)\)", text):
            if not re.match(r"https://", url):
                errors.append(f"{page}: link {url} is not absolute https")
            elif url.startswith(BLOB) and not (ROOT / url[len(BLOB):].split("#")[0]).exists():
                errors.append(f"{page}: link {url} has no file in the repository")

    def sections(page):
        return len(re.findall(r"^#{2,3} ", (STORE / page).read_text(encoding="utf-8"), re.M))
    if sections("description.md") != sections("description.ru.md"):
        errors.append("description.ru.md: sections differ from description.md "
                      f"({sections('description.ru.md')} against {sections('description.md')})")

    expected = curseforge((STORE / "description.md").read_text(encoding="utf-8"))
    if (STORE / "description.curseforge.md").read_text(encoding="utf-8") != expected:
        errors.append("description.curseforge.md is stale: run python3 scripts/store.py")
    if "<" in re.sub(r"`[^`]*`", "", expected):
        errors.append("description.curseforge.md: HTML left in the CurseForge page")
    return errors


def main() -> int:
    if "--check" in sys.argv[1:]:
        errors = check()
        for e in errors:
            print(f"store: {e}", file=sys.stderr)
        return 1 if errors else 0
    target = STORE / "description.curseforge.md"
    target.write_text(curseforge((STORE / "description.md").read_text(encoding="utf-8")), encoding="utf-8")
    print(f"wrote {target.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
