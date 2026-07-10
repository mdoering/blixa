package org.catalogueoflife.editor.coldp.imprt.dto;

import java.time.OffsetDateTime;
import java.util.List;
import org.catalogueoflife.editor.coldp.imprt.ImportRun;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// The API-facing projection of one `import_run` row (see ImportRun / V18__import_run.sql): an
// import run's status/result. Unlike ExportRunResponse there is no file path to omit -- import
// consumes an uploaded file rather than producing a downloadable one.
public record ImportRunResponse(
    Long id,
    Long projectId,
    String status,
    String sourceName,
    Boolean preserveIds,
    String idScope,
    Integer nameUsageCount,
    Integer referenceCount,
    Integer authorCount,
    List<ImportIssue> issues,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    String error) {

  private static final TypeReference<List<ImportIssue>> ISSUES_TYPE = new TypeReference<>() {};

  public record ImportIssue(String entity, String sourceId, String message) {}

  // A plain ObjectMapper is passed in rather than instantiated here: unlike
  // IdentifierScopeListTypeHandler (a MyBatis TypeHandler, which the type-handler registry
  // instantiates outside of Spring), this runs from request-handling code where the application's
  // shared Jackson 3 ObjectMapper bean is already available to @Autowire and pass through.
  public static ImportRunResponse of(ImportRun r, ObjectMapper json) {
    List<ImportIssue> issues = r.getIssues() == null || r.getIssues().isBlank()
        ? List.of()
        : json.readValue(r.getIssues(), ISSUES_TYPE);
    return new ImportRunResponse(r.getId(), r.getProjectId(), r.getStatus(), r.getSourceName(),
        r.getPreserveIds(), r.getIdScope(), r.getNameUsageCount(), r.getReferenceCount(),
        r.getAuthorCount(), issues, r.getStartedAt(), r.getFinishedAt(), r.getError());
  }
}
