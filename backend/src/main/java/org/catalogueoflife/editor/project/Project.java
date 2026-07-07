package org.catalogueoflife.editor.project;

import java.time.LocalDate;

public class Project {
  private Long id;
  private String slug;
  private String title;
  private String alias;
  private String description;
  private String nomCode;
  private String license;
  private String version;
  private LocalDate issued;
  private String geographicScope;
  private String taxonomicScope;
  private String doi;
  private String metadata = "{}";

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getSlug() { return slug; }
  public void setSlug(String slug) { this.slug = slug; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getAlias() { return alias; }
  public void setAlias(String alias) { this.alias = alias; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getNomCode() { return nomCode; }
  public void setNomCode(String nomCode) { this.nomCode = nomCode; }
  public String getLicense() { return license; }
  public void setLicense(String license) { this.license = license; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public LocalDate getIssued() { return issued; }
  public void setIssued(LocalDate issued) { this.issued = issued; }
  public String getGeographicScope() { return geographicScope; }
  public void setGeographicScope(String geographicScope) { this.geographicScope = geographicScope; }
  public String getTaxonomicScope() { return taxonomicScope; }
  public void setTaxonomicScope(String taxonomicScope) { this.taxonomicScope = taxonomicScope; }
  public String getDoi() { return doi; }
  public void setDoi(String doi) { this.doi = doi; }
  public String getMetadata() { return metadata; }
  public void setMetadata(String metadata) { this.metadata = metadata; }
}
