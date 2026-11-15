#!/bin/sh
# Puts one night of open data (tools/open_data.py's output folder, its manifest signed) on the "data" release so that a
# reader never sees a mixed set. The night's files, its manifest and its signature go up under names of their own,
# <night>.<name>, which nothing ever overwrites: a name the release already has must hold these very bytes, else the run
# stops there, and such a file isn't uploaded again. They are downloaded back and compared; only then does
# manifest.json, the one name that moves, become this night's manifest. Nights before the one it replaced are deleted
# after. Stopped anywhere, the release still serves a whole night through manifest.json: the old one or the new.
#   tools/data_publish.sh <folder>      (run by .github/workflows/data.yml, with gh signed in)
set -eu
HELPER=$(CDPATH= cd -- "${0%/*}/../site/scripts" && pwd)/data-files.py
OUT=$1
TAG=${KAV_DATA_TAG:-data}
folder=$(jq -r '.folder' "$OUT/manifest.json")
printf '%s' "$folder" | grep -Eqx '[0-9]{8}T[0-9]{6}Z' || { echo "no night in $OUT/manifest.json" >&2; exit 1; }
names="$(jq -r '.files[].name' "$OUT/manifest.json") manifest.json manifest.json.sig"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

gh release view "$TAG" > /dev/null 2>&1 || gh release create "$TAG" --latest=false --title "Open data" \
  --notes "Israel's stops, lines, roads and fares as Kav reads them, rebuilt each night: manifest.json names the night and its files, <night>.<file>. What each file holds and its licence: https://kavapp.link/docs/data"
# The night it replaces, kept beside it for whoever is halfway through it.
previous=$(gh release download "$TAG" --pattern manifest.json --output - 2>/dev/null | jq -r '.folder' 2>/dev/null || true)

mkdir "$STAGE/up" "$STAGE/back" "$STAGE/had"
for name in $names; do cp "$OUT/$name" "$STAGE/up/$folder.$name"; done
# A night's name stands for its bytes: whatever the release already has under it must be these.
gh release download "$TAG" --pattern "$folder.*" --dir "$STAGE/had" 2> /dev/null || true
for had in "$STAGE"/had/*; do
  [ -e "$had" ] || continue
  "$HELPER" compare "$had" "$STAGE/up/${had##*/}" || { echo "the release has ${had##*/} with other bytes: a night's name is never given again" >&2; exit 1; }
  rm "$STAGE/up/${had##*/}"
done
if [ -n "$(ls -A "$STAGE/up")" ]; then
  gh release upload "$TAG" "$STAGE"/up/*
fi
gh release download "$TAG" --pattern "$folder.*" --dir "$STAGE/back"
for name in $names; do
  "$HELPER" compare "$OUT/$name" "$STAGE/back/$folder.$name" || { echo "$folder.$name didn't come back the same" >&2; exit 1; }
done

cp "$OUT/manifest.json" "$STAGE/manifest.json"
gh release upload "$TAG" "$STAGE/manifest.json" --clobber
gh release download "$TAG" --pattern manifest.json --output - > "$STAGE/pointer.json"
"$HELPER" compare "$OUT/manifest.json" "$STAGE/pointer.json" || { echo "the release pointer did not switch" >&2; exit 1; }

gh release view "$TAG" --json assets > "$STAGE/assets.json"
"$HELPER" old-assets "$STAGE/assets.json" "$folder" "$previous" > "$STAGE/old-assets"
while IFS= read -r asset; do
  gh release delete-asset "$TAG" "$asset" --yes
done < "$STAGE/old-assets"
echo "published $folder"
