#!/usr/bin/env bash
# Refresh the logged-in page fixtures from the live site using an EXISTING session cookie jar
# (does not log in, so it never registers a new device). Login-page fixtures are not refreshed here.
# Usage: DRTV_TOKEN=... DRTV_UID=... DRTV_USER=... DRTV_PASS=... capture_fixtures.sh <cookiejar> [outdir]
set -euo pipefail
jar=$1
out=${2:-app/src/test/resources/fixtures}
base=https://robin.robert-redford.net
ua="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fetch() { curl -sf --compressed -A "$ua" -b scr=1 -b "$jar" "$base$2" -o "$tmp/$1.html"; }
fetch home /
fetch list_new_films /archive/category/new-films/
fetch search_pluribus "/search/?s=pluribus"
fetch title_series_pluribus /post/series/5735053/pluribus/
fetch title_film_ouatime /post/film/5744530/once-upon-a-time-in-the-middle-east/
fetch stream_film_ouatime "/stream/371517/5744530/?lang=original&h=720"
mkdir -p "$out"
for f in "$tmp"/*.html; do
  python3 -I "$(dirname "$0")/sanitize_fixture.py" "$f" "$out/$(basename "$f")"
done
echo "fixtures written to $out"
