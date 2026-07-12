package org.catalogueoflife.editor.mergerecords;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.mergerecords.dto.MergeRequest;
import org.catalogueoflife.editor.mergerecords.dto.MergeResult;
import org.catalogueoflife.editor.mergerecords.dto.ReferenceMergeCandidate;
import org.catalogueoflife.editor.mergerecords.dto.UsageMergeCandidate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}")
public class MergeRecordsController {

  private final MergeRecordsService service;
  private final CurrentUser currentUser;

  public MergeRecordsController(MergeRecordsService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @PostMapping("/usages/merge/preview")
  public List<UsageMergeCandidate> previewUsages(@PathVariable int pid, @RequestBody MergeRequest req) {
    int uid = currentUser.require().getId();
    return service.previewUsages(uid, pid, req.ids());
  }

  @PostMapping("/usages/merge")
  public MergeResult mergeUsages(@PathVariable int pid, @RequestBody MergeRequest req) {
    int uid = currentUser.require().getId();
    return service.mergeUsages(uid, pid, req.survivorId(), req.ids());
  }

  @PostMapping("/references/merge/preview")
  public List<ReferenceMergeCandidate> previewReferences(@PathVariable int pid, @RequestBody MergeRequest req) {
    int uid = currentUser.require().getId();
    return service.previewReferences(uid, pid, req.ids());
  }

  @PostMapping("/references/merge")
  public MergeResult mergeReferences(@PathVariable int pid, @RequestBody MergeRequest req) {
    int uid = currentUser.require().getId();
    return service.mergeReferences(uid, pid, req.survivorId(), req.ids());
  }
}
