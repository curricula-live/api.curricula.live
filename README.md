# curricula.live API — Java migration

New, independent Spring Boot API replacing the legacy Django service **after feature-parity review**. The current Vercel/Django production implementation is not changed by this repository.

## Stack
- Java 25, Spring Boot 4.1.2, Maven wrapper
- REST/JSON and a hand-authored OpenAPI bootstrap contract
- Implemented: Apache Jena 6.2 with local persistent TDB2, SHA-256 verified RDF v1 releases, named-graph queries, rollback and provenance
- No Supabase, PostgreSQL or Fuseki requirement at bootstrap

## Development
```bash
./mvnw spring-boot:run
curl http://localhost:8081/actuator/health
curl http://localhost:8081/api/v1/status
./mvnw verify
```

Knowledge endpoints return **503 only when no verified canonical RDF publication is connected**. This is intentional; no fake ontology concepts are served. The `knowledgeAvailable` field becomes `true` when a pinned dataset is active. The actuator health endpoint tests process health, not RDF readiness.

## Contracts
- `openapi/openapi.yaml`: versioned bootstrap HTTP contract
- `KnowledgeCatalog`: backend port for future Jena implementation
- `ConceptSummary.iri`: canonical RDF identifier, explicitly not a legacy UUID
- No direct public Fuseki SPARQL Update or admin endpoints

## Migration checklist
Legacy Django `/v1/search/`, `/v1/concepts/`, slug detail, neighborhood, prerequisites, relations, relation types, scholarly evidence, owner auth, plus `/v2/` endpoints must be audited separately.
**The bootstrap API is not feature-complete and does not claim route compatibility.** Add regression fixtures and authoritative RDF sources before cutting over DNS.

## Oracle ARM64
Dockerfile targets Java 25 without an x86-only binary assumption. Build and run on ARM64 or build a multi-arch image. The public ingress should only expose the reverse proxy; RDF server remains private. No production deployment is configured yet.

## Real RDF integration

See docs/rdf-consumer.md for the immutable producer snapshot, required environment variables, package verification rules and end-to-end tests. The active version can be inspected at /api/v1/knowledge/publication. Relationship, neighborhood and provenance GET endpoints are implemented and documented under openapi/.
