package org.catalogueoflife.editor.name.bulk;

import jakarta.validation.Valid;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.name.bulk.dto.BulkInsertRequest;
import org.catalogueoflife.editor.name.bulk.dto.BulkPreviewResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/usages/bulk")
public class BulkInsertController {

  private final BulkInsertService service;
  private final CurrentUser currentUser;

  public BulkInsertController(BulkInsertService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping("/preview")
  public BulkPreviewResponse preview(@PathVariable int pid, @Valid @RequestBody BulkInsertRequest req) {
    int uid = currentUser.require().getId();
    return service.preview(uid, pid, req);
  }
}
