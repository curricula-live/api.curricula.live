#!/usr/bin/env bash
set -euo pipefail
COMMIT="abbef1de99953316bcd0663f50f3a88a20d38c11"
MANIFEST="4cf71b7d381034596432d4e373dfa8d4509412f98d700b43c47d13d493284b42"
DEST="${CURRICULA_RDF_FIXTURE:-target/ontology-fixture}"
BASE="https://raw.githubusercontent.com/curricula-live/ontology-formal-sciences/${COMMIT}/dist"
mkdir -p "$DEST"
for name in manifest.json formal-sciences.ttl formal-sciences.trig formal-sciences.nq source-alignments.trig validation-report.json; do
 curl -fLSs --retry 3 --connect-timeout 20 "${BASE}/${name}" -o "${DEST}/${name}.partial"
 mv "${DEST}/${name}.partial" "${DEST}/${name}"
done
echo "$MANIFEST  $DEST/manifest.json" | sha256sum -c -
echo "Retrieved pinned ontology fixture: $COMMIT"
