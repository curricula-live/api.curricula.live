CREATE SCHEMA curricula_planning;

CREATE TABLE curricula_planning.account (
    issuer text COLLATE "C" NOT NULL CHECK (char_length(issuer) BETWEEN 1 AND 2048),
    subject text COLLATE "C" NOT NULL CHECK (char_length(subject) BETWEEN 1 AND 255),
    PRIMARY KEY (issuer, subject)
);

CREATE TABLE curricula_planning.lesson_plan (
    id uuid PRIMARY KEY,
    owner_issuer text COLLATE "C" NOT NULL,
    owner_subject text COLLATE "C" NOT NULL,
    title text NOT NULL CHECK (char_length(trim(title)) BETWEEN 1 AND 200),
    concept_iri text NOT NULL CHECK (char_length(concept_iri) BETWEEN 1 AND 2048),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    FOREIGN KEY (owner_issuer, owner_subject)
        REFERENCES curricula_planning.account (issuer, subject) ON DELETE RESTRICT
);

CREATE INDEX lesson_plan_owner_idx ON curricula_planning.lesson_plan (owner_issuer, owner_subject);
