package live.curricula.api.education;

import java.util.List;

public interface EducationCatalog {
    record Record(String value, String language, String graphIri) { }
    record Context(String conceptIri, boolean available, List<Record> prerequisites,
                   List<Record> curriculumMappings, List<Record> teachingNotes, boolean truncated) { }

    Context context(String conceptIri);
}
