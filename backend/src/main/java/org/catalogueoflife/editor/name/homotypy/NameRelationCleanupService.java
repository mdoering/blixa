package org.catalogueoflife.editor.name.homotypy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import life.catalogue.api.model.Name;
import life.catalogue.basgroup.NameRelationJudge;
import life.catalogue.basgroup.NameRelationJudge.Judged;
import life.catalogue.basgroup.NameRelationJudge.PairResult;
import life.catalogue.basgroup.NameRelationRules.Rel;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.child.NameRelationService;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.SynAccLink;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.name.homotypy.dto.CleanupRequest;
import org.catalogueoflife.editor.name.homotypy.dto.CleanupRequest.RelationRef;
import org.catalogueoflife.editor.name.homotypy.dto.JudgedRelation;
import org.catalogueoflife.editor.name.homotypy.dto.PairName;
import org.catalogueoflife.editor.name.homotypy.dto.RelationConflictPair;
import org.catalogueoflife.editor.nomenclature.ClbNames;
import org.catalogueoflife.editor.nomenclature.RelTypes;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.gbif.nameparser.api.NomCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The name relation cleanup of CatalogueOfLife/data#1815: judges the stored relations pair by pair of
 * names with CLB's {@link NameRelationJudge} and lists the pairs with conflicting, redundant or
 * duplicate relations for an editor to review. The scan only reads; apply deletes the relations an editor ticked.
 */
@Service
public class NameRelationCleanupService {

  static final String DUPLICATE = "DUPLICATE";

  private static final NameRelationJudge<NameRelationResponse> JUDGE =
      new NameRelationJudge<>(NameRelationCleanupService::toRel);

  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final NameRelationMapper nameRelations;
  private final ProjectService projects;
  private final NameParserService parser;
  private final NameRelationService relationService;

  public NameRelationCleanupService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted,
      NameRelationMapper nameRelations, ProjectService projects, NameParserService parser,
      NameRelationService relationService) {
    this.relationService = relationService;
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.nameRelations = nameRelations;
    this.projects = projects;
    this.parser = parser;
  }

  /**
   * Deletes exactly the listed relations, each only if it still has the version the scan showed.
   * All or nothing: a missing or changed relation throws 409, which rolls the transaction back, so
   * no relation, audit row or (after-commit) validation event survives. Repeated identical refs are
   * deleted once; the same id with two different versions is a 400. An empty list is a 400.
   * Like {@code NameRelationService.delete} this does not look at soft locks.
   *
   * @return the number of relations deleted
   */
  @Transactional
  public int apply(int userId, int projectId, CleanupRequest req) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
    if (req == null || req.relations() == null || req.relations().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no relations given");
    }
    Map<Integer, Integer> wanted = new LinkedHashMap<>();
    for (RelationRef ref : req.relations()) {
      if (ref == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "null relation reference");
      }
      Integer prev = wanted.put(ref.id(), ref.version());
      if (prev != null && prev != ref.version()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "relation " + ref.id() + " given with two versions");
      }
    }
    int deleted = 0;
    for (Map.Entry<Integer, Integer> e : wanted.entrySet()) {
      NameRelationResponse before = nameRelations.findById(projectId, e.getKey());
      // the version of the row read must be the one the editor saw; deleteWithAudit then re-checks
      // it atomically in the DELETE itself
      if (before == null || (before.version() == null ? 0 : before.version()) != e.getValue()
          || !relationService.deleteWithAudit(userId, projectId, before)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "conflict: relation " + e.getKey() + " is missing or was changed");
      }
      deleted++;
    }
    return deleted;
  }

  /**
   * @param rootId limits the scan to relations touching the subtree of this usage, its synonyms
   *     included; null scans the whole project
   */
  public List<RelationConflictPair> scan(int userId, int projectId, @Nullable Integer rootId) {
    Project project = projects.requireVisible(userId, projectId);
    NomCode code = project.getNomCode();

    // one query each for usages, synonym links and relations, whatever the size of the project
    Map<Integer, NameUsage> byId = new HashMap<>();
    for (NameUsage u : usages.findAllByProject(projectId)) {
      byId.put(u.getId(), u);
    }
    Set<Integer> scope = null;
    if (rootId != null) {
      if (!byId.containsKey(rootId)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
      }
      scope = subtreeWithSynonyms(projectId, rootId);
    }

    // Skip relations without a related usage, relating a usage to itself or with a type NomRelType
    // does not know: there is no pair to judge, and a null type would break the judge's rules.
    List<NameRelationResponse> candidates = new ArrayList<>();
    for (NameRelationResponse r : nameRelations.findByProject(projectId)) {
      if (r.relatedUsageId() == null || r.relatedUsageId().equals(r.usageId())
          || RelTypes.parse(r.type()) == null) continue;
      if (scope != null && !scope.contains(r.usageId()) && !scope.contains(r.relatedUsageId())) continue;
      candidates.add(r);
    }

    // the candidate relations of each pair of names, in the order the judge sees them
    Map<Set<Integer>, List<NameRelationResponse>> byPair = new HashMap<>();
    for (NameRelationResponse r : candidates) {
      byPair.computeIfAbsent(Set.of(r.usageId(), r.relatedUsageId()), k -> new ArrayList<>()).add(r);
    }

    // Names are mapped lazily, only for usages the candidates relate. Unknown ids give null, which
    // makes the judge skip the pair (a relation to a usage that no longer exists).
    Map<String, Name> names = new HashMap<>();
    List<PairResult<NameRelationResponse>> results = JUDGE.judge(candidates, id -> {
      NameUsage u = byId.get(Integer.valueOf(id));
      return u == null ? null : names.computeIfAbsent(id, k -> ClbNames.toName(u, code));
    });

    List<RelationConflictPair> pairs = new ArrayList<>();
    for (PairResult<NameRelationResponse> result : results) {
      NameUsage u1 = byId.get(Integer.valueOf(result.n1().getId()));
      NameUsage u2 = byId.get(Integer.valueOf(result.n2().getId()));
      List<NameRelationResponse> rows = byPair.get(Set.of(u1.getId(), u2.getId()));
      pairs.add(new RelationConflictPair(pairName(u1, code), pairName(u2, code),
          judged(rows, result.verdicts())));
    }
    return pairs;
  }

  /**
   * Shows every relation of the pair once: with all issues of its verdicts and {@code remove} if any
   * verdict removes it. Rows repeating the same type and direction form a duplicate set. The judge
   * keeps the first of a set, but a later row may be the only one with a reference, page or remarks,
   * so the row kept here is the one with the most of them filled, then the lowest id; the verdicts on
   * the judge's row apply to the whole set.
   */
  private static List<JudgedRelation> judged(List<NameRelationResponse> rows,
      List<Judged<NameRelationResponse>> verdicts) {
    Map<Integer, List<Judged<NameRelationResponse>>> verdictsById = new HashMap<>();
    for (Judged<NameRelationResponse> v : verdicts) {
      verdictsById.computeIfAbsent(v.relation().id(), k -> new ArrayList<>()).add(v);
    }
    Map<Rel, List<NameRelationResponse>> sets = new LinkedHashMap<>();
    for (NameRelationResponse r : rows) {
      sets.computeIfAbsent(toRel(r), k -> new ArrayList<>()).add(r);
    }

    List<JudgedRelation> out = new ArrayList<>();
    for (List<NameRelationResponse> set : sets.values()) {
      List<Judged<NameRelationResponse>> setVerdicts = verdictsById.getOrDefault(set.get(0).id(), List.of());
      Set<String> issues = new LinkedHashSet<>();
      boolean remove = false;
      for (Judged<NameRelationResponse> v : setVerdicts) {
        issues.add(v.issue().name());
        remove |= v.remove();
      }
      NameRelationResponse kept = set.stream()
          .min(Comparator.comparingInt(NameRelationCleanupService::filled).reversed()
              .thenComparingInt(NameRelationResponse::id))
          .orElseThrow();
      for (NameRelationResponse r : set) {
        if (r == kept) {
          out.add(judged(r, List.copyOf(issues), remove, false, remove));
        } else {
          List<String> dupIssues = new ArrayList<>(issues);
          dupIssues.add(DUPLICATE);
          out.add(judged(r, dupIssues, remove, true, remove || !addsContent(r, kept)));
        }
      }
    }
    out.sort(Comparator.comparingInt(JudgedRelation::id));
    return out;
  }

  private static JudgedRelation judged(NameRelationResponse r, List<String> issues, boolean remove,
      boolean duplicate, boolean preselected) {
    return new JudgedRelation(r.id(), r.version() == null ? 0 : r.version(), r.type(), r.usageId(),
        r.relatedUsageId(), r.referenceId(), r.page(), r.remarks(), issues, remove, duplicate, preselected);
  }

  /** How many of reference, page and remarks the relation has. */
  private static int filled(NameRelationResponse r) {
    return (r.referenceId() != null ? 1 : 0) + (notBlank(r.page()) ? 1 : 0) + (notBlank(r.remarks()) ? 1 : 0);
  }

  /** True if the duplicate holds a reference, page or remarks the kept row does not have. */
  private static boolean addsContent(NameRelationResponse dup, NameRelationResponse kept) {
    return dup.referenceId() != null && !dup.referenceId().equals(kept.referenceId())
        || notBlank(dup.page()) && !Objects.equals(dup.page().strip(), strip(kept.page()))
        || notBlank(dup.remarks()) && !Objects.equals(dup.remarks().strip(), strip(kept.remarks()));
  }

  /** The ids of the subtree of rootId (itself included) plus the synonyms of those ids. */
  private Set<Integer> subtreeWithSynonyms(int projectId, int rootId) {
    Set<Integer> subtree = new HashSet<>(usages.findSubtreeIds(projectId, rootId));
    Set<Integer> scope = new HashSet<>(subtree);
    for (SynAccLink link : synonymAccepted.findAllLinks(projectId)) {
      if (subtree.contains(link.acceptedId())) {
        scope.add(link.synonymId());
      }
    }
    return scope;
  }

  private PairName pairName(NameUsage u, @Nullable NomCode code) {
    return new PairName(u.getId(), parser.formatName(u, code, false),
        u.getStatus() == null ? null : u.getStatus().name());
  }

  private static Rel toRel(NameRelationResponse r) {
    return new Rel(RelTypes.parse(r.type()), String.valueOf(r.usageId()), String.valueOf(r.relatedUsageId()));
  }

  private static boolean notBlank(@Nullable String s) {
    return s != null && !s.isBlank();
  }

  @Nullable
  private static String strip(@Nullable String s) {
    return s == null ? null : s.strip();
  }
}
