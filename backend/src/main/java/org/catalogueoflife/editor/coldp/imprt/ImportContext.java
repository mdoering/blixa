package org.catalogueoflife.editor.coldp.imprt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.coldp.imprt.dto.ImportRunResponse;

// Mutable holder threaded through the load phases of one import run: references + authors load
// first in this task (they have no forward dependencies), Task 4 (names) and Task 5 (the
// taxon/synonym tree) extend loadTransactional to consume the sourceId -> ourId maps built here
// (e.g. resolving a NameUsage row's referenceID/authorID columns back to the ids allocated below)
// and to add their own usageIds entries. The running counts + issues collected here feed straight
// into ImportRunMapper.finish once the whole load completes (see ImportRunService.run).
// Package-private: only ImportRunService and its load-phase helpers ever see this.
class ImportContext {
  final int projectId;
  final Map<String, Integer> refIds = new HashMap<>();     // sourceRefID -> our id
  final Map<String, Integer> authorIds = new HashMap<>();   // sourceAuthorID -> our id
  final Map<String, Integer> usageIds = new HashMap<>();    // filled by Task 4
  int referenceCount;
  int authorCount;
  int nameUsageCount;
  final List<ImportRunResponse.ImportIssue> issues = new ArrayList<>();

  ImportContext(int projectId) {
    this.projectId = projectId;
  }

  void issue(String entity, String sourceId, String message) {
    issues.add(new ImportRunResponse.ImportIssue(entity, sourceId, message));
  }
}
