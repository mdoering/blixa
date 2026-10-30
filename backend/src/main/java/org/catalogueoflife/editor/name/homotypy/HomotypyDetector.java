package org.catalogueoflife.editor.name.homotypy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import life.catalogue.api.model.Name;
import life.catalogue.api.vocab.NomRelType;
import life.catalogue.basgroup.BasionymSorter;
import life.catalogue.basgroup.HomotypicGroup;
import life.catalogue.basgroup.HomotypicRules;
import life.catalogue.common.tax.AuthorshipNormalizer;
import life.catalogue.common.tax.SciNameNormalizer;
import life.catalogue.matching.authorship.AuthorComparator;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedGroup;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedRelation;
import org.catalogueoflife.editor.nomenclature.ClbNames;
import org.catalogueoflife.editor.nomenclature.RelTypes;
import org.gbif.nameparser.api.NameType;
import org.gbif.nameparser.api.NomCode;
import org.springframework.stereotype.Component;

/**
 * Groups names into homotypic groups with CLB's {@link BasionymSorter}, the way CLB's
 * {@code HomotypicConsolidator} does: candidates are bucketed by normalized terminal epithet and
 * each bucket is grouped by basionym-or-combination authorship ({@link AuthorComparator}, so "L."
 * and "Linnaeus" or years a few apart match). Synonymy and stored relations are the data evidence
 * that keeps original names of different genera together and keeps known misspellings from
 * becoming the basionym. Each group proposes the relations the consolidator would create, minus
 * those between names the stored relations already link or relate as later homonyms. Stateless,
 * no DB.
 */
@Component
public class HomotypyDetector {

  private static final AuthorComparator AUTHORS = new AuthorComparator(AuthorshipNormalizer.INSTANCE);
  private static final BasionymSorter<Name> SORTER = new BasionymSorter<>(AUTHORS, n -> 0);

  private static final String BASIONYM = RelTypes.label(NomRelType.BASIONYM);
  private static final String HOMOTYPIC = RelTypes.label(NomRelType.HOMOTYPIC);
  private static final String SPELLING_CORRECTION = RelTypes.label(NomRelType.SPELLING_CORRECTION);
  private static final String BASED_ON = RelTypes.label(NomRelType.BASED_ON);

  /** Groups an accepted name with its synonyms, misapplied names excluded. */
  public HomotypyProposal detect(NameUsage accepted, List<NameUsage> synonyms, ExistingRelations existing,
      @Nullable NomCode code) {
    List<NameUsage> candidates = new ArrayList<>();
    candidates.add(accepted); // accepted first so its group lists it first
    Map<Integer, List<Integer>> acceptedBySynonym = new HashMap<>();
    for (NameUsage s : synonyms) {
      if (s.getStatus() != Status.MISAPPLIED) {
        candidates.add(s);
        acceptedBySynonym.put(s.getId(), List.of(accepted.getId()));
      }
    }
    return group(candidates, acceptedBySynonym, existing, code);
  }

  /**
   * Groups exactly the usages passed (callers pre-filter).
   *
   * @param acceptedBySynonym the accepted usage ids of each synonym, the synonymy evidence
   */
  public HomotypyProposal group(List<NameUsage> candidates, Map<Integer, List<Integer>> acceptedBySynonym,
      ExistingRelations existing, @Nullable NomCode code) {
    Map<Integer, NameUsage> usages = new HashMap<>();
    Map<String, List<Name>> buckets = new LinkedHashMap<>();
    for (NameUsage u : candidates) {
      Name n = ClbNames.toName(u, code);
      String epithet = epithetKey(n);
      if (epithet == null) continue;
      usages.put(u.getId(), u);
      buckets.computeIfAbsent(epithet, k -> new ArrayList<>()).add(n);
    }

    Linked linked = new Linked(acceptedBySynonym, existing);
    HomotypicGroup.Evidence<Name> evidence = new HomotypicGroup.Evidence<>() {
      @Override
      public boolean isVariant(Name n) {
        return existing.misspelling(id(n));
      }

      @Override
      public int penalty(Name n) {
        NameUsage u = usages.get(id(n));
        int penalty = u != null && (u.getStatus() == Status.SYNONYM || u.getStatus() == Status.MISAPPLIED) ? 1 : 0;
        return n.isAvailable() ? penalty : penalty + 1;
      }
    };

    List<ProposedGroup> groups = new ArrayList<>();
    for (var bucket : buckets.entrySet()) {
      Collection<HomotypicGroup<Name>> hgs =
          SORTER.groupBasionyms(code, bucket.getKey(), bucket.getValue(), n -> n, linked::test, evidence);
      for (HomotypicGroup<Name> hg : hgs) {
        if (!hg.isEmpty()) {
          groups.add(toProposedGroup(hg, candidates, existing));
        }
      }
    }
    return new HomotypyProposal(groups);
  }

  /**
   * The relation types and directions of {@code HomotypicConsolidator}: recombinations have the
   * basionym, other original names are spelling corrections or homotypic, and the primary name is
   * based on the based-on name. Without a basionym the recombinations are homotypic with the first.
   */
  private static ProposedGroup toProposedGroup(HomotypicGroup<Name> hg, List<NameUsage> candidates,
      ExistingRelations existing) {
    List<ProposedRelation> rels = new ArrayList<>();
    if (hg.hasBasionym()) {
      Name basionym = hg.getBasionym();
      for (Name r : hg.getRecombinations()) {
        propose(rels, r, basionym, BASIONYM, existing);
      }
      for (Name v : hg.getBasionymVariations()) {
        relateVariation(rels, basionym, v, existing);
      }
    } else if (hg.hasRecombinations()) {
      var iter = hg.getRecombinations().iterator();
      Name hom = iter.next();
      while (iter.hasNext()) {
        Name u = iter.next();
        if (!HomotypicRules.isDuplicate(u, hom)) {
          propose(rels, u, hom, HOMOTYPIC, existing);
        }
      }
    }
    if (hg.hasBasedOn()) {
      Name basedOn = hg.getBasedOn();
      propose(rels, hg.getPrimary(), basedOn, BASED_ON, existing);
      for (Name v : hg.getBasedOnVariations()) {
        relateVariation(rels, basedOn, v, existing);
      }
    }

    // members in the callers' order, so detect() lists the accepted name first
    Set<Integer> ids = new HashSet<>();
    hg.getAll().forEach(n -> ids.add(id(n)));
    if (hg.getPrimary() != null) ids.add(id(hg.getPrimary()));
    List<Integer> members = candidates.stream().map(NameUsage::getId).filter(ids::contains).toList();
    Integer basionymId = hg.hasBasionym() ? id(hg.getBasionym()) : null;
    return new ProposedGroup(basionymId, members, rels);
  }

  /**
   * As {@code HomotypicConsolidator.relateVariation}: a duplicate gets no relation, an orthographic
   * variant a spelling correction from the original to the variant, any other variation is
   * homotypic with the original.
   */
  private static void relateVariation(List<ProposedRelation> rels, Name original, Name variation,
      ExistingRelations existing) {
    if (id(original) == id(variation) || HomotypicRules.isDuplicate(original, variation)) {
      return;
    }
    if (HomotypicRules.isOrthographicVariant(original, variation)) {
      propose(rels, original, variation, SPELLING_CORRECTION, existing);
    } else {
      propose(rels, variation, original, HOMOTYPIC, existing);
    }
  }

  /**
   * Adds the relation, marked if exactly it is stored already. A relation not yet stored is skipped
   * when the stored relations already link both names homotypically (directly or through one other
   * name) or relate them as later homonyms, so the cleanup never flags what the grouping proposes.
   */
  private static void propose(List<ProposedRelation> rels, Name from, Name to, String type,
      ExistingRelations existing) {
    int a = id(from);
    int b = id(to);
    if (a == b) return;
    if (existing.exists(a, b, RelTypes.parse(type))) {
      rels.add(new ProposedRelation(a, b, type, true));
    } else if (!existing.linkedByData(a, b) && !existing.laterHomonyms(a, b)) {
      rels.add(new ProposedRelation(a, b, type, false));
    }
  }

  /**
   * The normalized terminal epithet, or null for names the grouping skips as the consolidator does:
   * supraspecific names, autonyms, unparsable names and names without a usable terminal epithet.
   */
  @Nullable
  private static String epithetKey(Name n) {
    if (n.getType() == NameType.OTHER || n.getType() == NameType.IDENTIFIER
        || n.getRank() != null && n.getRank().isSupraspecific() || n.isAutonym()) {
      return null;
    }
    String terminal = n.getTerminalEpithet();
    if (terminal == null || terminal.isBlank()) return null;
    String norm = SciNameNormalizer.normalizeEpithet(terminal.trim().toLowerCase(Locale.ROOT));
    return norm == null || norm.isEmpty() ? null : norm;
  }

  private static int id(Name n) {
    return Integer.parseInt(n.getId());
  }

  /**
   * As {@code HomotypicConsolidator.linked}: one is a synonym of the other, both are synonyms of the
   * same accepted name, or the stored relations relate them homotypically.
   */
  private record Linked(Map<Integer, List<Integer>> acceptedBySynonym, ExistingRelations existing) {
    boolean test(Name n1, Name n2) {
      int a = id(n1);
      int b = id(n2);
      List<Integer> accA = acceptedBySynonym.getOrDefault(a, List.of());
      List<Integer> accB = acceptedBySynonym.getOrDefault(b, List.of());
      return accA.contains(b) || accB.contains(a)
          || accA.stream().anyMatch(accB::contains)
          || existing.homotypic(a).contains(b);
    }
  }
}
