package org.catalogueoflife.editor.name;

import java.time.OffsetDateTime;
import java.util.List;
import life.catalogue.api.vocab.Environment;
import life.catalogue.api.vocab.Gender;
import life.catalogue.api.vocab.NomStatus;
import org.gbif.nameparser.api.NamePart;
import org.gbif.nameparser.api.NameType;

// The collapsed COL editor model: name facts (nomenclatural) and taxonomic usage
// (accepted/synonym placement) live in a single row. `parentId` is the accepted
// classification tree link (self-referencing); synonyms link to their accepted
// usage via the separate synonym_accepted table, NOT via parentId.
//
// id/parentId/basionymId/publishedInReferenceId/referenceId are all scoped to `projectId`:
// name_usage uses a per-project compound (project_id, id) primary key (see V3__name_core.sql),
// so an id alone is only meaningful together with the project it belongs to.
public class NameUsage {
  private Integer id;
  private Integer projectId;
  private List<String> alternativeId;
  private Integer parentId;
  private Integer basionymId;
  private Integer ordinal;
  // taxonomic
  private Status status;
  private String namePhrase;
  private List<Integer> referenceId;
  private Boolean extinct;
  private List<Environment> environment;
  private String temporalRangeStart;
  private String temporalRangeEnd;
  // nomenclatural (name)
  private String scientificName;
  private String authorship;
  private String rank;
  private String uninomial;
  private String genus;
  private String infragenericEpithet;
  private String specificEpithet;
  private String infraspecificEpithet;
  private String cultivarEpithet;
  private NamePart notho;
  private String combinationAuthorship;
  private String combinationExAuthorship;
  private String combinationAuthorshipYear;
  private String basionymAuthorship;
  private String basionymExAuthorship;
  private String basionymAuthorshipYear;
  private String sanctioningAuthor;
  private NomStatus nomStatus;
  private Integer publishedInReferenceId;
  private Integer publishedInYear;
  private String publishedInPage;
  private String publishedInPageLink;
  private Gender gender;
  private String etymology;
  private NameType nameType;
  private String parseState;
  private String link;
  private String remarks;
  private OffsetDateTime modified;
  private Integer modifiedBy;
  private Integer version;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public Integer getProjectId() { return projectId; }
  public void setProjectId(Integer projectId) { this.projectId = projectId; }
  public List<String> getAlternativeId() { return alternativeId; }
  public void setAlternativeId(List<String> alternativeId) { this.alternativeId = alternativeId; }
  public Integer getParentId() { return parentId; }
  public void setParentId(Integer parentId) { this.parentId = parentId; }
  public Integer getBasionymId() { return basionymId; }
  public void setBasionymId(Integer basionymId) { this.basionymId = basionymId; }
  public Integer getOrdinal() { return ordinal; }
  public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
  public Status getStatus() { return status; }
  public void setStatus(Status status) { this.status = status; }
  public String getNamePhrase() { return namePhrase; }
  public void setNamePhrase(String namePhrase) { this.namePhrase = namePhrase; }
  public List<Integer> getReferenceId() { return referenceId; }
  public void setReferenceId(List<Integer> referenceId) { this.referenceId = referenceId; }
  public Boolean getExtinct() { return extinct; }
  public void setExtinct(Boolean extinct) { this.extinct = extinct; }
  public List<Environment> getEnvironment() { return environment; }
  public void setEnvironment(List<Environment> environment) { this.environment = environment; }
  public String getTemporalRangeStart() { return temporalRangeStart; }
  public void setTemporalRangeStart(String temporalRangeStart) { this.temporalRangeStart = temporalRangeStart; }
  public String getTemporalRangeEnd() { return temporalRangeEnd; }
  public void setTemporalRangeEnd(String temporalRangeEnd) { this.temporalRangeEnd = temporalRangeEnd; }
  public String getScientificName() { return scientificName; }
  public void setScientificName(String scientificName) { this.scientificName = scientificName; }
  public String getAuthorship() { return authorship; }
  public void setAuthorship(String authorship) { this.authorship = authorship; }
  public String getRank() { return rank; }
  public void setRank(String rank) { this.rank = rank; }
  public String getUninomial() { return uninomial; }
  public void setUninomial(String uninomial) { this.uninomial = uninomial; }
  public String getGenus() { return genus; }
  public void setGenus(String genus) { this.genus = genus; }
  public String getInfragenericEpithet() { return infragenericEpithet; }
  public void setInfragenericEpithet(String infragenericEpithet) { this.infragenericEpithet = infragenericEpithet; }
  public String getSpecificEpithet() { return specificEpithet; }
  public void setSpecificEpithet(String specificEpithet) { this.specificEpithet = specificEpithet; }
  public String getInfraspecificEpithet() { return infraspecificEpithet; }
  public void setInfraspecificEpithet(String infraspecificEpithet) { this.infraspecificEpithet = infraspecificEpithet; }
  public String getCultivarEpithet() { return cultivarEpithet; }
  public void setCultivarEpithet(String cultivarEpithet) { this.cultivarEpithet = cultivarEpithet; }
  public NamePart getNotho() { return notho; }
  public void setNotho(NamePart notho) { this.notho = notho; }
  public String getCombinationAuthorship() { return combinationAuthorship; }
  public void setCombinationAuthorship(String combinationAuthorship) { this.combinationAuthorship = combinationAuthorship; }
  public String getCombinationExAuthorship() { return combinationExAuthorship; }
  public void setCombinationExAuthorship(String combinationExAuthorship) { this.combinationExAuthorship = combinationExAuthorship; }
  public String getCombinationAuthorshipYear() { return combinationAuthorshipYear; }
  public void setCombinationAuthorshipYear(String combinationAuthorshipYear) { this.combinationAuthorshipYear = combinationAuthorshipYear; }
  public String getBasionymAuthorship() { return basionymAuthorship; }
  public void setBasionymAuthorship(String basionymAuthorship) { this.basionymAuthorship = basionymAuthorship; }
  public String getBasionymExAuthorship() { return basionymExAuthorship; }
  public void setBasionymExAuthorship(String basionymExAuthorship) { this.basionymExAuthorship = basionymExAuthorship; }
  public String getBasionymAuthorshipYear() { return basionymAuthorshipYear; }
  public void setBasionymAuthorshipYear(String basionymAuthorshipYear) { this.basionymAuthorshipYear = basionymAuthorshipYear; }
  public String getSanctioningAuthor() { return sanctioningAuthor; }
  public void setSanctioningAuthor(String sanctioningAuthor) { this.sanctioningAuthor = sanctioningAuthor; }
  public NomStatus getNomStatus() { return nomStatus; }
  public void setNomStatus(NomStatus nomStatus) { this.nomStatus = nomStatus; }
  public Integer getPublishedInReferenceId() { return publishedInReferenceId; }
  public void setPublishedInReferenceId(Integer publishedInReferenceId) { this.publishedInReferenceId = publishedInReferenceId; }
  public Integer getPublishedInYear() { return publishedInYear; }
  public void setPublishedInYear(Integer publishedInYear) { this.publishedInYear = publishedInYear; }
  public String getPublishedInPage() { return publishedInPage; }
  public void setPublishedInPage(String publishedInPage) { this.publishedInPage = publishedInPage; }
  public String getPublishedInPageLink() { return publishedInPageLink; }
  public void setPublishedInPageLink(String publishedInPageLink) { this.publishedInPageLink = publishedInPageLink; }
  public Gender getGender() { return gender; }
  public void setGender(Gender gender) { this.gender = gender; }
  public String getEtymology() { return etymology; }
  public void setEtymology(String etymology) { this.etymology = etymology; }
  public NameType getNameType() { return nameType; }
  public void setNameType(NameType nameType) { this.nameType = nameType; }
  public String getParseState() { return parseState; }
  public void setParseState(String parseState) { this.parseState = parseState; }
  public String getLink() { return link; }
  public void setLink(String link) { this.link = link; }
  public String getRemarks() { return remarks; }
  public void setRemarks(String remarks) { this.remarks = remarks; }
  public OffsetDateTime getModified() { return modified; }
  public void setModified(OffsetDateTime modified) { this.modified = modified; }
  public Integer getModifiedBy() { return modifiedBy; }
  public void setModifiedBy(Integer modifiedBy) { this.modifiedBy = modifiedBy; }
  public Integer getVersion() { return version; }
  public void setVersion(Integer version) { this.version = version; }
}
