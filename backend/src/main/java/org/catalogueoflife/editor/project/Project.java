package org.catalogueoflife.editor.project;

import life.catalogue.api.vocab.License;
import org.gbif.nameparser.api.NomCode;

public class Project {
  private Integer id;
  private String title;
  private String alias;
  private String description;
  private NomCode nomCode;
  private License license;
  private String geographicScope;
  private String taxonomicScope;
  private String metadata = "{}";

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getAlias() { return alias; }
  public void setAlias(String alias) { this.alias = alias; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public NomCode getNomCode() { return nomCode; }
  public void setNomCode(NomCode nomCode) { this.nomCode = nomCode; }
  public License getLicense() { return license; }
  public void setLicense(License license) { this.license = license; }
  public String getGeographicScope() { return geographicScope; }
  public void setGeographicScope(String geographicScope) { this.geographicScope = geographicScope; }
  public String getTaxonomicScope() { return taxonomicScope; }
  public void setTaxonomicScope(String taxonomicScope) { this.taxonomicScope = taxonomicScope; }
  public String getMetadata() { return metadata; }
  public void setMetadata(String metadata) { this.metadata = metadata; }
}
