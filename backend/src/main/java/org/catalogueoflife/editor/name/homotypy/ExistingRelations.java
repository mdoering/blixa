package org.catalogueoflife.editor.name.homotypy;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import life.catalogue.api.vocab.NomRelType;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.nomenclature.RelTypes;

/**
 * The stored name relations the homotypic grouping takes as evidence, indexed by usage id: which
 * usages are related homotypically (any {@link NomRelType#isHomotypic()} type, either direction),
 * which are related as later homonyms (either direction), and which usages are the misspelling a
 * spelling correction points to. Built by the callers from {@code name_relation}; a relation
 * without a related usage, relating a usage to itself, or of an unknown type is skipped.
 */
public final class ExistingRelations {

  public static final ExistingRelations EMPTY = of(List.of());

  private record Key(int from, int to, NomRelType type) {}

  private final Set<Key> relations = new HashSet<>();
  private final Map<Integer, Set<Integer>> homotypic = new HashMap<>();
  private final Map<Integer, Set<Integer>> homonyms = new HashMap<>();
  private final Set<Integer> misspelled = new HashSet<>();

  private ExistingRelations() {}

  public static ExistingRelations of(Collection<NameRelationResponse> relations) {
    ExistingRelations ex = new ExistingRelations();
    for (NameRelationResponse r : relations) {
      if (r.usageId() == null || r.relatedUsageId() == null || r.usageId().equals(r.relatedUsageId())) {
        continue;
      }
      NomRelType type = RelTypes.parse(r.type());
      if (type == null) {
        continue;
      }
      int from = r.usageId();
      int to = r.relatedUsageId();
      ex.relations.add(new Key(from, to, type));
      if (type.isHomotypic()) {
        link(ex.homotypic, from, to);
        // the spelling correction points from the correct spelling to the variant
        if (type == NomRelType.SPELLING_CORRECTION) {
          ex.misspelled.add(to);
        }
      } else if (type == NomRelType.LATER_HOMONYM) {
        link(ex.homonyms, from, to);
      }
    }
    return ex;
  }

  private static void link(Map<Integer, Set<Integer>> index, int a, int b) {
    index.computeIfAbsent(a, k -> new HashSet<>()).add(b);
    index.computeIfAbsent(b, k -> new HashSet<>()).add(a);
  }

  /** True if exactly this relation, in this direction, is stored. */
  public boolean exists(int from, int to, NomRelType type) {
    return relations.contains(new Key(from, to, type));
  }

  /** The usages related homotypically to the given one, either direction. */
  public Set<Integer> homotypic(int usageId) {
    return Collections.unmodifiableSet(homotypic.getOrDefault(usageId, Set.of()));
  }

  /** True if the relations link both homotypically, directly or through a single other usage. */
  public boolean linkedByData(int a, int b) {
    Set<Integer> relA = homotypic(a);
    return relA.contains(b) || !Collections.disjoint(relA, homotypic(b));
  }

  /** True if one is stored as a later homonym of the other, either direction. */
  public boolean laterHomonyms(int a, int b) {
    return homonyms.getOrDefault(a, Set.of()).contains(b);
  }

  /** True if the usage is the target of a spelling correction, i.e. the variant spelling. */
  public boolean misspelling(int usageId) {
    return misspelled.contains(usageId);
  }
}
