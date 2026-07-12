package org.catalogueoflife.editor.publicapi;

import java.util.List;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.publicapi.dto.PublicContributor;
import org.catalogueoflife.editor.publicapi.dto.PublicProjectInfo;
import org.catalogueoflife.editor.publicapi.dto.PublicProjectInfo.PublicRelease;
import org.catalogueoflife.editor.publicapi.dto.PublicProjectSummary;
import org.catalogueoflife.editor.release.Release;
import org.catalogueoflife.editor.release.ReleaseMapper;
import org.catalogueoflife.editor.release.ReleaseMetricsService;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class PublicController {

  private final PublicProjectMapper projects;
  private final ProjectMemberMapper members;
  private final AppUserMapper users;
  private final ReleaseMapper releases;
  private final ReleaseMetricsService metricsService;
  private final ObjectMapper json;

  public PublicController(PublicProjectMapper projects, ProjectMemberMapper members, AppUserMapper users,
      ReleaseMapper releases, ReleaseMetricsService metricsService, ObjectMapper json) {
    this.projects = projects;
    this.members = members;
    this.users = users;
    this.releases = releases;
    this.metricsService = metricsService;
    this.json = json;
  }

  @GetMapping("/api/public/projects")
  public List<PublicProjectSummary> list() {
    return projects.findPublic().stream().map(p -> {
      Release latest = releases.findLatestReady(p.getId());
      return new PublicProjectSummary(p.getId(), p.getTitle(), p.getAlias(), p.getDescription(),
          latest == null ? null : latest.getVersion(),
          latest == null ? null : latest.getCreatedAt(),
          latest == null ? null : latest.getNameUsageCount());
    }).toList();
  }

  @GetMapping("/api/public/projects/{idOrAlias}")
  public PublicProjectInfo info(@PathVariable String idOrAlias) {
    Project p = resolve(idOrAlias);
    List<PublicContributor> contributors = members.findByProject(p.getId()).stream()
        .filter(m -> !Role.VIEWER.dbValue().equals(m.getRole()))
        .map(m -> {
          AppUser u = users.findById(m.getUserId());
          String name = u == null ? null : (u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
          return new PublicContributor(name, u == null ? null : u.getOrcid(), m.getRole());
        }).toList();

    List<Release> ready = releases.findReadyByProject(p.getId());
    List<PublicRelease> rels = ready.stream().map(r -> new PublicRelease(
        r.getId(), r.getVersion(), r.getNotes(), r.getCreatedAt(), r.getFileName(), r.getFileSize(),
        r.getNameUsageCount(), parse(r.getMetrics()),
        "/api/public/projects/" + p.getId() + "/releases/" + r.getId() + "/download")).toList();

    // Headline metrics: latest release snapshot if any, else a live compute (all-time, since=null).
    JsonNode metrics = ready.isEmpty()
        ? parse(metricsService.compute(p.getId(), null))
        : parse(ready.get(0).getMetrics());

    return new PublicProjectInfo(p.getId(), p.getTitle(), p.getAlias(), p.getDescription(),
        p.getLicense() == null ? null : p.getLicense().name(),
        p.getNomCode() == null ? null : p.getNomCode().name().toLowerCase(java.util.Locale.ROOT),
        p.getGeographicScope(), p.getTaxonomicScope(), contributors, metrics, rels);
  }

  private Project resolve(String idOrAlias) {
    Project p = idOrAlias.matches("\\d+")
        ? projects.findPublicById(Integer.parseInt(idOrAlias))
        : projects.findPublicByAlias(idOrAlias);
    if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    return p;
  }

  private JsonNode parse(String s) {
    if (s == null || s.isBlank()) return null;
    try { return json.readTree(s); } catch (Exception e) { return null; }
  }
}
