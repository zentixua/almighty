#!/usr/bin/env python3
"""Store pages (docs/store): assemble the Modrinth and CurseForge pages and check the listing.

    python3 scripts/store.py           write docs/store/modrinth.md and docs/store/curseforge.md
    python3 scripts/store.py --check   fail if they are stale or the listing is inconsistent (CI)

Sources: description.md (English, with the images) and description.ru.md (the full Russian text, without images).
A page is the English text, a rule, then the Russian text, as on Incoming's pages. The CurseForge page differs only
in its header banner and in links to other mods' CurseForge pages. Checks: modrinth.json fields and limits, every
icon, gallery and banner file exists and fits Modrinth's size limits, images are absolute raw.githubusercontent URLs
to files in this repository, links are absolute, no HTML, the Russian text has the same sections and no images.
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

SEPARATOR = "\n---\n\n"


def modrinth_page(en: str, ru: str) -> str:
    return en.rstrip("\n") + "\n" + SEPARATOR + ru


def curseforge(text: str) -> str:
    text = text.replace(MODRINTH_BANNER, CURSEFORGE_BANNER)
    for modrinth, cf in MOD_PAGES.items():
        text = text.replace(f"({modrinth})", f"({cf})")
    return text


def pages() -> dict:
    en = (STORE / "description.md").read_text(encoding="utf-8")
    ru = (STORE / "description.ru.md").read_text(encoding="utf-8")
    page = modrinth_page(en, ru)
    return {"modrinth.md": page, "curseforge.md": curseforge(page)}


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

    for name in ("description.md", "description.ru.md"):
        text = (STORE / name).read_text(encoding="utf-8")
        for url in re.findall(r"!\[[^\]]*\]\(([^)\s]+)\)", text):
            if name == "description.ru.md":
                errors.append(f"{name}: image {url}: images belong to the English part only")
            elif not url.startswith(RAW):
                errors.append(f"{name}: image {url} is not an absolute {RAW} URL")
            elif not (ROOT / url[len(RAW):]).is_file():
                errors.append(f"{name}: image {url} has no file in the repository")
        for url in re.findall(r"\]\(([^)\s]+)\)", text):
            if not url.startswith("https://"):
                errors.append(f"{name}: link {url} is not absolute https")
            elif url.startswith(BLOB) and not (ROOT / url[len(BLOB):].split("#")[0]).exists():
                errors.append(f"{name}: link {url} has no file in the repository")
        if re.search(r"<[A-Za-z/]", re.sub(r"`[^`]*`", "", text)):
            errors.append(f"{name}: HTML (the CurseForge editor documents none; use Markdown)")

    def headings(name):
        return re.findall(r"^(#{1,3}) ", (STORE / name).read_text(encoding="utf-8"), re.M)
    if headings("description.md") != headings("description.ru.md"):
        errors.append("description.ru.md: its sections differ from description.md")

    for name, text in pages().items():
        if not (STORE / name).is_file() or (STORE / name).read_text(encoding="utf-8") != text:
            errors.append(f"{name} is stale: run python3 scripts/store.py")
    return errors


def main() -> int:
    if "--check" in sys.argv[1:]:
        errors = check()
        for e in errors:
            print(f"store: {e}", file=sys.stderr)
        return 1 if errors else 0
    for name, text in pages().items():
        (STORE / name).write_text(text, encoding="utf-8")
        print(f"wrote docs/store/{name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
