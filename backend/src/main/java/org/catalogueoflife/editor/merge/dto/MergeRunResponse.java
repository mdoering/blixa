package org.catalogueoflife.editor.merge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import org.catalogueoflife.editor.merge.MergeRun;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// The API-facing projection of one `merge_run` row (see MergeRun / V19__merge_run.sql): a merge
// run's status/result. Deliberately omits the raw `plan` JSON -- that's the full mapping, reviewed
// paged via MergeService.getMapping/GET .../mapping, never inlined into the run summary GET.
public record MergeRunResponse(
    Long id,
    Long sourceProjectId,
    Long targetProjectId,
    String status,
    String mode,
    Boolean transactional,
    MergeMetrics metrics,
    List<MergeIssue> issues,
    OffsetDateTime startedAt,
    OffsetDateTime plannedAt,
    OffsetDateTime finishedAt,
    String error) {

  private static final TypeReference<MergeMetrics> METRICS_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<MergeIssue>> ISSUES_TYPE = new TypeReference<>() {};

  // A per-record non-fatal problem recorded during compute-plan or apply (e.g. a NEW accepted name
  // whose source parent resolved to nothing -- "unanchored", see the plan's Self-Review notes).
  // Same (entity, sourceId, message) shape as ImportRunResponse.ImportIssue -- both are "one row's
  // worth of leftover context on an otherwise successful async job", just for different entities
  // ("name"/"reference" here vs. import's ColDP entity names).
  public record MergeIssue(String entity, String sourceId, String message) {}

  // names{new,matched,possibleHomonym,possibleFuzzy} / references{new,matched,possible} plus the
  // run-wide counts newAccepted/newSynonyms/unanchored (see the plan doc's Task 4/6 metrics). Named
  // "newCount" (not "new", a reserved word) on the wire as "new" via @JsonProperty so the JSON shape
  // matches the plan doc's `names{new,matched,...}` verbatim.
  public record MergeMetrics(
      NameCounts names,
      ReferenceCounts references,
      int newAccepted,
      int newSynonyms,
      int unanchored) {

    public record NameCounts(
        @JsonProperty("new") int newCount,
        int matched,
        int possibleHomonym,
        int possibleFuzzy) {}

    public record ReferenceCounts(
        @JsonProperty("new") int newCount,
        int matched,
        int possible) {}
  }

  // A plain ObjectMapper is passed in rather than instantiated here: unlike a MyBatis TypeHandler
  // (instantiated outside of Spring by the type-handler registry), this runs from request-handling
  // code where the application's shared Jackson 3 ObjectMapper bean is already available to
  // @Autowire and pass through -- same reasoning as ImportRunResponse.of.
  public static MergeRunResponse of(MergeRun r, ObjectMapper json) {
    MergeMetrics metrics = r.getMetrics() == null || r.getMetrics().isBlank()
        ? null
        : json.readValue(r.getMetrics(), METRICS_TYPE);
    List<MergeIssue> issues = r.getIssues() == null || r.getIssues().isBlank()
        ? List.of()
        : json.readValue(r.getIssues(), ISSUES_TYPE);
    return new MergeRunResponse(r.getId(), r.getSourceProjectId(), r.getTargetProjectId(),
        r.getStatus(), r.getMode(), r.getTransactional(), metrics, issues,
        r.getStartedAt(), r.getPlannedAt(), r.getFinishedAt(), r.getError());
  }
}
