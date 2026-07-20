package org.catalogueoflife.editor.admin;

import java.util.List;
import org.catalogueoflife.editor.admin.dto.AdminRequest;
import org.catalogueoflife.editor.admin.dto.AdminUserResponse;
import org.catalogueoflife.editor.admin.dto.StateRequest;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

  private final AdminUserService service;
  private final CurrentUser currentUser;

  public AdminUserController(AdminUserService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<AdminUserResponse> list() {
    return service.list(currentUser.require().getId()).stream().map(AdminUserResponse::of).toList();
  }

  @PostMapping("/{id}/state")
  public AdminUserResponse setState(@PathVariable int id, @RequestBody StateRequest req) {
    return AdminUserResponse.of(service.setState(currentUser.require().getId(), id, req.state()));
  }

  @PostMapping("/{id}/admin")
  public AdminUserResponse setAdmin(@PathVariable int id, @RequestBody AdminRequest req) {
    return AdminUserResponse.of(service.setAdmin(currentUser.require().getId(), id, req.admin()));
  }
}
