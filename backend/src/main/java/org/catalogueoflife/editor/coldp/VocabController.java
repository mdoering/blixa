package org.catalogueoflife.editor.coldp;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import life.catalogue.api.model.CSLType;
import life.catalogue.api.vocab.Environment;
import life.catalogue.api.vocab.Gender;
import life.catalogue.api.vocab.NomStatus;
import org.gbif.nameparser.api.Rank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Exposes the enum vocabularies the taxon editing form needs, so those fields can be constrained,
// searchable dropdowns instead of free text (mirrors IdScopeController). Values are returned in the
// exact form the API stores/returns them, so a stored value always matches a dropdown option and
// round-trips on save: ranks are lower-cased (NameUsage.rank is stored lower-cased -- see
// ParsedNameMapping), the rest are the enum name() (NameUsageResponse serializes them via
// Enum.name(), and VocabParsing accepts that form back). Authenticated read only (SecurityConfig's
// anyRequest().authenticated() covers /api/coldp/**); no service layer -- these are fixed in-JVM enums.
@RestController
public class VocabController {

  // A nomenclatural-status option carries both code-specific labels so the taxon form can render the
  // one that fits the project's nomenclatural code -- the botanical label for botanical (and
  // bacterial, which follows botany) projects, the zoological label for zoological ones. `value` is
  // the enum name (the wire form NameUsageResponse/VocabParsing use).
  public record NomStatusOption(String value, String botanical, String zoological) {}

  public record VocabResponse(List<String> ranks, List<NomStatusOption> nomStatus,
      List<String> gender, List<String> environment, List<String> cslTypes) {}

  @GetMapping("/api/coldp/vocab")
  public VocabResponse vocab() {
    return new VocabResponse(
        Arrays.stream(Rank.values()).map(r -> r.name().toLowerCase(Locale.ROOT)).toList(),
        Arrays.stream(NomStatus.values())
            .map(s -> new NomStatusOption(s.name(), s.getBotanicalLabel(), s.getZoologicalLabel()))
            .toList(),
        Arrays.stream(Gender.values()).map(Enum::name).toList(),
        Arrays.stream(Environment.values()).map(Enum::name).toList(),
        // CSLType.toString() is the CSL-JSON wire id (e.g. "article-journal") -- the same canonical
        // form ReferenceService.validateType persists, so a value picked from this list always
        // round-trips (mirrors ranks' lower-case rationale above).
        Arrays.stream(CSLType.values()).map(CSLType::toString).toList());
  }
}
