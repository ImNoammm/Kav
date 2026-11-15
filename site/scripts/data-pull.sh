#!/bin/sh
# Puts Kav's open data (site/docs/data.md) from the "data" release on Kav's site, kavapp.link/data/v1/. Run on the
# site's server by cron each night after the workflow that builds it (.github/workflows/data.yml):
#   site/scripts/data-pull.sh <folder>      (the folder the site's web server serves at /data/)
#   site/scripts/data-pull.sh --check-manifest <manifest.json>
# The second form only checks a manifest against the rules listed at shape() below, the one list the publishing gate
# (tools/open_data.py check) applies too, by running this.
# The release's manifest.json names the night to take; that night's manifest, signature and files sit beside it under
# the night's own names, <night>.<name> (tools/data_publish.sh). A night goes in v1/<night> only if it is newer than the
# one served, its signature checks out, its manifest is exactly the shape this script knows (the nine files, each within
# its most bytes), and every file's sha256 matches. It is put together in a folder of this script's own that no one else
# can write (mktemp, 0700), each file made new where no link can lead it elsewhere (dd conv=excl) and checked a plain
# file of one link before and after its sha256. That folder becomes v1/<night>, and v1/latest, a folder of hard links to
# it, takes the old one's place in one step (mv --exchange); the night before stays. Every run also checks what latest
# serves against its signed manifest and puts right from the release what doesn't match. A night's name stands for its
# files: the release giving the name of one here to other files is refused. Nothing here is ever a symlink (Caddy
# follows them, so any found is removed), and only this script's user may write here. Exits 0 when all went well and
# latest is whole; 1 when anything didn't, latest then staying the last whole night there was and nothing of the run
# left behind. Without a jq that works, or with a latest it can't read, it stops before changing anything.
set -eu
HELPER=$(CDPATH= cd -- "${0%/*}" && pwd)/data-files.py
rm() { shift; "$HELPER" remove "$@"; }
ln() { "$HELPER" link "$@"; }
chmod() { "$HELPER" mode "$@"; }
mv() {
  shift
  if [ "$1" = --exchange ]; then shift; "$HELPER" exchange "$@"; else "$HELPER" move "$@"; fi
}
sha256sum() {
  if [ "$#" = 0 ]; then "$HELPER" hash; else shift 3; "$HELPER" check "$@"; fi
}
umask 022
RELEASE=${KAV_DATA_RELEASE:-https://github.com/ImNoammm/kav/releases/download/data}
# The public half of the data key, as .github/workflows/data.yml has it.
KEY=${KAV_DATA_PUBLIC_KEY:-REPLACE_WITH_THE_DATA_KEY_PUBLIC_HALF}
# A night's files and the most bytes each may have (tonight's are 1 KB to 28 MB), and the most for all and for the rest.
FILES="stops.csv.gz:50000000 routes.csv.gz:20000000 operators.csv.gz:1000000 roads.geojson.gz:300000000
fares.csv.gz:100000000 fares.json.gz:5000000 rail-roads.geojson.gz:50000000 busy.csv.gz:20000000 fleet.csv.gz:5000000"
TOTAL_MOST=500000000 MANIFEST_MOST=65536 SIGNATURE_MOST=1024
DEADLINE=$(( $(date +%s) + ${KAV_DATA_SECONDS:-3600} ))

fail() { echo "data: $*" >&2; exit 1; }
night() {  # <text>: whether it is a night's name, YYYYMMDDTHHMMSSZ (the shell's own matching: no tool to fail)
  case $1 in [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z) return 0 ;; *) return 1 ;; esac
}
number() {  # <night>: its digits, YYYYMMDDHHMMSS, to compare nights by (the shell's own: no tool to fail)
  n=${1:-0}; n=${n%Z}; case $n in *T*) n=${n%%T*}${n#*T} ;; esac; printf '%s' "$n"
}
names() { for pair in $FILES; do printf '%s\n' "${pair%%:*}"; done; }
regular() { [ "$(stat -c %F -- "$1" 2>/dev/null)" = "regular file" ]; }  # a file itself, not a link to one
single() { [ "$(stat -c '%F %h' -- "$1" 2>/dev/null)" = "regular file 1" ]; }  # and nowhere else under another name

# The rules a manifest must keep, every field of its type, in its range and of its shape: the one list both this script
# and the publishing gate (tools/open_data.py check) apply, the gate by running this script's --check-manifest.
#  0. exactly one JSON value in the file, nothing before or after it but white space (one());
#  1. an object with exactly the keys built, contact, docs, files, folder, format, licences, sources and version;
#  2. format the text "kav-open-data"; version the number 1 (not true, not "1");
#  3. folder a night's name, YYYYMMDDTHHMMSSZ (in a pull, the night the release's manifest.json names), and built the
#     same moment as ISO time in UTC (+00:00);
#  4. docs an https address with more after "https://" and no space; contact a mail address (text@text, no space);
#  5. licences a non-empty object, each licence exactly a name (non-empty text) and a url (an https address, as docs);
#  6. sources a non-empty object, each source a name (non-empty text) and every other value of it text;
#  7. files a list of the nine files by name (FILES), none other and none twice;
#  8. each file exactly changed, credit, licence, name, rows, sha256, size and sources, with licence_note besides if any;
#  9. sha256 64 lowercase hexadecimal digits; size a whole number from 1 to the file's most bytes (FILES); rows a whole
#     number from 1;
# 10. licence one of licences' names; credit and changed non-empty text; sources a non-empty list of sources' names;
#     licence_note, where there is one, non-empty text;
# 11. the sizes together at most TOTAL_MOST.
one() {  # <file>: its one JSON object, on one line; nothing, and failure, for no value, more than one, or not an object
  jq -cs 'if length == 1 and (.[0] | type) == "object" then .[0] else error("not exactly one JSON object") end' "$1" 2>/dev/null
}
shape() {  # <manifest> <night>: whether the manifest keeps every rule above
  list=$(names | jq -Rsc 'split("\n") | map(select(. != "")) | sort')
  most=$(for pair in $FILES; do printf '%s %s\n' "${pair%%:*}" "${pair#*:}"; done |
    jq -Rsc 'split("\n") | map(select(. != "") | split(" ") | {(.[0]): (.[1] | tonumber)}) | add')
  one "$1" | jq -e --arg folder "$2" --argjson names "$list" --argjson most "$most" --argjson total "$TOTAL_MOST" '
    def address: type == "string" and test("^https://[^ ]+$");
    def words: type == "string" and length > 0;
    def whole($least): type == "number" and . == floor and . >= $least;
    type == "object"
    and (keys == ["built", "contact", "docs", "files", "folder", "format", "licences", "sources", "version"])
    and .format == "kav-open-data" and (.version | type == "number" and . == 1)
    and (.folder | type == "string" and test("^[0-9]{8}T[0-9]{6}Z$") and . == $folder)
    and (.built | type == "string" and ((sub("\\+00:00$"; "") | gsub("[-:]"; "")) + "Z") == $folder)
    and (.docs | address)
    and (.contact | type == "string" and test("^[^@ ]+@[^@ ]+$"))
    and (.licences | type == "object" and length > 0 and all(.[]; type == "object" and keys == ["name", "url"]
      and (.name | words) and (.url | address)))
    and (.sources | type == "object" and length > 0 and all(.[]; type == "object" and (.name | words)
      and all(.[]; type == "string")))
    and (.files | type == "array" and all(.[]; type == "object") and (map(.name) | sort) == $names)
    and (.licences as $licences | .sources as $sources | all(.files[];
      ((keys - ["licence_note"]) == ["changed", "credit", "licence", "name", "rows", "sha256", "size", "sources"])
      and ((has("licence_note") | not) or (.licence_note | words))
      and (.sha256 | type == "string" and test("^[0-9a-f]{64}$"))
      and (.name as $n | .size | whole(1) and . <= $most[$n])
      and (.rows | whole(1))
      and (.licence | type == "string" and (. as $l | $licences | has($l)))
      and (.credit | words) and (.changed | words)
      and (.sources | type == "array" and length > 0 and all(.[]; type == "string" and (. as $s | $sources | has($s))))))
    and ([.files[].size] | add) <= $total' > /dev/null 2>&1
}

# Every read of a manifest goes through jq: one that doesn't run, or answers nothing, stops everything here, before
# anything is read or changed.
[ "$(printf '{"kav": [1]}' | jq -ec '.kav' 2>/dev/null)" = '[1]' ] || fail "jq doesn't run here; nothing was changed"

if [ "${1:-}" = --check-manifest ]; then
  manifest=${2:?usage: data-pull.sh --check-manifest <manifest.json>}
  shape "$manifest" "$(one "$manifest" | jq -r '.folder | strings' 2>/dev/null)" ||
    fail "$manifest doesn't keep the rules listed in data-pull.sh"
  exit 0
fi
DATA=${1:?usage: data-pull.sh <the folder served at /data/>}
V=$DATA/v1

# Only this script's user may write where the site serves from: a link someone else put there, its web server (Caddy)
# would follow.
[ ! -L "$DATA" ] && [ ! -L "$V" ] || fail "$DATA or $V is a symlink"
mkdir -p "$V"
for d in "$DATA" "$V"; do
  [ "$(stat -c %u "$d")" = "$(id -u)" ] || fail "$d isn't $(id -un)'s"
  case $(stat -c %A "$d") in ?????w*|????????w*) fail "others than $(id -un) may write in $d" ;; esac
done

# The run's own folders, each new, this user's, 0700 and empty: WORK for what is checked along the way, STAGE for the
# night being put together, SWAP for the latest taking the old one's place. All go when the run ends, and so does a
# night put in place (PLACED) that latest didn't come to. A night's folder exchanged for its new copy (EXCHANGED, the old
# one then in STAGE) is exchanged back if latest didn't come to the new one: a run that fails leaves the folder as it was.
WORK= STAGE= SWAP= PLACED= EXCHANGED=
leave() {
  cd /
  if [ -n "$EXCHANGED" ]; then
    if mv -T --exchange "$STAGE" "$V/$EXCHANGED" && [ "$STAGE/manifest.json" -ef "$WORK/staged" ]; then
      :
    else
      echo "data: $EXCHANGED's own folder couldn't be put back; it is kept in $STAGE" >&2
      STAGE=
    fi
  fi
  rm -rf ${WORK:+"$WORK"} ${STAGE:+"$STAGE"} ${SWAP:+"$SWAP"} ${PLACED:+"$PLACED"}
}
trap leave EXIT
private() {
  made=$(mktemp -d "$V/.$1.XXXXXXXX")
  if ! { [ "$(stat -c '%u %a' "$made")" = "$(id -u) 700" ] && [ -z "$(ls -A "$made")" ]; }; then
    case $made in "$V"/.?*) rmdir "$made" 2>/dev/null || : ;; esac
    fail "$made isn't a new folder of $(id -un)'s"
  fi
  echo "$made"
}
key() {  # the data key's public half, in WORK
  printf '%s' "$KEY" | base64 -d > "$WORK/key.der"
  openssl pkey -pubin -inform DER -in "$WORK/key.der" -out "$WORK/key.pem"
}
signed() {  # <manifest> <signature>: whether the data key signed that manifest
  base64 -d "$2" > "$WORK/sig" 2>/dev/null &&
    [ "$(openssl dgst -sha256 -verify "$WORK/key.pem" -signature "$WORK/sig" "$1" 2>/dev/null)" = "Verified OK" ]
}
# Whether a folder holds a whole night (<folder> <night>): its manifest signed by the data key and keeping every rule
# above, as that night, and each of its files matching its sha256 there. A night is checked so in the folder it was
# put together in before latest comes to it, and latest is after, and at each run.
whole_night() {
  for name in manifest.json manifest.json.sig $(names); do
    regular "$1/$name" || return 1
  done
  signed "$1/manifest.json" "$1/manifest.json.sig" && shape "$1/manifest.json" "$2" &&
    one "$1/manifest.json" | jq -r '.files[] | "\(.sha256)  \(.name)"' > "$WORK/whole" &&
    (cd "$1" && sha256sum --check --quiet --strict "$WORK/whole") > /dev/null 2>&1
}
served() { whole_night "$V/latest" "$current"; }
whole() {
  [ -n "$current" ] || fail "no night is served"
  served || fail "latest isn't whole: a file there doesn't match its signed manifest"
}

[ ! -L "$V/.lock" ] || rm -f "$V/.lock"
if [ "${2:-}" != --lock-open ]; then
  "$HELPER" lock "$V/.lock" "$0" "$DATA"
  exit "$?"
fi
"$HELPER" check-lock "$V/.lock"
busy=0
flock -n -E 75 9 || busy=$?
case $busy in 0|75) ;; *) fail "flock doesn't run here (exit $busy); nothing was changed" ;; esac
if [ "$busy" = 75 ]; then
  # Another run has the folder: this one changes nothing there, and succeeds only if what latest serves is whole. It
  # checks in a folder of its own outside (the other run removes any in the folder it didn't make).
  WORK=$(mktemp -d)
  key
  current=$(one "$V/latest/manifest.json" | jq -r '.folder | strings' 2>/dev/null) || current=
  night "$current" || fail "another run is at work, and latest names no night"
  whole
  echo "data: another run is at work; latest, $current, is whole"
  exit 0
fi

# Only what this script makes stays: latest and the nights, folders holding regular files of the names it knows. A
# folder a run that was killed left behind goes too.
known="manifest.json manifest.json.sig"; for pair in $FILES; do known="$known ${pair%%:*}"; done
for entry in "$V"/* "$V"/.[!.]*; do
  [ -e "$entry" ] || [ -L "$entry" ] || continue
  name=${entry##*/}
  [ "$name" != .lock ] || continue
  if [ -L "$entry" ] || [ ! -d "$entry" ] || { [ "$name" != latest ] && ! night "$name"; }; then
    echo "data: removing $name, which this script didn't make" >&2
    rm -rf "$entry"
    continue
  fi
  for f in "$entry"/* "$entry"/.[!.]*; do
    [ -e "$f" ] || [ -L "$f" ] || continue
    case " $known " in *" ${f##*/} "*) ;; *) f=; esac
    if [ -z "$f" ] || [ -L "$f" ] || [ ! -f "$f" ]; then
      echo "data: removing $name, which holds what this script didn't make" >&2
      rm -rf "$entry"
      break
    fi
  done
done

WORK=$(private work)
key

# The night served now, which latest's manifest names. A latest whose manifest can't be read, or names no night, stops
# the run with nothing changed: no read that failed or came back empty is ever a reason to delete or replace it. One
# whose night's own folder is gone stays as it is (its files are links of their own) and is checked like any other.
current=
if [ -d "$V/latest" ]; then
  current=$(one "$V/latest/manifest.json" | jq -r '.folder | strings' 2>/dev/null) || current=
  night "$current" || fail "latest's manifest can't be read, or names no night; nothing was changed"
fi

get() {  # <name in the release> <most bytes> <new file>: downloaded into a file made for it, through no link
  left=$(( DEADLINE - $(date +%s) ))
  [ "$left" -gt 0 ] || fail "out of time"
  code=$( { { curl -sSfL --max-filesize "$2" --max-time "$left" "$RELEASE/$1"; echo $? >&3; } |
            dd of="$3" conv=excl oflag=nofollow status=none; } 3>&1 ) || fail "$1 couldn't be written to a new file"
  [ "$code" = 0 ] || fail "$1 didn't come whole"
  single "$3" || fail "$1 isn't a plain file of its own"
  [ "$(wc -c < "$3")" -le "$2" ] || fail "$1 is over $2 bytes"
}

get manifest.json "$MANIFEST_MOST" "$WORK/pointer.json"
folder=$(one "$WORK/pointer.json" | jq -r '.folder | strings' 2>/dev/null) || folder=
night "$folder" || { whole; fail "the release's manifest names no night"; }
if [ "$folder" = "$current" ]; then
  # The night served, whole and as the release has it: nothing to do. Otherwise it is put right from the release.
  if cmp -s "$WORK/pointer.json" "$V/latest/manifest.json" && served; then
    exit 0
  fi
else
  [ "$(number "$folder")" -gt "$(number "$current")" ] || { whole; fail "the release names $folder, older than $current, which stays"; }
  [ "$(number "$folder")" -le "$(date -u -d '+1 day' +%Y%m%d%H%M%S)" ] || { whole; fail "the release names $folder, ahead of the clock"; }
fi

STAGE=$(private night)
cd "$STAGE"
get "$folder.manifest.json" "$MANIFEST_MOST" manifest.json
get "$folder.manifest.json.sig" "$SIGNATURE_MOST" manifest.json.sig
signed manifest.json manifest.json.sig || fail "$folder.manifest.json isn't signed by the data key"

# A night's name stands for its files: a night here by this name, signed, must be this very one.
kept=$V/$folder/manifest.json
if regular "$kept" && regular "$kept.sig" && signed "$kept" "$kept.sig" && ! cmp -s manifest.json "$kept"; then
  whole
  fail "the release gives $folder to other files than the $folder here, which stays: a night's name is never given again"
fi

# Exactly the shape the generator writes (the rules at shape()).
shape manifest.json "$folder" ||
  fail "$folder.manifest.json isn't the shape this script takes"

# Each file: a copy of the one served when that is the same (no download; its time kept, so Caddy's ETag for it stays
# and Cloudflare keeps its copy), else downloaded under the night's own name, no larger than the manifest says.
one manifest.json | jq -r '.files[] | "\(.name) \(.size) \(.sha256)"' > "$WORK/list"
while read -r name size sum; do
  if [ -n "$current" ] && regular "$V/latest/$name" && [ "$(sha256sum < "$V/latest/$name" | cut -d' ' -f1)" = "$sum" ]; then
    dd if="$V/latest/$name" iflag=nofollow of="$name" conv=excl oflag=nofollow status=none || fail "$name couldn't be copied"
    touch -r "$V/latest/$name" "$name"
  else
    get "$folder.$name" "$size" "$name"
  fi
done < "$WORK/list"
one manifest.json | jq -r '.files[] | "\(.sha256)  \(.name)"' > "$WORK/sums"
for name in manifest.json manifest.json.sig $(names); do single "$name" || fail "$name isn't a plain file of its own"; done
sha256sum --check --quiet --strict "$WORK/sums" || fail "a file of $folder doesn't match its sha256"
for name in manifest.json manifest.json.sig $(names); do single "$name" || fail "$name changed while it was checked"; done
[ "$(ls -A | wc -l)" -eq 11 ] || fail "the folder $folder was put together in holds more than its files"
# The night whole where it was put together, by the very check latest gets: latest is touched only after it passes.
whole_night "$STAGE" "$folder" || fail "$folder isn't a whole night where it was put together; latest stays as it was"
chmod 0755 .
cd /

# In place: the night's folder takes the place of one by its name in one step (mv --exchange), the old one kept in STAGE
# until latest has come to the new one; with none there, it is moved in. Nothing is removed before what replaces it is
# in place, and each move is checked by the shell itself (-ef: the same file), not by what the tool said.
ln "$STAGE/manifest.json" "$WORK/staged"
if [ -d "$V/$folder" ]; then
  mv -T --exchange "$STAGE" "$V/$folder"
  [ "$V/$folder/manifest.json" -ef "$WORK/staged" ] || fail "$folder's new folder couldn't be put in place"
  EXCHANGED=$folder
else
  mv -T "$STAGE" "$V/$folder"
  [ "$V/$folder/manifest.json" -ef "$WORK/staged" ] && [ ! -e "$STAGE" ] || fail "$folder couldn't be put in place"
  STAGE=
  PLACED=$V/$folder
fi
# Then latest swapped for a folder of hard links to it in one step, once that folder is checked whole too.
SWAP=$(private latest)
ln "$V/$folder"/* "$SWAP/"
chmod 0755 "$SWAP"
whole_night "$SWAP" "$folder" || fail "the new latest for $folder isn't whole; latest stays as it was"
if [ -d "$V/latest" ]; then
  mv -T --exchange "$SWAP" "$V/latest"
else
  mv -T "$SWAP" "$V/latest"
  SWAP=
fi
[ "$V/latest/manifest.json" -ef "$WORK/staged" ] || fail "latest couldn't be switched to $folder; it stays as it was"
EXCHANGED= PLACED=
# This night and the one before stay; older ones go (which is older decided by the shell alone).
current=$folder
before=
for d in "$V"/[0-9]*T*Z; do
  if [ -d "$d" ] && [ "$(number "${d##*/}")" -lt "$(number "$current")" ]; then before=${d##*/}; fi
done
for d in "$V"/[0-9]*T*Z; do
  [ -d "$d" ] || continue
  case ${d##*/} in "$current"|"$before") ;; *)
    rm -rf "$d" && [ ! -e "$d" ] || fail "$folder is served, but the older night ${d##*/} couldn't be removed" ;;
  esac
done
served || fail "$folder is served, checked whole before the switch, but checking it again after failed"
echo "data: $folder, $(du -sh "$V/latest" | cut -f1)"
