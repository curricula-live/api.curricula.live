#!/usr/bin/env bash
set -euo pipefail

archive_base=https://codeload.github.com/curricula-live/ontology-formal-sciences/tar.gz
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

fetch_fixture() {
    local commit=$1 manifest=$2 destination=$3
    local checkout="$scratch/$commit"
    local prefix="ontology-formal-sciences-$commit"
    curl --fail --silent --show-error --location --retry 3 --connect-timeout 20 \
        --proto '=https' --proto-redir '=https' "$archive_base/$commit" -o "$scratch/$commit.tar.gz"
    mkdir -p "$checkout"
    tar --extract --gzip --file "$scratch/$commit.tar.gz" --directory "$checkout" --strip-components=1 \
        "$prefix/dist" "$prefix/docs/queries"
    (cd "$checkout/dist" && printf '%s  manifest.json\n' "$manifest" | sha256sum --check)
    python3 - "$checkout/dist" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

dist = Path(sys.argv[1])
artifacts = json.loads((dist / "manifest.json").read_bytes())["artifacts"]
if set(artifacts) != {f.name for f in dist.iterdir()} - {"manifest.json"}:
    sys.exit("Fixture artifact roster does not match manifest")
for file, expected in sorted(artifacts.items()):
    with (dist / file).open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
            sys.exit(f"Fixture artifact checksum mismatch: {file}")
PY
    mkdir -p "$destination" "${destination}-queries"
    cp "$checkout/dist/"* "$destination/"
    cp "$checkout/docs/queries/"*.rq "${destination}-queries/"
    echo "Verified pinned ontology fixture $commit in $destination"
}

fetch_fixture abbef1de99953316bcd0663f50f3a88a20d38c11 \
    4cf71b7d381034596432d4e373dfa8d4509412f98d700b43c47d13d493284b42 \
    "${CURRICULA_RDF_FIXTURE:-target/ontology-fixture}"
fetch_fixture cbdf2c4de01f561a0af7e9be0a6db4e8d1fe577a \
    c7ffe29c6b34ee974187c77b73db737cbd7eb848c641daf7db963fc623173f51 \
    "${CURRICULA_RDF_FIXTURE_050:-target/ontology-fixture-0.5.0}"
fetch_fixture 2da2f7676dd1a68fc8dc01427a5240882c7f951a \
    ed70ec240ee39fc1b10026848b4cc4b561d50e8344323c1d69e71d8a5b337b54 \
    "${CURRICULA_RDF_FIXTURE_060:-target/ontology-fixture-0.6.0}"
fetch_fixture ed04aa8c2a39ddc89203c3905381a88d489733dc \
    0f76ecfd7e9cf2e6d92d6d76b0d09523c34cf5e776442760e2f7109610424162 \
    "${CURRICULA_RDF_FIXTURE_070:-target/ontology-fixture-0.7.0}"
fetch_fixture 17c4c1db6901381b41bd377fb8c24c2f868c1f23 \
    25152ce02ee0b6b0cf6039b74d9d5d41cb058571cb05097845d86eb88bbaf8ba \
    "${CURRICULA_RDF_FIXTURE_080:-target/ontology-fixture-0.8.0}"
