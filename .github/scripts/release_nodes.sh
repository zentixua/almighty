#!/usr/bin/env bash
# The release a publish run uploads (called by .github/workflows/publish.yml):
#   release_nodes.sh <version|""> <step>
# Resolves the GitHub release (empty: latest), downloads each node's jar (versions/<node> → almighty-<version>+<node>.jar)
# into dist/, writes the changelog to dist/changelog.md and, to GITHUB_OUTPUT, version, environment (from
# docs/store/modrinth.json) and nodes: a JSON list with one entry per node whose jar is in the release:
#   {name, jar, version, title, loader, minecraft, java, dependencies}
# dependencies is mc-publish's list: compat_<mod> keys in the node's gradle.properties become optional relations,
# Fabric nodes require Fabric API. A node without a jar in the release is skipped with a notice.
# Environment: gh authenticated for the repository (GH_TOKEN, GH_REPO); HAS_MODRINTH, HAS_CURSEFORGE for the summary.
set -euo pipefail

VERSION=${1-}
STEP=${2:?step}
OUT=${GITHUB_OUTPUT:-/dev/null}
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}
STORE_JSON=${STORE_JSON:-docs/store/modrinth.json}

v=${VERSION#v}
[ -n "$v" ] || v=$(gh release view --json tagName -q .tagName | sed 's/^v//')
case $v in ''|*[!0-9A-Za-z.+-]*) echo "::error::Version $v is not a release number"; exit 1 ;; esac
assets=$(gh release view "v$v" --json assets -q '.assets[].name')

# prop NODE KEY: a value from versions/<node>/gradle.properties, empty if absent.
prop() {
  sed -n "s/^$2=//p" "versions/$1/gradle.properties" | head -n 1
}

# deps NODE LOADER: mc-publish dependencies, one per line.
deps() {
  local node=$1 loader=$2
  [ "$loader" != fabric ] || echo 'fabric-api(required){modrinth:fabric-api}{curseforge:fabric-api}'
  # Permission nodes: NeoForge's permission API, or lucko's fabric-permissions-api; LuckPerms implements both.
  echo 'luckperms(optional){modrinth:luckperms}{curseforge:luckperms}'
  [ -z "$(prop "$node" compat_ia)" ] ||
    echo 'immersive-aircraft(optional){modrinth:immersive-aircraft}{curseforge:immersive-aircraft}'
  [ -z "$(prop "$node" compat_sable)" ] || echo 'sable(optional){modrinth:sable}{curseforge:sable}'
  [ -z "$(prop "$node" compat_opac)" ] ||
    echo 'open-parties-and-claims(optional){modrinth:open-parties-and-claims}{curseforge:open-parties-and-claims}'
  # FTB Chunks is on CurseForge only, one project per loader.
  if [ -n "$(prop "$node" compat_ftbchunks)" ]; then
    case $loader in
      fabric) echo 'ftb-chunks(optional){curseforge:ftb-chunks-fabric}#(ignore:modrinth)' ;;
      *) echo 'ftb-chunks(optional){curseforge:ftb-chunks-forge}#(ignore:modrinth)' ;;
    esac
  fi
}

mkdir -p dist
nodes='[]'
skipped=()
for dir in versions/*/; do
  node=$(basename "$dir")
  jar="almighty-$v+$node.jar"
  if ! grep -qxF "$jar" <<< "$assets"; then
    skipped+=("$node")
    continue
  fi
  loader=${node##*-}
  minecraft=$(prop "$node" minecraft_version)
  java=$(prop "$node" java_version)
  case $loader in
    neoforge) loader_name=NeoForge ;;
    fabric) loader_name=Fabric ;;
    *) echo "::error::Node $node: unknown loader $loader"; exit 1 ;;
  esac
  if [ -z "$minecraft" ] || [ -z "$java" ]; then
    echo "::error::versions/$node/gradle.properties lacks minecraft_version or java_version"
    exit 1
  fi
  gh release download "v$v" --pattern "$jar" --dir dist --clobber
  nodes=$(jq -c --arg name "$node" --arg jar "$jar" --arg version "$v+$node" \
    --arg title "Almighty $v ($loader_name $minecraft)" --arg loader "$loader" --arg minecraft "$minecraft" \
    --arg java "$java" --arg deps "$(deps "$node" "$loader")" \
    '. + [{name: $name, jar: $jar, version: $version, title: $title, loader: $loader, minecraft: $minecraft,
           java: $java, dependencies: $deps}]' <<< "$nodes")
done
if [ "$nodes" = '[]' ]; then
  echo "::error::Release v$v has no node jar (almighty-$v+<node>.jar for a node in versions/)"
  exit 1
fi

if [ -f "docs/releases/$v.md" ]; then
  cp "docs/releases/$v.md" dist/changelog.md
  notes="docs/releases/$v.md (${GITHUB_REF_NAME:-local})"
else
  gh release view "v$v" --json body -q .body > dist/changelog.md
  notes="release body v$v"
fi
# Version environment for Modrinth and CurseForge from the page's client_side/server_side.
environment=$(jq -r '[(.client_side | {required: "client", optional: "client?"}[.] // empty),
  (.server_side | {required: "server", optional: "server?"}[.] // empty)] | join(" | ")' "$STORE_JSON")
{
  echo "version=$v"
  echo "environment=$environment"
  echo "nodes=$nodes"
} >> "$OUT"
{
  echo "Almighty $v, step $STEP; notes — $notes"
  echo "- Nodes: $(jq -r 'map(.name) | join(", ")' <<< "$nodes")"
  [ ${#skipped[@]} = 0 ] || echo "- Not in the release, skipped: ${skipped[*]}"
  [ "${HAS_MODRINTH:-}" = true ] || echo "- Modrinth skipped: missing MODRINTH_TOKEN secret"
  [ "$STEP" = draft ] || [ "${HAS_CURSEFORGE:-}" = true ] ||
    echo "- CurseForge skipped: missing CURSEFORGE_TOKEN secret or CURSEFORGE_ID variable"
} >> "$SUMMARY"
[ ${#skipped[@]} = 0 ] || echo "::notice::Not in release v$v, skipped: ${skipped[*]}"
[ "${HAS_MODRINTH:-}" = true ] || echo "::notice::Modrinth skipped: missing MODRINTH_TOKEN secret"
[ "$STEP" = draft ] || [ "${HAS_CURSEFORGE:-}" = true ] ||
  echo "::notice::CurseForge skipped: missing CURSEFORGE_TOKEN secret or CURSEFORGE_ID variable"
