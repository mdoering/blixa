package org.catalogueoflife.editor.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// Builds a per-usage RuleContext, runs every registered ValidationRule against it, and reconciles
// the resulting findings against the issues already stored for that usage -- see the plan's Global
// Constraints and Task 1 Step 3 (docs/superpowers/plans/2026-07-08-phase1-validation-engine.md) for
// the exact reconcile algorithm this mirrors line for line. `rules` is injected as
// List<ValidationRule> -- Spring collects every @Component implementing the interface (see the
// `rules` sub-package for the Task-1 starter set), so adding a new rule later needs no change here.
@Service
public class ValidationService {

  private static final String ENTITY_NAME_USAGE = "name_usage";

  private final List<ValidationRule> rules;
  private final IssueMapper issues;
  private final NameUsageMapper nameUsages;
  private final ReferenceMapper references;
  private final SynonymAcceptedMapper synonymAccepted;
  private final ObjectMapper objectMapper;

  // Self-reference through the Spring proxy so revalidateProject's per-usage calls below actually
  // go through @Transactional (see revalidateProject) -- a plain `this.revalidateUsage(...)` call
  // bypasses the proxy entirely, so the whole recompute would run as ONE transaction holding every
  // per-usage pg_advisory_xact_lock until the very end (see IssueMapper.lockUsage), starving the
  // async auto-revalidate pool for the whole project's worth of usages. @Lazy avoids a circular
  // bean-creation error (this bean depending on itself while still being constructed).
  @Autowired
  @Lazy
  private ValidationService self;

  public ValidationService(List<ValidationRule> rules, IssueMapper issues, NameUsageMapper nameUsages,
      ReferenceMapper references, SynonymAcceptedMapper synonymAccepted, ObjectMapper objectMapper) {
    this.rules = rules;
    this.issues = issues;
    this.nameUsages = nameUsages;
    this.references = references;
    this.synonymAccepted = synonymAccepted;
    this.objectMapper = objectMapper;
  }

  // Re-runs every rule for one usage and reconciles the result against the issues already stored
  // for it. Idempotent: calling this twice in a row with unchanged underlying data leaves the
  // stored issue set byte-identical (see ValidationReconcileIT). A usage that no longer exists
  // (already deleted) is a no-op -- there is nothing left to validate; its issues are cleaned up
  // explicitly by the deleting service (name/NameUsageService.delete calling
  // IssueMapper.deleteByEntity inside the same transaction as the delete), not by any DB-level
  // cascade -- issue.entity_id is polymorphic and has no FK to name_usage at all.
  @Transactional
  public void revalidateUsage(int projectId, int usageId) {
    // Must be the first statement: see IssueMapper.lockUsage. Serializes this call against any
    // other concurrent revalidateUsage for the same (project, usage) -- e.g. Task 3's async
    // auto-revalidate trigger firing for a usage while an on-demand/manual revalidate for that same
    // usage is also in flight -- so the reconcile below never races itself into a duplicate INSERT.
    issues.lockUsage(projectId, usageId);
    NameUsage usage = nameUsages.findByIdInProject(projectId, usageId);
    if (usage == null) {
      return;
    }
    RuleContext ctx = buildContext(projectId, usage);
    List<Finding> current = rules.stream()
        .map(rule -> rule.evaluate(ctx))
        .flatMap(Optional::stream)
        .toList();
    List<Issue> existing = issues.findByEntity(projectId, ENTITY_NAME_USAGE, usageId);
    // Non-rule flags (e.g. the bulk multi-scope match job's <scope>_id_added / <scope>_id_updated /
    // <scope>_id_missing, stamped straight onto issue.rule -- see ColMatchJobService.matchOneScope)
    // are owned elsewhere and must never be swept by the stale loop below -- only rows whose rule
    // is a registered ValidationRule.key() are this method's business.
    Set<String> ruleKeys = rules.stream().map(ValidationRule::key).collect(Collectors.toSet());

    // for f in current: insert / reopen / updateFinding (see IssueMapper for exactly what each
    // transition touches).
    Set<String> currentRuleKeys = new HashSet<>();
    for (Finding finding : current) {
      currentRuleKeys.add(finding.rule());
      Issue existingIssue = findByRule(existing, finding.rule());
      String contextJson = toJson(finding.context());
      if (existingIssue == null) {
        insert(projectId, usageId, finding, contextJson);
      } else if (IssueStatus.DONE.name().equals(existingIssue.getStatus())) {
        issues.reopen(existingIssue.getId(), finding.severity().name(), finding.message(), contextJson);
      } else {
        issues.updateFinding(existingIssue.getId(), finding.severity().name(), finding.message(), contextJson);
      }
    }
    // for e in existing where e.rule not in current: the finding cleared.
    for (Issue existingIssue : existing) {
      if (currentRuleKeys.contains(existingIssue.getRule())) {
        continue;
      }
      if (!ruleKeys.contains(existingIssue.getRule())) {
        continue; // non-rule flags (e.g. col_*) are owned elsewhere
      }
      String status = existingIssue.getStatus();
      if (IssueStatus.ACCEPTED.name().equals(status)) {
        issues.markDone(existingIssue.getId());
      } else if (IssueStatus.OPEN.name().equals(status) || IssueStatus.REJECTED.name().equals(status)) {
        issues.deleteById(existingIssue.getId());
      }
      // status DONE stays DONE: no-op.
    }
  }

  // Iterates every usage in the project and revalidates each in turn. Note: this is O(N) queries
  // (buildContext runs a handful of selects per usage) -- acceptable for an on-demand recompute; a
  // set-based fast path is a later optimization (see plan Self-Review Notes). Calls through `self`
  // (the Spring proxy), not `this`, so each iteration is its OWN transaction: revalidateUsage's
  // @Transactional actually applies per usage, acquiring and releasing IssueMapper.lockUsage's
  // advisory lock promptly instead of holding every usage's lock for the whole recompute (see the
  // `self` field's javadoc). IssueService.revalidateProject (the caller) must NOT be @Transactional
  // itself, or this would wrap right back into one outer transaction.
  public void revalidateProject(int projectId) {
    for (int usageId : nameUsages.findIdsByProject(projectId)) {
      self.revalidateUsage(projectId, usageId);
    }
  }

  private RuleContext buildContext(int projectId, NameUsage usage) {
    int synonymAcceptedCount = synonymAccepted.countBySynonym(projectId, usage.getId());
    Reference publishedInReference = usage.getPublishedInReferenceId() == null ? null
        : references.findByIdInProject(projectId, usage.getPublishedInReferenceId());
    int duplicateCount = nameUsages.countDuplicates(projectId, usage.getScientificName(),
        usage.getAuthorship(), usage.getId());
    String ancestorGenusName = nameUsages.findAncestorGenusName(projectId, usage.getId());
    String parentRank = usage.getParentId() == null ? null
        : nameUsages.findParentRank(projectId, usage.getId());
    Integer ancestorGenusYear = nameUsages.findAncestorGenusYear(projectId, usage.getId());
    String ancestorSpeciesEpithet = nameUsages.findAncestorSpeciesEpithet(projectId, usage.getId());
    int synonymNonAcceptedTargetCount = nameUsages.countNonAcceptedSynonymTargets(projectId, usage.getId());
    return new RuleContext(usage, synonymAcceptedCount, publishedInReference, duplicateCount,
        ancestorGenusName, parentRank, ancestorGenusYear, ancestorSpeciesEpithet,
        synonymNonAcceptedTargetCount);
  }

  private void insert(int projectId, int usageId, Finding finding, String contextJson) {
    Issue issue = new Issue();
    issue.setProjectId(projectId);
    issue.setEntityType(ENTITY_NAME_USAGE);
    issue.setEntityId(usageId);
    issue.setRule(finding.rule());
    issue.setSeverity(finding.severity().name());
    issue.setMessage(finding.message());
    issue.setContext(contextJson);
    issues.insert(issue);
  }

  private static Issue findByRule(List<Issue> existing, String rule) {
    for (Issue issue : existing) {
      if (issue.getRule().equals(rule)) {
        return issue;
      }
    }
    return null;
  }

  private String toJson(Object context) {
    return context == null ? null : objectMapper.writeValueAsString(context);
  }
}
