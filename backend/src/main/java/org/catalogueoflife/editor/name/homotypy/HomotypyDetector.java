package org.catalogueoflife.editor.name.homotypy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import life.catalogue.common.tax.AuthorshipNormalizer;
import life.catalogue.common.tax.SciNameNormalizer;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedGroup;
import org.catalogueoflife.editor.name.homotypy.dto.ProposedRelation;
import org.springframework.stereotype.Component;

// Compact BasionymSorter-lite. Buckets the accepted usage + its (non-misapplied) synonyms by
// normalized terminal epithet, then clusters within a bucket by basionym-or-combination author team
// (+ compatible year). A name WITHOUT parenthetical basionym authorship is the bucket's basionym;
// names WITH it are recombinations. Pure/stateless: no DB, no name parser (reads the parsed fields
// already stored on each usage). See docs/superpowers/specs/2026-07-20-homotypic-grouping-design.md.
@Component
public class HomotypyDetector {

  private static final class Cluster {
    final String epithetKey;
    final String authorKey;
    String year; // first non-null year seen (back-fills the group's reference year)
    final List<NameUsage> members = new ArrayList<>();
    Cluster(String epithetKey, String authorKey, String year) {
      this.epithetKey = epithetKey; this.authorKey = authorKey; this.year = year;
    }
  }

  public HomotypyProposal detect(NameUsage accepted, List<NameUsage> synonyms, Set<String> existingKeys) {
    List<NameUsage> candidates = new ArrayList<>();
    candidates.add(accepted); // accepted first so its group is anchored on it
    for (NameUsage s : synonyms) {
      if (s.getStatus() != Status.MISAPPLIED) candidates.add(s);
    }

    List<Cluster> clusters = new ArrayList<>();
    for (NameUsage u : candidates) {
      String ek = epithetKey(u);
      String ak = authorKey(u);
      String yr = yearOf(u);
      Cluster match = null;
      if (!ak.isBlank()) {
        for (Cluster c : clusters) {
          if (c.epithetKey.equals(ek) && c.authorKey.equals(ak) && yearCompatible(c.year, yr)) {
            match = c; break;
          }
        }
      }
      if (match == null) {
        clusters.add(new Cluster(ek, ak, yr));
        clusters.get(clusters.size() - 1).members.add(u);
      } else {
        match.members.add(u);
        // The group year is established from the first member that has one, so later year-less
        // members still match against it. When the anchor lacks a year and later members carry
        // different years, which member ends up "the" group year (and thus the split between
        // clusters) is order-dependent. Accepted as a v1 limitation: Side 1 is curator-confirmed,
        // and a genuine different-year-same-author case is a data contradiction the curator
        // resolves, not something the detector needs to disambiguate automatically.
        if (match.year == null) match.year = yr;
      }
    }

    List<ProposedGroup> groups = new ArrayList<>();
    for (Cluster c : clusters) {
      NameUsage basionym = null;
      for (NameUsage m : c.members) {
        if (!hasBasionymAuthorship(m)) { basionym = m; break; }
      }
      List<Integer> memberIds = c.members.stream().map(NameUsage::getId).toList();
      List<ProposedRelation> relations = new ArrayList<>();
      if (basionym != null) {
        for (NameUsage m : c.members) {
          if (m == basionym) continue;
          String type = hasBasionymAuthorship(m) ? "basionym" : "homotypic";
          relations.add(rel(m.getId(), basionym.getId(), type, existingKeys));
        }
      } else if (c.members.size() > 1) {
        NameUsage head = c.members.get(0);
        for (int i = 1; i < c.members.size(); i++) {
          relations.add(rel(c.members.get(i).getId(), head.getId(), "homotypic", existingKeys));
        }
      }
      groups.add(new ProposedGroup(basionym == null ? null : basionym.getId(), memberIds, relations));
    }
    return new HomotypyProposal(groups);
  }

  private static ProposedRelation rel(int usageId, int relatedId, String type, Set<String> existing) {
    boolean exists = existing.contains(usageId + ":" + relatedId + ":" + HomotypicRelations.normalize(type));
    return new ProposedRelation(usageId, relatedId, type, exists);
  }

  private static String epithetKey(NameUsage u) {
    String terminal = notBlank(u.getInfraspecificEpithet()) ? u.getInfraspecificEpithet()
        : notBlank(u.getSpecificEpithet()) ? u.getSpecificEpithet()
        : u.getUninomial();
    if (isBlank(terminal)) return "";
    String norm = SciNameNormalizer.normalizeEpithet(terminal);
    return norm == null ? "" : norm.toLowerCase(Locale.ROOT);
  }

  // Basionym-or-combination author: the basionym (in-parentheses) author if the name has one, else
  // the combination author. Normalized + alias-resolved so spelling variants of one author match.
  private static String authorKey(NameUsage u) {
    String team = hasBasionymAuthorship(u) ? u.getBasionymAuthorship() : u.getCombinationAuthorship();
    if (isBlank(team)) return "";
    String normalized = AuthorshipNormalizer.normalize(team);
    if (normalized == null || normalized.isBlank()) return "";
    String looked = AuthorshipNormalizer.INSTANCE.lookup(normalized);
    return (looked == null || looked.isBlank() ? normalized : looked).toLowerCase(Locale.ROOT);
  }

  private static String yearOf(NameUsage u) {
    return hasBasionymAuthorship(u) ? u.getBasionymAuthorshipYear() : u.getCombinationAuthorshipYear();
  }

  private static boolean yearCompatible(String a, String b) {
    return a == null || a.isBlank() || b == null || b.isBlank() || a.equals(b);
  }

  // Whether this usage carries parenthetical basionym authorship, i.e. is itself a recombination
  // (as opposed to being the original combination / the cluster's basionym).
  private static boolean hasBasionymAuthorship(NameUsage u) { return notBlank(u.getBasionymAuthorship()); }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }
  private static boolean notBlank(String s) { return !isBlank(s); }
}
