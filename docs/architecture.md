# Architectural boundaries

- \`ontology-*\`: canonical RDF-native source; semantic curation remains independent.
- Future \`data\` replacement: publishes audited RDF releases with stable IRIs, provenance and checksums.
- Future \`database\`: imports those RDF artifacts into Fuseki/TDB2.
- This repository: *reads* the validated dataset through a \`KnowledgeCatalog\` adapter and exposes an authenticated/controlled public API.
- \`curricula.live\`: server-rendered Java UI, consumes this API over private networking.

Do not implement Supabase-specific schemas, UUID-keyed ontology identity, or uncited semantic reconciliation in API adapters. No direct edits to a production RDF store from public endpoints.
