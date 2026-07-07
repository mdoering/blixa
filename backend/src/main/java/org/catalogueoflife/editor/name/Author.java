package org.catalogueoflife.editor.name;

import java.time.OffsetDateTime;
import java.util.List;

public class Author {
  private Long id;
  private Long projectId;
  private String coldpId;
  private List<String> alternativeId;
  private String given;
  private String family;
  private String suffix;
  private String abbreviationBotany;
  private String affiliation;
  private String birth;
  private String death;
  private String birthPlace;
  private String country;
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
  public String getGiven() { return given; }
  public void setGiven(String given) { this.given = given; }
  public String getFamily() { return family; }
  public void setFamily(String family) { this.family = family; }
  public String getSuffix() { return suffix; }
  public void setSuffix(String suffix) { this.suffix = suffix; }
  public String getAbbreviationBotany() { return abbreviationBotany; }
  public void setAbbreviationBotany(String abbreviationBotany) { this.abbreviationBotany = abbreviationBotany; }
  public String getAffiliation() { return affiliation; }
  public void setAffiliation(String affiliation) { this.affiliation = affiliation; }
  public String getBirth() { return birth; }
  public void setBirth(String birth) { this.birth = birth; }
  public String getDeath() { return death; }
  public void setDeath(String death) { this.death = death; }
  public String getBirthPlace() { return birthPlace; }
  public void setBirthPlace(String birthPlace) { this.birthPlace = birthPlace; }
  public String getCountry() { return country; }
  public void setCountry(String country) { this.country = country; }
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
