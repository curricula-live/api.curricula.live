# Relational planning foundation

Planning data lives in the PostgreSQL `curricula_planning` schema. The JVM's RDF store and public knowledge handlers open no planning database connections. The optional planning endpoints are disabled by default. Public knowledge routes remain independent of the planning database.

Apply [migration 001](../src/main/resources/db/planning/001-initial.sql) once with a migrations identity and a trusted PostgreSQL client. Use `psql --set ON_ERROR_STOP=1 --single-transaction --file` with credentials supplied through the deployment's secret mechanism. Create a separate runtime role that has usage on the schema, select/insert on accounts and select/insert/update on plans. Keep schema ownership, DDL and deletions with a separate administrative identity. Use a separate database or isolated credentials and backups for this transactional data. The test uses a fresh disposable database selected explicitly via `CURRICULA_TEST_POSTGRES_URL`, `CURRICULA_TEST_POSTGRES_USER` and `CURRICULA_TEST_POSTGRES_PASSWORD`.

Each account is keyed by its verified identity provider issuer and opaque subject. The planning API derives these values from a verified RS256 access token after signature, issuer, audience, scope, and token lifetime checks. Do not populate either identity field from request data or trust an email match across issuers. The same-origin web application uses OIDC authorization-code login with PKCE, Secure HttpOnly sessions and CSRF protection. Its server forwards the access token to the private API. Local logout clears the web session. The provider controls identity lifecycle and token revocation. Rely on provider-mediated identity and document its retention and erasure behavior before accepting real accounts.

Plan reads and mutations include the full owner key. Updates check the expected revision in the same SQL statement and return no record for stale or inaccessible plans. Plan IDs are operational identifiers. Knowledge references remain canonical RDF IRIs, with publication-specific availability resolved separately from transactional storage. No RDF activation rewrites plans or accounts.


## Enable explicitly

Set `CURRICULA_PLANNING_ENABLED=true` only in an approved environment. The API requires `CURRICULA_PLANNING_ISSUER`, `CURRICULA_PLANNING_AUDIENCE`, `CURRICULA_PLANNING_DB_URL`, `CURRICULA_PLANNING_DB_USER` and `CURRICULA_PLANNING_DB_PASSWORD_FILE`. The issuer and optional `CURRICULA_PLANNING_JWK_SET_URI` use HTTPS. Tokens require the `curricula.planning` scope. The web supplies a separately configured OIDC client. Plan creation provisions the issuer/subject account key on the first verified write. There is no public user migration.
