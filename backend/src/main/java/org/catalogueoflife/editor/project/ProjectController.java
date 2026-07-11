package org.catalogueoflife.editor.project;

import jakarta.validation.Valid;
import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.project.dto.ProjectResponse;
import org.catalogueoflife.editor.project.dto.UpdateProjectMetadataRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

  private final ProjectService service;
  private final ProjectMemberMapper members;
  private final CurrentUser currentUser;

  public ProjectController(ProjectService service, ProjectMemberMapper members, CurrentUser currentUser) {
    this.service = service;
    this.members = members;
    this.currentUser = currentUser;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectResponse create(@Valid @RequestBody CreateProjectRequest req) {
    int uid = currentUser.require().getId();
    Project p = service.create(uid, req);
    return ProjectResponse.of(p, Role.OWNER.dbValue());
  }

  @GetMapping
  public List<ProjectResponse> listMine() {
    int uid = currentUser.require().getId();
    return service.listForUser(uid).stream()
        .map(p -> ProjectResponse.of(p, members.findRole(p.getId(), uid)))
        .toList();
  }

  @GetMapping("/{id}")
  public ProjectResponse get(@PathVariable int id) {
    int uid = currentUser.require().getId();
    Project p = service.requireVisible(uid, id);
    return ProjectResponse.of(p, members.findRole(id, uid));
  }

  @PutMapping("/{id}/metadata")
  public ProjectResponse updateMetadata(@PathVariable int id,
                                        @Valid @RequestBody UpdateProjectMetadataRequest req) {
    int uid = currentUser.require().getId();
    Project p = service.updateMetadata(uid, id, req);
    return ProjectResponse.of(p, members.findRole(id, uid));
  }

  @GetMapping("/{id}/members")
  public java.util.List<org.catalogueoflife.editor.project.dto.MemberResponse> members(@PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.listMembers(uid, id);
  }

  @PutMapping("/{id}/members")
  public void setMember(@PathVariable int id,
                        @jakarta.validation.Valid @RequestBody org.catalogueoflife.editor.project.dto.MemberRequest req) {
    int uid = currentUser.require().getId();
    service.setMember(uid, id, req.username(), req.role());
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/{id}/members/{userId}")
  public void removeMember(@PathVariable int id, @PathVariable int userId) {
    int uid = currentUser.require().getId();
    service.removeMember(uid, id, userId);
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
  public void delete(@PathVariable int id) {
    int uid = currentUser.require().getId();
    service.delete(uid, id);
  }

  // Map<String,Boolean> (not a record) avoids a `public`-named record component -- the JSON key
  // on the wire is "public".
  @PutMapping("/{id}/public")
  public void setPublic(@PathVariable int id, @RequestBody java.util.Map<String, Boolean> body) {
    int uid = currentUser.require().getId();
    service.setPublic(uid, id, Boolean.TRUE.equals(body.get("public")));
  }
}
