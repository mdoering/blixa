package org.catalogueoflife.editor.project;

import java.util.List;
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
  private boolean gbifOccurrenceLayer = true;
  // Which alternative_id CURIE scopes (e.g. "col", "gbif") this project's identifier fields use,
  // each with an optional CLB dataset key (matchable iff datasetKey is set) -- loaded/written via
  // ProjectMapper's @Results/UPDATE (both wire the JSONB typeHandler); see
  // ProjectResponse/UpdateProjectMetadataRequest for the wire-facing read/write shape.
  private List<IdentifierScope> identifierScopes;
  private boolean isPublic;

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
  public boolean getGbifOccurrenceLayer() { return gbifOccurrenceLayer; }
  public void setGbifOccurrenceLayer(boolean gbifOccurrenceLayer) { this.gbifOccurrenceLayer = gbifOccurrenceLayer; }
  public List<IdentifierScope> getIdentifierScopes() { return identifierScopes; }
  public void setIdentifierScopes(List<IdentifierScope> identifierScopes) { this.identifierScopes = identifierScopes; }
  public boolean isPublic() { return isPublic; }
  public void setPublic(boolean isPublic) { this.isPublic = isPublic; }
}
