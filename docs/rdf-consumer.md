# RDF producer–consumer integration

The Java API consumes the [formal-sciences RDF release contract](https://github.com/curricula-live/ontology-formal-sciences/blob/ed04aa8c2a39ddc89203c3905381a88d489733dc/docs/contract-v1.md). Its published package contains a manifest and five hashed artifacts. The four named graphs carry assertions, inferences, alignments and provenance. The default graph is empty. Producer review and a versioned GitHub release determine what may be used in production.

## Pinned development fixtures

| Version | Producer commit | Manifest SHA-256 | Concepts | Scientific edges | Mappings |
| --- | --- | --- | ---: | ---: | ---: |
| 0.4.0 | `abbef1de99953316bcd0663f50f3a88a20d38c11` | `4cf71b7d381034596432d4e373dfa8d4509412f98d700b43c47d13d493284b42` | 2,590 | 274 | 210 |
| 0.5.0 | `cbdf2c4de01f561a0af7e9be0a6db4e8d1fe577a` | `c7ffe29c6b34ee974187c77b73db737cbd7eb848c641daf7db963fc623173f51` | 2,667 | 399 | 219 |
| 0.6.0 | `2da2f7676dd1a68fc8dc01427a5240882c7f951a` | `ed70ec240ee39fc1b10026848b4cc4b561d50e8344323c1d69e71d8a5b337b54` | 2,751 | 534 | 230 |
| 0.7.0 | `ed04aa8c2a39ddc89203c3905381a88d489733dc` | `0f76ecfd7e9cf2e6d92d6d76b0d09523c34cf5e776442760e2f7109610424162` | 2,849 | 702 | 234 |

These snapshots are from [ontology PR #1](https://github.com/curricula-live/ontology-formal-sciences/pull/1). The last pin was the inspected PR head. When adding a fixture, pin the commit and manifest together. The test script fetches each commit through Git, extracts the RDF package and published SPARQL queries, and verifies all artifact digests. No producer Python modules are required.

```sh
bash scripts/fetch-integration-fixture.sh
CURRICULA_RDF_FIXTURE=target/ontology-fixture \
CURRICULA_RDF_FIXTURE_050=target/ontology-fixture-0.5.0 \
CURRICULA_RDF_FIXTURE_060=target/ontology-fixture-0.6.0 \
CURRICULA_RDF_FIXTURE_070=target/ontology-fixture-0.7.0 \
  ./mvnw -B -ntp verify
```

Tests cover complete named graphs, manifest-driven counts, persistence of canonical IRIs across versions, additional concepts and predicates, provenance, rollback, reactivation and JVM restart. They also execute all published competency queries against their corresponding dataset. Abrupt JVM exits test recovery at three activation stages. CI sets all fixture paths explicitly so a missing fixture fails the run.

## Store operation and recovery

Set `CURRICULA_RDF_STORE` to the persistent store directory. A service launched without an import loads the release already selected there. If the store is empty, the health endpoint reports process health and knowledge endpoints return HTTP 503. Use `/api/v1/status` to check knowledge readiness and `/api/v1/knowledge/publication` to inspect the active pin.

To activate a verified publication during a controlled startup, set:

- `CURRICULA_RDF_PACKAGE_DIR` to the absolute, local path to the published artifact directory.
- `CURRICULA_RDF_MANIFEST_SHA256` to its independently verified manifest digest.
- `CURRICULA_RDF_COMMIT` to the full immutable producer commit SHA.

The store copies the package to a private staging directory. It verifies all digests and the semantic equivalence of the distributed serializations before publishing the TDB2 release directory. Readers can continue querying the current release during parsing and semantic verification. The final selection changes both active and prior digests in one atomic rename after syncing the release files. A failed pre-activation import leaves the previous selection intact. If the final directory sync fails after the rename, the service reports the error and keeps its in-memory selection aligned with the visible file. Verify the selected digest after an I/O error or restart before continuing a rollout.

Run one service process per store on a local Linux volume supporting file locks, directory syncing, and atomic renames. Stop the old service before upgrading, since its implementation predates the process-wide store lock. The upgraded API reads `active.txt` and `previous.txt` until its first successful release switch. Thereafter `release-state` is authoritative. Use a stopped-volume snapshot if reverting to an application image that predates the new state format. Startup removes abandoned staging files and fails if the selected release is invalid. Keep the complete store and the original pinned artifacts in off-host backups. Validate each restore with a representative RDF query.

To roll back, stop the owning API process and run the offline command as an operator with access to the same store:

```sh
CURRICULA_RDF_STORE=/path/to/store java -jar target/curricula-api-0.0.1-SNAPSHOT.jar rdf-rollback
```

Restart the API using only the store configuration so startup does not reactivate a package through the import variables. Check the manifest digest via `/api/v1/knowledge/publication` and issue a representative query via the private API. Another offline rollback swaps back to the release just left. A previously installed digest keeps the producer commit recorded on its initial import when another producer commit has identical manifest bytes.

## Access boundaries and additional producers

The HTTP catalog exposes bounded read queries, and accepts canonical RDF IRIs or formal-sciences slugs on compatibility routes. RDF activation and rollback are filesystem operations performed by the deployment identity. Keep the API private behind the web application, and restrict write access to the TDB2 volume and to deployment configuration. RDF ingestion and restore require no public SPARQL endpoint.

The query registry recognizes disjoint producer namespaces and keeps a concept's canonical IRI independent of its slug or storage location. It can resolve and traverse explicitly recorded cross-source statements if those datasets are present. Persistent activation currently installs the formal-sciences v1 package. Add a new producer only after defining its immutable artifact schema, namespace and graph ownership, independent validated release record and activation policy. Reviewed alignments retain their source provenance.

User accounts and teacher data require a separate transactional store. Establish identity and ownership before migrating user records. Authentication must validate issuer, audience and session state. The API should authorize teacher operations against the specific class, plan or resource, and authorize administration separately. Deployers retain a distinct RDF publication identity.
