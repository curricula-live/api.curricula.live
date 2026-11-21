package live.curricula.api.planning;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import live.curricula.api.rdf.RdfOntologyRegistry;

/** Owner-scoped relational persistence. Callers obtain the owner from a verified principal. */
public final class PlanningStore {
    private static final String OWNER_PREDICATE = " WHERE id = ? AND owner_issuer = ? AND owner_subject = ?";
    private static final String PLAN_FIELDS = "id, title, concept_iri, revision";
    private final DataSource source;

    public PlanningStore(DataSource source) {
        this.source = source;
    }

    public record Account(String issuer, String subject) {
        public Account {
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
            title = title.strip();
            if (title.isEmpty() || title.codePointCount(0, title.length()) > 200
                    || title.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Title must be 1..200 printable characters");
            }
            RdfOntologyRegistry.requireAbsoluteIri(conceptIri);
            var producer = RdfOntologyRegistry.DEFAULT.ownerOf(conceptIri);
            if (producer.isEmpty() || !producer.get().localName(conceptIri).matches("[a-z0-9][-a-z0-9]*")) {
                throw new IllegalArgumentException("Expected a canonical concept IRI");
            }
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

    private static void owner(PreparedStatement statement, int index, Account owner) throws SQLException {
        statement.setString(index, owner.issuer());
        statement.setString(index + 1, owner.subject());
    }

    private static Optional<Plan> result(PreparedStatement statement) throws SQLException {
        try (var row = statement.executeQuery()) {
            if (!row.next()) return Optional.empty();
            return Optional.of(new Plan(row.getObject("id", UUID.class), row.getString("title"),
                    row.getString("concept_iri"), row.getLong("revision")));
        }
    }
}
