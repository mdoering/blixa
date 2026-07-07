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
    long uid = currentUser.require().getId();
    Project p = service.create(uid, req);
    return ProjectResponse.of(p, Role.OWNER.dbValue());
  }

  @GetMapping
  public List<ProjectResponse> listMine() {
    long uid = currentUser.require().getId();
    return service.listForUser(uid).stream()
        .map(p -> ProjectResponse.of(p, members.findRole(p.getId(), uid)))
        .toList();
  }

  @GetMapping("/{id}")
  public ProjectResponse get(@PathVariable long id) {
    long uid = currentUser.require().getId();
    Project p = service.requireVisible(uid, id);
    return ProjectResponse.of(p, members.findRole(id, uid));
  }

  @PutMapping("/{id}/metadata")
  public ProjectResponse updateMetadata(@PathVariable long id,
                                        @Valid @RequestBody UpdateProjectMetadataRequest req) {
    long uid = currentUser.require().getId();
    Project p = service.updateMetadata(uid, id, req);
    return ProjectResponse.of(p, members.findRole(id, uid));
  }

  @GetMapping("/{id}/members")
  public java.util.List<org.catalogueoflife.editor.project.dto.MemberResponse> members(@PathVariable long id) {
    long uid = currentUser.require().getId();
    return service.listMembers(uid, id);
  }

  @PutMapping("/{id}/members")
  public void setMember(@PathVariable long id,
                        @jakarta.validation.Valid @RequestBody org.catalogueoflife.editor.project.dto.MemberRequest req) {
    long uid = currentUser.require().getId();
    service.setMember(uid, id, req.username(), req.role());
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/{id}/members/{userId}")
  public void removeMember(@PathVariable long id, @PathVariable long userId) {
    long uid = currentUser.require().getId();
    service.removeMember(uid, id, userId);
  }
}
