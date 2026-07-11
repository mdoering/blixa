package org.catalogueoflife.editor.name;

import java.time.OffsetDateTime;
import java.util.List;

// reference uses a per-project compound (project_id, id) primary key (see V3__name_core.sql):
// id alone is only meaningful together with projectId.
public class Reference {
  private Integer id;
  private Integer projectId;
  private List<String> alternativeId;
  private String citation;
  private String type;
  private String author;
  private String editor;
  private String title;
  private String containerTitle;
  private String issued;
  private String volume;
  private String issue;
  private String page;
  private String publisher;
  private String doi;
  private String isbn;
  private String issn;
  private String link;
  // Filename of a hosted PDF (see name/PdfService), NOT a URL -- ReferenceResponse.pdfUrl and
  // ReferenceColdpWriter build the public URL from this + coldp.pdf.base-url. Deliberately separate
  // from `link`, which stays whatever the user set (see ReferenceMapper.updatePdf's javadoc).
  private String pdf;
  // When the cited online resource was last accessed (free-text, e.g. an ISO date). CSL "accessed"
  // / BibTeX "urldate" (see RefMapping).
  private String accessed;
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
  public String getCitation() { return citation; }
  public void setCitation(String citation) { this.citation = citation; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getAuthor() { return author; }
  public void setAuthor(String author) { this.author = author; }
  public String getEditor() { return editor; }
  public void setEditor(String editor) { this.editor = editor; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getContainerTitle() { return containerTitle; }
  public void setContainerTitle(String containerTitle) { this.containerTitle = containerTitle; }
  public String getIssued() { return issued; }
  public void setIssued(String issued) { this.issued = issued; }
  public String getVolume() { return volume; }
  public void setVolume(String volume) { this.volume = volume; }
  public String getIssue() { return issue; }
  public void setIssue(String issue) { this.issue = issue; }
  public String getPage() { return page; }
  public void setPage(String page) { this.page = page; }
  public String getPublisher() { return publisher; }
  public void setPublisher(String publisher) { this.publisher = publisher; }
  public String getDoi() { return doi; }
  public void setDoi(String doi) { this.doi = doi; }
  public String getIsbn() { return isbn; }
  public void setIsbn(String isbn) { this.isbn = isbn; }
  public String getIssn() { return issn; }
  public void setIssn(String issn) { this.issn = issn; }
  public String getLink() { return link; }
  public void setLink(String link) { this.link = link; }
  public String getPdf() { return pdf; }
  public void setPdf(String pdf) { this.pdf = pdf; }
  public String getAccessed() { return accessed; }
  public void setAccessed(String accessed) { this.accessed = accessed; }
  public String getRemarks() { return remarks; }
  public void setRemarks(String remarks) { this.remarks = remarks; }
  public OffsetDateTime getModified() { return modified; }
  public void setModified(OffsetDateTime modified) { this.modified = modified; }
  public Integer getModifiedBy() { return modifiedBy; }
  public void setModifiedBy(Integer modifiedBy) { this.modifiedBy = modifiedBy; }
  public Integer getVersion() { return version; }
  public void setVersion(Integer version) { this.version = version; }
}
