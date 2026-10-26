#!/usr/bin/env bash
# Modrinth page from repository files (called by .github/workflows/publish.yml):
#   modrinth.sh page draft <jar>    absent project: draft (is_draft, no versions); existing: fields, icon, gallery;
#                                   only while hidden (draft, rejected, processing)
#   modrinth.sh page release <jar>  same for a reviewed project (not draft or rejected); never creates a project
#   modrinth.sh submit              submit a draft (or rejected project) for review: status processing
# page writes the project id and upload=true to GITHUB_OUTPUT if the jar (by sha1) is not already in the project.
# Fields: docs/store/modrinth.json (missing key leaves the site field untouched; null clears it); page text:
# docs/store/description.md. Skip icon/gallery files absent from the repository and already-uploaded images
# (their file sha1 appears in the Modrinth CDN URL): reruns add only new images and remove site images
# no longer listed.
# Look up the project by slug using the token: only the team can see a draft.
# Environment: MODRINTH_TOKEN; MODRINTH_API (default https://api.modrinth.com/v2; a stub for tests);
# STORE_JSON, BODY_MD override the fields and text paths.
set -euo pipefail

API=${MODRINTH_API:-https://api.modrinth.com/v2}
STORE_JSON=${STORE_JSON:-docs/store/modrinth.json}
BODY_MD=${BODY_MD:-docs/store/description.md}
OUT=${GITHUB_OUTPUT:-/dev/null}
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}

: "${MODRINTH_TOKEN:?MODRINTH_TOKEN is not set}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# Token header comes from a file, not curl arguments.
(umask 077 && printf 'Authorization: %s\n' "$MODRINTH_TOKEN" > "$tmp/auth")
UA="User-Agent: github.com/${GITHUB_REPOSITORY:-local} publish workflow"

# call METHOD PATH "CODES" [curl arguments]: response in $tmp/body, status in $code; unlisted status fails with the response.
call() {
  local method=$1 path=$2 ok=$3
  shift 3
  code=$(curl -sS -o "$tmp/body" -w '%{http_code}' -X "$method" -H @"$tmp/auth" -H "$UA" "$@" "$API$path") || code=000
  case " $ok " in *" $code "*) return 0 ;; esac
  echo "::error::Modrinth: $method $path — HTTP $code"
  cat "$tmp/body" >&2 || true
  echo >&2
  exit 1
}

# image FILE LIMIT: validate an image; ext and ctype are used for upload.
image() {
  ext=${1##*.}
  ext=${ext,,}
  case $ext in
    png) ctype=image/png ;;
    jpg|jpeg) ctype=image/jpeg ;;
    webp) ctype=image/webp ;;
    gif) ctype=image/gif ;;
    *) echo "::error::$1: expected png, jpg, webp or gif"; exit 1 ;;
  esac
  if [ "$(stat -c %s "$1")" -gt "$2" ]; then
    echo "::error::$1 exceeds $(($2 / 1024)) KiB — Modrinth limit"
    exit 1
  fi
}

slug=$(jq -r .slug "$STORE_JSON")
call GET "/project/$slug" "200 404"
status=none
if [ "$code" = 200 ]; then
  cp "$tmp/body" "$tmp/project.json"
  status=$(jq -r .status "$tmp/project.json")
fi

submit() {
  case $status in
    none) echo "::error::Modrinth has no project $slug — run draft first"; exit 1 ;;
    draft|rejected) ;;
    processing) echo "::notice::Modrinth: $slug is already under review"; return ;;
    *) echo "::notice::Modrinth: $slug — $status, nothing to submit"; return ;;
  esac
  if [ "$(jq '.versions | length' "$tmp/project.json")" = 0 ]; then
    echo "::error::Modrinth: draft $slug has no versions — run draft first"
    exit 1
  fi
  # 400 means Modrinth validation found page issues, listed in the response.
  call PATCH "/project/$(jq -r .id "$tmp/project.json")" 204 -H 'Content-Type: application/json' \
    --data '{"status":"processing"}'
  echo "Modrinth: [$slug](https://modrinth.com/mod/$slug) submitted for review" >> "$SUMMARY"
}

page() {
  local step=$1 jar=$2 id sha file i n q
  case "$step:$status" in
    draft:none)
      # New projects must be drafts: Modrinth otherwise requires versions, and only the team sees them before review.
      jq --rawfile body "$BODY_MD" '{slug, title, description, body: $body, categories,
          additional_categories: (.additional_categories // []), client_side, server_side, license_id,
          source_url, issues_url, wiki_url, discord_url, project_type: "mod", is_draft: true, initial_versions: []}
        | with_entries(select(.value != null))' "$STORE_JSON" > "$tmp/data.json"
      call POST /project 200 -F "data=<$tmp/data.json;type=application/json"
      cp "$tmp/body" "$tmp/project.json"
      echo "::notice::Modrinth: created draft $slug"
      ;;
    draft:draft | draft:rejected | draft:processing | release:approved | release:unlisted | release:private | \
      release:archived | release:processing)
      jq --rawfile body "$BODY_MD" '. as $s
        | [ "title", "description", "categories", "additional_categories", "license_id",
            "source_url", "issues_url", "wiki_url", "discord_url" ]
        | map(select(. as $k | $s | has($k)) | {(.): $s[.]}) | add + {body: $body}' "$STORE_JSON" > "$tmp/edit.json"
      call PATCH "/project/$(jq -r .id "$tmp/project.json")" 204 -H 'Content-Type: application/json' \
        --data-binary @"$tmp/edit.json"
      ;;
    release:none) echo "::error::Modrinth has no project $slug — run draft and submit first"; exit 1 ;;
    release:*) echo "::error::Modrinth: $slug — $status; release follows submit and review"; exit 1 ;;
    *) echo "::error::Modrinth: $slug — $status; draft is only for hidden projects; publish with release"; exit 1 ;;
  esac
  id=$(jq -r .id "$tmp/project.json")

  # Icon: if absent, upload later; if the same file is already on the site, leave it unchanged.
  file=$(jq -r '.icon // empty' "$STORE_JSON")
  if [ -n "$file" ] && [ -f "$file" ]; then
    sha=$(sha1sum "$file" | cut -d' ' -f1)
    if jq -e --arg s "$sha" '(.icon_url // "") | contains($s)' "$tmp/project.json" > /dev/null; then
      echo "icon: unchanged"
    else
      image "$file" 524288
      call PATCH "/project/$id/icon?ext=$ext" 204 -H "Content-Type: $ctype" --data-binary @"$file"
      echo "icon: $file"
    fi
  elif [ -n "$file" ]; then
    echo "::notice::Modrinth: icon $file is not in the repository yet"
  fi

  # Gallery: new images only (Modrinth rejects duplicates); captions for uploaded images are on the site.
  n=$(jq '.gallery // [] | length' "$STORE_JSON")
  for ((i = 0; i < n; i++)); do
    file=$(jq -r ".gallery[$i].file" "$STORE_JSON")
    if [ ! -f "$file" ]; then
      echo "::notice::Modrinth: image $file is not in the repository yet"
      continue
    fi
    sha=$(sha1sum "$file" | cut -d' ' -f1)
    if jq -e --arg s "$sha" 'any(.gallery[]?; ((.url // "") + " " + (.raw_url // "")) | contains($s))' \
        "$tmp/project.json" > /dev/null; then
      echo "gallery: $file already exists"
      continue
    fi
    image "$file" 5242880
    q=$(jq -r --argjson i "$i" '.gallery[$i] | "featured=\(.featured // false)"
      + (if .title then "&title=\(.title | @uri)" else "" end)
      + (if .description then "&description=\(.description | @uri)" else "" end)' "$STORE_JSON")
    call POST "/project/$id/gallery?ext=$ext&ordering=$i&$q" 204 -H "Content-Type: $ctype" --data-binary @"$file"
    echo "gallery: $file"
  done

  # Site gallery must match modrinth.json: remove images whose sha1 is absent from its files.
  local keep=() url k found
  for ((i = 0; i < n; i++)); do
    file=$(jq -r ".gallery[$i].file" "$STORE_JSON")
    if [ -f "$file" ]; then keep+=("$(sha1sum "$file" | cut -d' ' -f1)"); fi
  done
  while IFS= read -r url; do
    found=false
    for k in "${keep[@]}"; do
      case $url in *"$k"*) found=true ;; esac
    done
    if [ "$found" = false ]; then
      call DELETE "/project/$id/gallery?url=$(jq -rn --arg u "$url" '$u | @uri')" 204
      echo "gallery: removed $url"
    fi
  done < <(jq -r '.gallery[]?.url // empty' "$tmp/project.json")

  # Version: if the jar is already in the project, do not upload it again (retry after a failure).
  sha=$(sha1sum "$jar" | cut -d' ' -f1)
  call GET "/version_file/$sha?algorithm=sha1" "200 404"
  local upload=true
  if [ "$code" = 200 ]; then
    if [ "$(jq -r .project_id "$tmp/body")" != "$id" ]; then
      echo "::error::$(basename "$jar") is already uploaded to Modrinth in another project: $(jq -r .project_id "$tmp/body")"
      exit 1
    fi
    upload=false
    echo "::notice::Modrinth: $(basename "$jar") is already uploaded — version $(jq -r .version_number "$tmp/body")"
  fi

  { echo "id=$id"; echo "upload=$upload"; } >> "$OUT"
  echo "Modrinth: [$slug](https://modrinth.com/mod/$slug), $(jq -r .status "$tmp/project.json"), step $step" >> "$SUMMARY"
}

case ${1:-} in
  page) page "${2:?draft or release}" "${3:?jar}" ;;
  submit) submit ;;
  *) echo "usage: $0 page draft|release <jar> | submit" >&2; exit 2 ;;
esac
