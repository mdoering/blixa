package org.catalogueoflife.editor.name.dto;

import java.util.List;
import org.catalogueoflife.editor.name.NameUsage;

// Mirrors ReferenceResponse's shape: the API-writable fields plus the parser-derived
// atomized name parts / nameType / parseState, the computed formattedName, and the
// synonym_accepted link ids (which are not columns on name_usage itself).
public record NameUsageResponse(
    Integer id,
    Integer parentId,
    String status,
    String namePhrase,
    Boolean extinct,
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
    String publishedInYear,
    String publishedInPage,
    String publishedInPageLink,
    String gender,
    String etymology,
    String nameType,
    String parseState,
    String link,
    String remarks,
    String formattedName,
    List<Integer> acceptedParentIds,
    List<Integer> synonymIds,
    Integer version) {

  public static NameUsageResponse of(NameUsage u, String formattedName, List<Integer> acceptedParentIds,
      List<Integer> synonymIds) {
    return new NameUsageResponse(u.getId(), u.getParentId(), u.getStatus(), u.getNamePhrase(),
        u.getExtinct(), u.getScientificName(), u.getAuthorship(), u.getRank(), u.getUninomial(),
        u.getGenus(), u.getInfragenericEpithet(), u.getSpecificEpithet(), u.getInfraspecificEpithet(),
        u.getCultivarEpithet(), u.getNotho(), u.getCombinationAuthorship(), u.getCombinationExAuthorship(),
        u.getCombinationAuthorshipYear(), u.getBasionymAuthorship(), u.getBasionymExAuthorship(),
        u.getBasionymAuthorshipYear(), u.getSanctioningAuthor(), u.getNomStatus(),
        u.getPublishedInReferenceId(), u.getPublishedInYear(), u.getPublishedInPage(),
        u.getPublishedInPageLink(), u.getGender(), u.getEtymology(), u.getNameType(), u.getParseState(),
        u.getLink(), u.getRemarks(), formattedName, acceptedParentIds, synonymIds, u.getVersion());
  }
}
