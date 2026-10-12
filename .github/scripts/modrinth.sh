#!/usr/bin/env bash
# Страница Modrinth из файлов репозитория (зовёт .github/workflows/publish.yml):
#   modrinth.sh page draft <jar>    нет проекта — черновик (is_draft, без версий); есть — поля, иконка, галерея;
#                                   только пока проект скрыт (draft, rejected, processing)
#   modrinth.sh page release <jar>  то же для проекта после проверки (не draft и не rejected); новый не создаёт
#   modrinth.sh submit              черновик (или отклонённый) — на проверку: status processing
# page пишет в GITHUB_OUTPUT id проекта и upload=true, если этого jar (по sha1) в проекте ещё нет.
# Поля — docs/store/modrinth.json (ключа нет — поле на сайте не трогаем, null — очищаем), текст страницы —
# docs/store/description.md. Иконка и картинки галереи, которых ещё нет в репозитории, пропускаются, уже выложенные
# (sha1 файла есть в их адресе на CDN Modrinth) — тоже: повторный запуск дописывает только новое, а картинки на
# сайте, которых в списке больше нет, убирает.
# Проект ищется по slug с токеном: черновик виден только его команде.
# Окружение: MODRINTH_TOKEN; MODRINTH_API (по умолчанию https://api.modrinth.com/v2; для проверки — заглушка);
# STORE_JSON, BODY_MD — другие пути к полям и тексту.
set -euo pipefail

API=${MODRINTH_API:-https://api.modrinth.com/v2}
STORE_JSON=${STORE_JSON:-docs/store/modrinth.json}
BODY_MD=${BODY_MD:-docs/store/description.md}
OUT=${GITHUB_OUTPUT:-/dev/null}
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}

: "${MODRINTH_TOKEN:?MODRINTH_TOKEN не задан}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
# токен — заголовком из файла, не в аргументах curl
(umask 077 && printf 'Authorization: %s\n' "$MODRINTH_TOKEN" > "$tmp/auth")
UA="User-Agent: github.com/${GITHUB_REPOSITORY:-local} publish workflow"

# call МЕТОД ПУТЬ "КОДЫ" [аргументы curl] — ответ в $tmp/body, код в $code; код не из списка — ошибка с ответом
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

# image ФАЙЛ ПРЕДЕЛ — проверка картинки; ext и ctype — для загрузки
image() {
  ext=${1##*.}
  ext=${ext,,}
  case $ext in
    png) ctype=image/png ;;
    jpg|jpeg) ctype=image/jpeg ;;
    webp) ctype=image/webp ;;
    gif) ctype=image/gif ;;
    *) echo "::error::$1: нужен png, jpg, webp или gif"; exit 1 ;;
  esac
  if [ "$(stat -c %s "$1")" -gt "$2" ]; then
    echo "::error::$1 больше $(($2 / 1024)) КиБ — предел Modrinth"
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
    none) echo "::error::На Modrinth нет проекта $slug — сначала шаг draft"; exit 1 ;;
    draft|rejected) ;;
    processing) echo "::notice::Modrinth: $slug уже на проверке"; return ;;
    *) echo "::notice::Modrinth: $slug — $status, отправлять нечего"; return ;;
  esac
  if [ "$(jq '.versions | length' "$tmp/project.json")" = 0 ]; then
    echo "::error::Modrinth: у черновика $slug нет версий — сначала шаг draft"
    exit 1
  fi
  # 400 — проверка Modrinth нашла, что поправить на странице: список — в ответе
  call PATCH "/project/$(jq -r .id "$tmp/project.json")" 204 -H 'Content-Type: application/json' \
    --data '{"status":"processing"}'
  echo "Modrinth: [$slug](https://modrinth.com/mod/$slug) отправлен на проверку" >> "$SUMMARY"
}

page() {
  local step=$1 jar=$2 id sha file i n q
  case "$step:$status" in
    draft:none)
      # новый — только черновиком: без версий Modrinth иначе не создаёт, и до проверки его видит лишь команда
      jq --rawfile body "$BODY_MD" '{slug, title, description, body: $body, categories,
          additional_categories: (.additional_categories // []), client_side, server_side, license_id,
          source_url, issues_url, wiki_url, discord_url, project_type: "mod", is_draft: true, initial_versions: []}
        | with_entries(select(.value != null))' "$STORE_JSON" > "$tmp/data.json"
      call POST /project 200 -F "data=<$tmp/data.json;type=application/json"
      cp "$tmp/body" "$tmp/project.json"
      echo "::notice::Modrinth: создан черновик $slug"
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
    release:none) echo "::error::На Modrinth нет проекта $slug — сначала шаги draft и submit"; exit 1 ;;
    release:*) echo "::error::Modrinth: $slug — $status; выпуск — после шага submit и проверки"; exit 1 ;;
    *) echo "::error::Modrinth: $slug — $status; шаг draft — только пока проект скрыт, выпуск — шаг release"; exit 1 ;;
  esac
  id=$(jq -r .id "$tmp/project.json")

  # иконка: файла ещё нет — позже; тот же файл уже на сайте — не трогаем
  file=$(jq -r '.icon // empty' "$STORE_JSON")
  if [ -n "$file" ] && [ -f "$file" ]; then
    sha=$(sha1sum "$file" | cut -d' ' -f1)
    if jq -e --arg s "$sha" '(.icon_url // "") | contains($s)' "$tmp/project.json" > /dev/null; then
      echo "иконка: без изменений"
    else
      image "$file" 524288
      call PATCH "/project/$id/icon?ext=$ext" 204 -H "Content-Type: $ctype" --data-binary @"$file"
      echo "иконка: $file"
    fi
  elif [ -n "$file" ]; then
    echo "::notice::Modrinth: иконки $file в репозитории ещё нет"
  fi

  # галерея: только новые картинки (одну и ту же Modrinth дважды не берёт); подписи выложенных — на сайте
  n=$(jq '.gallery // [] | length' "$STORE_JSON")
  for ((i = 0; i < n; i++)); do
    file=$(jq -r ".gallery[$i].file" "$STORE_JSON")
    if [ ! -f "$file" ]; then
      echo "::notice::Modrinth: картинки $file в репозитории ещё нет"
      continue
    fi
    sha=$(sha1sum "$file" | cut -d' ' -f1)
    if jq -e --arg s "$sha" 'any(.gallery[]?; ((.url // "") + " " + (.raw_url // "")) | contains($s))' \
        "$tmp/project.json" > /dev/null; then
      echo "галерея: $file уже есть"
      continue
    fi
    image "$file" 5242880
    q=$(jq -r --argjson i "$i" '.gallery[$i] | "featured=\(.featured // false)"
      + (if .title then "&title=\(.title | @uri)" else "" end)
      + (if .description then "&description=\(.description | @uri)" else "" end)' "$STORE_JSON")
    call POST "/project/$id/gallery?ext=$ext&ordering=$i&$q" 204 -H "Content-Type: $ctype" --data-binary @"$file"
    echo "галерея: $file"
  done

  # галерея на сайте — ровно список modrinth.json: картинку, чьего sha1 нет среди его файлов, убираем
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
      echo "галерея: убрана $url"
    fi
  done < <(jq -r '.gallery[]?.url // empty' "$tmp/project.json")

  # версия: этот jar в проекте уже есть — второй раз не выкладываем (повторный запуск после сбоя)
  sha=$(sha1sum "$jar" | cut -d' ' -f1)
  call GET "/version_file/$sha?algorithm=sha1" "200 404"
  local upload=true
  if [ "$code" = 200 ]; then
    if [ "$(jq -r .project_id "$tmp/body")" != "$id" ]; then
      echo "::error::$(basename "$jar") уже выложен на Modrinth в другом проекте: $(jq -r .project_id "$tmp/body")"
      exit 1
    fi
    upload=false
    echo "::notice::Modrinth: $(basename "$jar") уже выложен — версия $(jq -r .version_number "$tmp/body")"
  fi

  { echo "id=$id"; echo "upload=$upload"; } >> "$OUT"
  echo "Modrinth: [$slug](https://modrinth.com/mod/$slug), $(jq -r .status "$tmp/project.json"), шаг $step" >> "$SUMMARY"
}

case ${1:-} in
  page) page "${2:?draft или release}" "${3:?jar}" ;;
  submit) submit ;;
  *) echo "использование: $0 page draft|release <jar> | submit" >&2; exit 2 ;;
esac
