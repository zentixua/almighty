#!/usr/bin/env python3
"""Export Almighty's own GM notes as Agent Skills for the plugin.

The notes live in the mod jar at src/main/resources/data/almighty/almighty/notes/<name>.md, where resource paths
allow only lowercase names with underscores. Agent Skills (https://agentskills.io/specification) need a folder per
skill named like the skill, lowercase with hyphens, holding SKILL.md. This script writes
plugin/almighty/skills/<name-with-hyphens>/SKILL.md from each note, so the same text reaches harnesses where MCP
is not the way skills arrive (claude -p, the Agent SDK, Codex, Cursor, VS Code).

  python3 scripts/export_skills.py          # write
  python3 scripts/export_skills.py --check  # fail if the plugin is out of date (CI)
"""
import json
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
NOTES = ROOT / "src/main/resources/data/almighty/almighty/notes"
SKILLS = ROOT / "plugin/almighty/skills"
NAME = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")


def frontmatter(text):
    if not text.startswith("---\n"):
        raise ValueError("no frontmatter")
    end = text.index("\n---\n", 3)
    fields = {}
    for line in text[4:end].splitlines():
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip().strip('"')
    return fields, text[end + 5:].strip()


def render():
    out = {}
    for note in sorted(NOTES.glob("*.md")):
        fields, body = frontmatter(note.read_text(encoding="utf-8").replace("\r\n", "\n"))
        name = note.stem.replace("_", "-")
        if fields.get("name") != note.stem:
            raise ValueError(f"{note.name}: name must be {note.stem!r}")
        if not NAME.match(name) or len(name) > 64:
            raise ValueError(f"{note.name}: skill name {name!r} is not a valid Agent Skills name")
        description = fields.get("description", "")
        if not description or len(description) > 1024:
            raise ValueError(f"{note.name}: description must be 1-1024 characters")
        source = note.relative_to(ROOT).as_posix()
        # A JSON string is a valid YAML double-quoted scalar: no description can break the frontmatter.
        out[name] = (f"---\nname: {name}\ndescription: {json.dumps(description, ensure_ascii=False)}\n---\n\n"
                     f"<!-- Generated from {source} by scripts/export_skills.py; edit the note, not this file. -->\n\n"
                     f"{body}\n")
    return out


def versions():
    """Plugin manifests whose version differs from mod_version in gradle.properties."""
    mod = re.search(r"^mod_version=(.+)$", (ROOT / "gradle.properties").read_text(), re.M).group(1).strip()
    wrong = []
    for manifest in (ROOT / "plugin/almighty/plugin.json", ROOT / "plugin/almighty/.claude-plugin/plugin.json"):
        if json.loads(manifest.read_text(encoding="utf-8")).get("version") != mod:
            wrong.append(manifest.relative_to(ROOT).as_posix())
    return mod, wrong


def main():
    check = "--check" in sys.argv[1:]
    wanted = render()
    mod, wrong = versions()
    if wrong:
        print(f"plugin version must be mod_version {mod} in: {', '.join(wrong)}", file=sys.stderr)
        sys.exit(1)
    extra = sorted(p.relative_to(SKILLS).as_posix() for p in SKILLS.glob("*/*") if p.name != "SKILL.md") if SKILLS.is_dir() else []
    if extra:
        print(f"unexpected files in plugin skills (generated from notes only): {extra}", file=sys.stderr)
        sys.exit(1)
    present = {p.name for p in SKILLS.iterdir() if p.is_dir()} if SKILLS.is_dir() else set()
    stale = sorted(present - wanted.keys())
    changed = sorted(n for n, text in wanted.items()
                     if not (SKILLS / n / "SKILL.md").is_file() or (SKILLS / n / "SKILL.md").read_text(encoding="utf-8") != text)
    if check:
        if stale or changed:
            print(f"plugin skills out of date (stale: {stale}, changed: {changed}): run python3 scripts/export_skills.py",
                  file=sys.stderr)
            sys.exit(1)
        print(f"plugin skills up to date ({len(wanted)})")
        return
    for n in stale:
        shutil.rmtree(SKILLS / n)
    for n in changed:
        (SKILLS / n).mkdir(parents=True, exist_ok=True)
        (SKILLS / n / "SKILL.md").write_text(wanted[n], encoding="utf-8")
    print(f"wrote {len(changed)}, removed {len(stale)}, {len(wanted)} skills")


if __name__ == "__main__":
    main()
