package org.catalogueoflife.editor.user;

public class AppUser {
  private Integer id;
  private String orcid;
  private String username;
  private String email;
  private String displayName;
  private String given;
  private String family;
  private String passwordHash;

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public String getOrcid() { return orcid; }
  public void setOrcid(String orcid) { this.orcid = orcid; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }
  public String getGiven() { return given; }
  public void setGiven(String given) { this.given = given; }
  public String getFamily() { return family; }
  public void setFamily(String family) { this.family = family; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
