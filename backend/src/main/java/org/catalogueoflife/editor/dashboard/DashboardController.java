package org.catalogueoflife.editor.dashboard;

import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// The personal dashboard: one aggregation read + a "mark seen" write that resets the pings marker.
@RestController
public class DashboardController {

  private final CurrentUser currentUser;
  private final DashboardService service;
  private final AppUserMapper users;

  public DashboardController(CurrentUser currentUser, DashboardService service, AppUserMapper users) {
    this.currentUser = currentUser;
    this.service = service;
    this.users = users;
  }

  @GetMapping("/api/me/dashboard")
  public DashboardResponse dashboard() {
    return service.build(currentUser.require());
  }

  @PostMapping("/api/me/dashboard/seen")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void seen() {
    users.touchDashboardSeen(currentUser.require().getId());
  }
}
