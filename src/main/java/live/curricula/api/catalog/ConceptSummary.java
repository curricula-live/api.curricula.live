package live.curricula.api.catalog;

/** Canonical identifiers are RDF IRIs, not legacy PostgreSQL UUIDs. */
public record ConceptSummary(String iri, String slug, String label) {}
