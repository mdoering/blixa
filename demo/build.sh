#!/usr/bin/env bash
# Zip the playground ColDP folder into the frontend's static help assets, where both the docs'
# download link and the "Try it with demo data" button pick it up.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../frontend/public/help/demo/blixa-playground-felidae.zip"
mkdir -p "$(dirname "$out")"
rm -f "$out"
(cd "$here/felidae" && zip -q -X "$out" metadata.yaml *.tsv)
echo "wrote $out ($(du -h "$out" | cut -f1))"
