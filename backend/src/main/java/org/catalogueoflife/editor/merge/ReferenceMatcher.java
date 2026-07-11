package org.catalogueoflife.editor.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.catalogueoflife.editor.merge.dto.Candidate;
import org.catalogueoflife.editor.merge.dto.Category;
import org.catalogueoflife.editor.name.Reference;
import org.catalogueoflife.editor.name.ReferenceMapper;
import org.catalogueoflife.editor.name.dto.ScoredId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Matches every SOURCE-project reference against the TARGET project and assigns each one a
// Category (see dto/Category.java), producing the `references` half of a merge run's MergePlan
// (MergeService.computePlan, a later task, calls ReferenceMatcher.match once per merge).
// Three-stage matching, cheapest/most-precise first:
//
//   1. DOI match: normDoi(r) looked up in a byDoi map of every target reference's normDoi (built
//      once per match() call, first-wins on collision) -- a DOI is a global identifier, so an
//      equal (normalized) DOI is treated as full confidence, no similarity computed.
//   2. Citation match: only tried when the source has no DOI (or its DOI didn't hit) --
//      normCitation(r) looked up in a byCitation map of every target reference's normCitation
//      (first-wins). An exact normalized-citation string is also full confidence.
//   3. Trigram fuzzy fallback: only when neither the DOI nor the citation exact-matched --
//      ReferenceMapper.findFuzzyCitation's pg_trgm `%`/similarity() query against
//      target.citation, gated by coldp.merge.citation-similarity (default 0.9). Unlike
//      NameMatcher's POSSIBLE_FUZZY, a fuzzy citation hit here is reported as plain POSSIBLE (see
//      Category's javadoc) -- there is no author-compatibility axis for references the way there
//      is for names, so "fuzzy" is the only kind of ambiguity a reference candidate can have.
//
// Deliberately conservative on the fuzzy threshold (0.9, well above NameMatcher's 0.7): citations
// are long, free-text strings where two DIFFERENT works (e.g. same authors/year, different pages)
// can still share a lot of trigram overlap, so a high bar keeps POSSIBLE meaningful rather than
// noisy.
@Service
public class ReferenceMatcher {

  private final ReferenceMapper references;
  private final double threshold;

  public ReferenceMatcher(ReferenceMapper references,
      @Value("${coldp.merge.citation-similarity:0.9}") double threshold) {
    this.references = references;
    this.threshold = threshold;
  }

  /**
   * Matches every reference of {@code sourceProjectId} against {@code targetProjectId}, one
   * {@link Candidate} per source reference (so the returned list's size always equals the source
   * project's reference count). Category assignment, per source reference:
   *
   * <ul>
   *   <li>{@link Category#MATCHED} -- the source's {@link #normDoi} is non-null and equals a
   *       target reference's normDoi, OR (no DOI hit) the source's {@link #normCitation} is
   *       non-null and equals a target reference's normCitation. {@code score} is {@code 1.0}.
   *   <li>{@link Category#POSSIBLE} -- no DOI/citation exact match, but {@link
   *       ReferenceMapper#findFuzzyCitation} finds a target whose citation clears the configured
   *       trigram-similarity threshold. {@code targetId}/{@code score} come from that candidate.
   *   <li>{@link Category#NEW} -- no DOI, citation, or fuzzy candidate at all. {@code targetId}
   *       and {@code score} are both {@code null}.
   * </ul>
   */
  public List<Candidate> match(int sourceProjectId, int targetProjectId) {
    List<Reference> targets = references.findAllByProject(targetProjectId);
    Map<String, Reference> byDoi = new HashMap<>();
    Map<String, Reference> byCitation = new HashMap<>();
    for (Reference t : targets) {
      String doi = normDoi(t.getDoi());
      if (doi != null) {
        byDoi.putIfAbsent(doi, t);
      }
      String citation = normCitation(t.getCitation());
      if (citation != null) {
        byCitation.putIfAbsent(citation, t);
      }
    }

    List<Candidate> result = new ArrayList<>();
    for (Reference src : references.findAllByProject(sourceProjectId)) {
      result.add(matchOne(src, byDoi, byCitation, targetProjectId));
    }
    return result;
  }

  private Candidate matchOne(Reference src, Map<String, Reference> byDoi,
      Map<String, Reference> byCitation, int targetProjectId) {
    String sourceId = String.valueOf(src.getId());

    String doi = normDoi(src.getDoi());
    if (doi != null) {
      Reference hit = byDoi.get(doi);
      if (hit != null) {
        return new Candidate(sourceId, Category.MATCHED, String.valueOf(hit.getId()), 1.0);
      }
    }

    String citation = normCitation(src.getCitation());
    if (citation != null) {
      Reference hit = byCitation.get(citation);
      if (hit != null) {
        return new Candidate(sourceId, Category.MATCHED, String.valueOf(hit.getId()), 1.0);
      }
    }

    // Guard: a null/blank source citation must never reach `%` in findFuzzyCitation.
    if (citation != null) {
      ScoredId fuzzy = references.findFuzzyCitation(targetProjectId, src.getCitation(), threshold);
      if (fuzzy != null) {
        return new Candidate(sourceId, Category.POSSIBLE, String.valueOf(fuzzy.id()), fuzzy.score());
      }
    }

    return new Candidate(sourceId, Category.NEW, null, null);
  }

  static String normDoi(String doi) {
    if (doi == null || doi.isBlank()) return null;
    return doi.trim().toLowerCase(Locale.ROOT)
        .replaceFirst("^https?://(dx\\.)?doi\\.org/", "").replaceFirst("^doi:", "").trim();
  }

  static String normCitation(String c) {
    if (c == null || c.isBlank()) return null;
    return c.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("[.,;]+$", "");
  }
}
