# RDF producer–consumer integration

The authoritative formal-sciences ontology is developed in curricula-live/ontology-formal-sciences PR #1. This Java consumer does not replicate its scientific curation, edit its RDF, or import Python staging internals.

## Pinned test fixture

- Version: 0.4.0, development release (not reviewed for production)
- Full commit: abbef1de99953316bcd0663f50f3a88a20d38c11
- Manifest SHA-256: 4cf71b7d381034596432d4e373dfa8d4509412f98d700b43c47d13d493284b42
- Contract: curricula.rdf-release/v1
- Four separate named graphs: graph/asserted, graph/inferred, graph/alignments, graph/provenance
- The default graph is intentionally empty.

The consumer verifies all artifact SHA-256 values, manifest and validation conformance, namespace, named-graph counts, TriG/N-Quads equivalence, Turtle/asserted graph identity, and the standalone alignment graph.

## Local integration test

Run: bash scripts/fetch-integration-fixture.sh
Run: CURRICULA_RDF_FIXTURE=target/ontology-fixture ./mvnw verify

To bootstrap the server with an approved pinned local package, configure these environment variables:
- CURRICULA_RDF_PACKAGE_DIR (absolute path to directory containing manifest.json)
- CURRICULA_RDF_MANIFEST_SHA256 (expected SHA-256)
- CURRICULA_RDF_COMMIT (full immutable producer commit)
- CURRICULA_RDF_STORE (persistent storage directory)

Then execute ./mvnw spring-boot:run. If none is provided, the service starts without knowledge, and returns HTTP 503 to knowledge queries rather than fabricating ontology records.

The import is a deployment operation, never a public API operation. Loading takes place in a private TDB2 staging directory, undergoes semantic-distribution validation, and activates only when fully verified. Previous datasets remain on disk for rollback via the Java service. Production must pin an accepted, reviewed immutable release, not the changing feature branch.

## Performance and operational notes

- Do not expose raw public Fuseki admin or unrestricted SPARQL Update.
- Keep datasets outside Git and back up artifacts and verified TDB2 snapshots outside Oracle.
- Treat the private API-to-web connection as an internal network connection.
- TDB2 is disk-backed, while TriG/TTL equivalence validation currently requires temporary RDF models in memory; benchmark before datasets become very large.
- Future independent producers require a generalized namespace registry and non-colliding slug resolution.
- Cloudflare may proxy Oracle-hosted MVC views, but that is not the same as serving them from Cloudflare Pages.
