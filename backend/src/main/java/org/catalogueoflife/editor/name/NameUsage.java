package org.catalogueoflife.editor.name;

import java.time.OffsetDateTime;
import java.util.List;

// The collapsed COL editor model: name facts (nomenclatural) and taxonomic usage
// (accepted/synonym placement) live in a single row. `parentId` is the accepted
// classification tree link (self-referencing); synonyms link to their accepted
// usage via the separate synonym_accepted table, NOT via parentId.
public class NameUsage {
  private Long id;
  private Long projectId;
  private String coldpId;
  private List<String> alternativeId;
  private Long parentId;
  private Long basionymId;
  private Integer ordinal;
  // taxonomic
  private String status;
  private String namePhrase;
  private List<Long> referenceId;
  private Boolean extinct;
  private List<String> environment;
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
  private String notho;
  private String combinationAuthorship;
  private String combinationExAuthorship;
  private String combinationAuthorshipYear;
  private String basionymAuthorship;
  private String basionymExAuthorship;
  private String basionymAuthorshipYear;
  private String sanctioningAuthor;
  private String nomStatus;
  private Long publishedInReferenceId;
  private String publishedInYear;
  private String publishedInPage;
  private String publishedInPageLink;
  private String gender;
  private String etymology;
  private String nameType;
  private String parseState;
  private String link;
  private String remarks;
  private OffsetDateTime modified;
  private Long modifiedBy;
  private Integer version;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getProjectId() { return projectId; }
  public void setProjectId(Long projectId) { this.projectId = projectId; }
  public String getColdpId() { return coldpId; }
  public void setColdpId(String coldpId) { this.coldpId = coldpId; }
  public List<String> getAlternativeId() { return alternativeId; }
  public void setAlternativeId(List<String> alternativeId) { this.alternativeId = alternativeId; }
  public Long getParentId() { return parentId; }
  public void setParentId(Long parentId) { this.parentId = parentId; }
  public Long getBasionymId() { return basionymId; }
  public void setBasionymId(Long basionymId) { this.basionymId = basionymId; }
  public Integer getOrdinal() { return ordinal; }
  public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getNamePhrase() { return namePhrase; }
  public void setNamePhrase(String namePhrase) { this.namePhrase = namePhrase; }
  public List<Long> getReferenceId() { return referenceId; }
  public void setReferenceId(List<Long> referenceId) { this.referenceId = referenceId; }
  public Boolean getExtinct() { return extinct; }
  public void setExtinct(Boolean extinct) { this.extinct = extinct; }
  public List<String> getEnvironment() { return environment; }
  public void setEnvironment(List<String> environment) { this.environment = environment; }
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
  public String getNotho() { return notho; }
  public void setNotho(String notho) { this.notho = notho; }
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
  public String getNomStatus() { return nomStatus; }
  public void setNomStatus(String nomStatus) { this.nomStatus = nomStatus; }
  public Long getPublishedInReferenceId() { return publishedInReferenceId; }
  public void setPublishedInReferenceId(Long publishedInReferenceId) { this.publishedInReferenceId = publishedInReferenceId; }
  public String getPublishedInYear() { return publishedInYear; }
  public void setPublishedInYear(String publishedInYear) { this.publishedInYear = publishedInYear; }
  public String getPublishedInPage() { return publishedInPage; }
  public void setPublishedInPage(String publishedInPage) { this.publishedInPage = publishedInPage; }
  public String getPublishedInPageLink() { return publishedInPageLink; }
  public void setPublishedInPageLink(String publishedInPageLink) { this.publishedInPageLink = publishedInPageLink; }
  public String getGender() { return gender; }
  public void setGender(String gender) { this.gender = gender; }
  public String getEtymology() { return etymology; }
  public void setEtymology(String etymology) { this.etymology = etymology; }
  public String getNameType() { return nameType; }
  public void setNameType(String nameType) { this.nameType = nameType; }
  public String getParseState() { return parseState; }
  public void setParseState(String parseState) { this.parseState = parseState; }
  public String getLink() { return link; }
  public void setLink(String link) { this.link = link; }
  public String getRemarks() { return remarks; }
  public void setRemarks(String remarks) { this.remarks = remarks; }
  public OffsetDateTime getModified() { return modified; }
  public void setModified(OffsetDateTime modified) { this.modified = modified; }
  public Long getModifiedBy() { return modifiedBy; }
  public void setModifiedBy(Long modifiedBy) { this.modifiedBy = modifiedBy; }
  public Integer getVersion() { return version; }
  public void setVersion(Integer version) { this.version = version; }
}
