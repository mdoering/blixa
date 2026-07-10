package org.catalogueoflife.editor.name.dto;

import java.util.List;
import life.catalogue.api.vocab.Environment;
import org.catalogueoflife.editor.name.NameUsage;

// Mirrors ReferenceResponse's shape: the API-writable fields plus the parser-derived
// atomized name parts / nameType / parseState, the computed formattedName, and the
// synonym_accepted link ids (which are not columns on name_usage itself). The now-enum-typed
// model fields (status, nomStatus, gender, notho, nameType, environment) are exposed as plain
// strings here (enum .name()) to keep the existing wire shape; temporalRangeStart/End are plain
// Strings on the model already (free-text for now; vocab-backed validation is a future concern);
// publishedInYear mirrors the model's Integer.
public record NameUsageResponse(
    Integer id,
    Integer parentId,
    List<String> alternativeId,
    String status,
    String namePhrase,
    List<Integer> referenceId,
    Boolean extinct,
    List<String> environment,
    String temporalRangeStart,
    String temporalRangeEnd,
    String scientificName,
    String authorship,
    String rank,
    String uninomial,
    String genus,
    String infragenericEpithet,
    String specificEpithet,
    String infraspecificEpithet,
    String cultivarEpithet,
    String notho,
    String combinationAuthorship,
    String combinationExAuthorship,
    String combinationAuthorshipYear,
    String basionymAuthorship,
    String basionymExAuthorship,
    String basionymAuthorshipYear,
    String sanctioningAuthor,
    String nomStatus,
    Integer publishedInReferenceId,
    Integer publishedInYear,
    String publishedInPage,
    String publishedInPageLink,
    String gender,
    String etymology,
    String nameType,
    String parseState,
    String remarks,
    String formattedName,
    List<Integer> acceptedParentIds,
    List<Integer> synonymIds,
    Integer version) {

  public static NameUsageResponse of(NameUsage u, String formattedName, List<Integer> acceptedParentIds,
      List<Integer> synonymIds) {
    return new NameUsageResponse(u.getId(), u.getParentId(), u.getAlternativeId(), name(u.getStatus()),
        u.getNamePhrase(), u.getReferenceId(),
        u.getExtinct(), names(u.getEnvironment()), u.getTemporalRangeStart(), u.getTemporalRangeEnd(),
        u.getScientificName(), u.getAuthorship(), u.getRank(), u.getUninomial(),
        u.getGenus(), u.getInfragenericEpithet(), u.getSpecificEpithet(), u.getInfraspecificEpithet(),
        u.getCultivarEpithet(), name(u.getNotho()), u.getCombinationAuthorship(), u.getCombinationExAuthorship(),
        u.getCombinationAuthorshipYear(), u.getBasionymAuthorship(), u.getBasionymExAuthorship(),
        u.getBasionymAuthorshipYear(), u.getSanctioningAuthor(), name(u.getNomStatus()),
        u.getPublishedInReferenceId(), u.getPublishedInYear(), u.getPublishedInPage(),
        u.getPublishedInPageLink(), name(u.getGender()), u.getEtymology(), name(u.getNameType()), u.getParseState(),
        u.getRemarks(), formattedName, acceptedParentIds, synonymIds, u.getVersion());
  }

  private static String name(Enum<?> e) {
    return e == null ? null : e.name();
  }

  private static List<String> names(List<Environment> envs) {
    return envs == null ? null : envs.stream().map(Environment::name).toList();
  }
}
