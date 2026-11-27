#!/usr/bin/env python3
"""Write docs/guide/tools.md from the mod's tool definitions (src/main/resources/assets/almighty/mcp/tools.json).

  python3 scripts/gen_tools_md.py          # write
  python3 scripts/gen_tools_md.py --check  # fail if docs/guide/tools.md is out of date (CI)
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOLS = ROOT / "src/main/resources/assets/almighty/mcp/tools.json"
OUT = ROOT / "docs/guide/tools.md"


def compact():
    """The compact profile's tools, as Tools.COMPACT lists them."""
    import re
    java = (ROOT / "src/main/java/ua/zentix/almighty/mcp/Tools.java").read_text(encoding="utf-8")
    return re.findall(r'"(\w+)"', re.search(r"COMPACT = List\.of\(([^)]*)\)", java).group(1))


def render():
    tools = json.loads(TOOLS.read_text(encoding="utf-8"))["tools"]
    lines = ["# Tools", "",
             "<!-- Generated from src/main/resources/assets/almighty/mcp/tools.json by scripts/gen_tools_md.py; edit the JSON. -->", "",
             "The tools every connected agent gets, exactly as the server describes them (English only: this text is what",
             "the agent reads). `?profile=compact` gives small models " + str(len(compact())) + " tools ("
             + ", ".join(f"`{n}`" for n in compact()) + ")", "with one-line descriptions; `act` there combines `command`, `build` and `say`.", ""]
    lines += ["| Tool | What it does | Can change things |", "|---|---|---|"]
    for t in tools:
        lines.append(f"| [`{t['name']}`](#{t['name']}) | {t.get('title', '')} | {'no' if t['annotations'].get('readOnlyHint') else 'yes'} |")
    lines.append("")
    for t in tools:
        lines += [f"## {t['name']}", "", t["description"], ""]
        props = t["inputSchema"].get("properties", {})
        required = set(t["inputSchema"].get("required", []))
        if props:
            lines += ["| Parameter | Type | Notes |", "|---|---|---|"]
            for name, p in props.items():
                kind = p.get("type") or "/".join(x.get("type", "?") for x in p.get("anyOf", []))
                notes = []
                if name in required:
                    notes.append("required")
                if "enum" in p:
                    notes.append("one of " + ", ".join(f"`{v}`" for v in p["enum"]))
                if "default" in p:
                    notes.append(f"default `{json.dumps(p['default'])}`")
                if "description" in p:
                    notes.append(p["description"])
                lines.append(f"| `{name}` | {kind} | {'; '.join(notes)} |")
            lines.append("")
    return "\n".join(lines)


def main():
    text = render()
    if "--check" in sys.argv[1:]:
        if not OUT.is_file() or OUT.read_text(encoding="utf-8") != text:
            print("docs/guide/tools.md is out of date: run python3 scripts/gen_tools_md.py", file=sys.stderr)
            sys.exit(1)
        print("docs/guide/tools.md up to date")
        return
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
