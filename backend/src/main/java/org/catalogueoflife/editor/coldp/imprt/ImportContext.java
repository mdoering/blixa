package org.catalogueoflife.editor.coldp.imprt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  // New (our) usage ids whose primary row is a TAXON -- ACCEPTED or UNASSESSED ("provisionally
  // accepted"), never SYNONYM/MISAPPLIED (see Status.isTaxon) -- filled alongside usageIds in Task
  // 4's Pass 1 (insertPrimaryUsage). Consulted by the 5 taxon-scoped Task 5 child loaders
  // (Distribution/VernacularName/Media/SpeciesEstimate/TaxonProperty) to uphold
  // AbstractChildEntityService.requireTaxonUsage's invariant that those 5 entities only ever apply
  // to taxa -- TypeMaterial/NameRelation key off nameID and apply to any usage status, so they never
  // consult this set.
  final Set<Integer> taxonUsageIds = new HashSet<>();
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
