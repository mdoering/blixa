package org.catalogueoflife.editor.validation.dto;

import java.util.Map;

// A per-project problems-view rollup: GET /api/projects/{pid}/issues/summary and the return value
// of an on-demand POST /api/projects/{pid}/revalidate (features.md "regenerate on demand"). Built
// by IssueService from IssueMapper.countByStatusSeverity's (status, severity, count) rows, summed
// along each axis -- byStatus/bySeverity keys are the lowercase API strings (e.g. "open", "error").
public record IssueSummaryResponse(long total, Map<String, Long> byStatus, Map<String, Long> bySeverity) {}
