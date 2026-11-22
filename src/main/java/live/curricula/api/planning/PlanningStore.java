package live.curricula.api.planning;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import live.curricula.api.rdf.RdfOntologyRegistry;

/** Owner-scoped relational persistence. Callers obtain the owner from a verified principal. */
public final class PlanningStore {
    private static final String OWNER_PREDICATE = " WHERE id = ? AND owner_issuer = ? AND owner_subject = ?";
    private static final String PLAN_FIELDS = "id, title, concept_iri, revision";
    private final DataSource source;
    private final RdfOntologyRegistry registry;

    public PlanningStore(DataSource source) {
        this(source, RdfOntologyRegistry.DEFAULT);
    }

    public PlanningStore(DataSource source, RdfOntologyRegistry registry) {
        this.source = source;
        this.registry = registry;
    }

    public record Account(String issuer, String subject) {
        public Account {
            if (issuer == null || subject == null) throw new IllegalArgumentException("Missing identity claims");
            URI parsed = URI.create(issuer);
            if (issuer.length() > 2048 || !"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null
                    || subject.isBlank() || subject.length() > 255
                    || subject.chars().anyMatch(ch -> ch < 32 || ch > 126)) {
                throw new IllegalArgumentException("Invalid identity claims");
            }
        }
    }

    public record Draft(String title, String conceptIri) {
        public Draft {
            if (title == null) throw new IllegalArgumentException("Title is required");
            title = title.strip();
            if (title.isEmpty() || title.codePointCount(0, title.length()) > 200
                    || title.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Title must be 1..200 printable characters");
            }
            RdfOntologyRegistry.requireAbsoluteIri(conceptIri);
        }
    }

    public record Plan(UUID id, String title, String conceptIri, long revision) { }

    public void registerVerifiedAccount(Account owner) throws SQLException {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO curricula_planning.account (issuer, subject) VALUES (?, ?)
                     ON CONFLICT (issuer, subject) DO NOTHING
                     """)) {
            owner(statement, 1, owner);
            statement.executeUpdate();
        }
    }

    public Plan create(Account owner, Draft draft) throws SQLException {
        requireOwnedNamespace(draft);
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO curricula_planning.lesson_plan
                         (id, owner_issuer, owner_subject, title, concept_iri)
                     VALUES (?, ?, ?, ?, ?)
                     RETURNING
                     """ + PLAN_FIELDS)) {
            statement.setObject(1, UUID.randomUUID());
            owner(statement, 2, owner);
            statement.setString(4, draft.title());
            statement.setString(5, draft.conceptIri());
            return result(statement).orElseThrow(() -> new SQLException("Plan insert did not return a row"));
        }
    }

    public Optional<Plan> find(Account owner, UUID id) throws SQLException {
        try (Connection connection = source.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT " + PLAN_FIELDS + " FROM curricula_planning.lesson_plan" + OWNER_PREDICATE)) {
            statement.setObject(1, id);
            owner(statement, 2, owner);
            return result(statement);
        }
    }

    /**
     * Updates only the owner's expected revision. Zero rows covers stale, absent,
     * and inaccessible plans without disclosing which condition held.
     */
    public Optional<Plan> update(Account owner, UUID id, long expectedRevision, Draft draft) throws SQLException {
        requireOwnedNamespace(draft);
        if (expectedRevision < 1) throw new IllegalArgumentException("Invalid plan revision");
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE curricula_planning.lesson_plan
                     SET title = ?, concept_iri = ?, revision = revision + 1
                     """ + OWNER_PREDICATE + " AND revision = ? RETURNING " + PLAN_FIELDS)) {
            statement.setString(1, draft.title());
            statement.setString(2, draft.conceptIri());
            statement.setObject(3, id);
            owner(statement, 4, owner);
            statement.setLong(6, expectedRevision);
            return result(statement);
        }
    }

    public List<Plan> list(Account owner, int offset) throws SQLException {
        if (offset < 0 || offset > 10000) throw new IllegalArgumentException("Offset must be 0..10000");
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT " + PLAN_FIELDS + " " + """
                     FROM curricula_planning.lesson_plan
                     WHERE owner_issuer = ? AND owner_subject = ?
                     ORDER BY id LIMIT 100 OFFSET ?
                     """)) {
            owner(statement, 1, owner);
            statement.setInt(3, offset);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<Plan>();
                while (rows.next()) result.add(plan(rows));
                return List.copyOf(result);
            }
        }
    }

    private void requireOwnedNamespace(Draft draft) {
        var producer = registry.ownerOf(draft.conceptIri());
        if (producer.isEmpty() || !producer.get().localName(draft.conceptIri()).matches("[a-z0-9][-a-z0-9]*")) {
            throw new IllegalArgumentException("Expected a canonical concept IRI");
        }
    }

    private static void owner(PreparedStatement statement, int index, Account owner) throws SQLException {
        statement.setString(index, owner.issuer());
        statement.setString(index + 1, owner.subject());
    }

    private static Optional<Plan> result(PreparedStatement statement) throws SQLException {
        try (var row = statement.executeQuery()) {
            if (!row.next()) return Optional.empty();
            return Optional.of(plan(row));
        }
    }

    private static Plan plan(java.sql.ResultSet row) throws SQLException {
        return new Plan(row.getObject("id", UUID.class), row.getString("title"),
                row.getString("concept_iri"), row.getLong("revision"));
    }
}
