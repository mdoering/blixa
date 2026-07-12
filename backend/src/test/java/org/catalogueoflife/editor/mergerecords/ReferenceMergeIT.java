package org.catalogueoflife.editor.mergerecords;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// The destructive apply step of reference merge: repoint every FK/soft-pointer that cites the
// merged (duplicate) reference onto the survivor -- name_usage.published_in_reference_id
// (scalar, ON DELETE SET NULL so MUST be repointed before the delete), name_usage.reference_id[]
// (array, repointed then de-duplicated), and reference_id on the 6 child tables (name_relation,
// type_material, vernacular, distribution, estimate, property, all soft pointers with no FK) --
// then delete the duplicate, all inside one transaction. See
// mergerecords/MergeRecordsService.mergeReferences.
@AutoConfigureMockMvc
@WithMockUser(username = "rmOwner")
class ReferenceMergeIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) { if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u); }

  private long createProject(String title) throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private int createReference(long pid, String citation) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"" + citation + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  private int createUsage(long pid, String name, Integer publishedInReferenceId) throws Exception {
    String c = "{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"ACCEPTED\""
        + (publishedInReferenceId == null ? "" : ",\"publishedInReferenceId\":" + publishedInReferenceId) + "}";
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(c))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  private void createVernacular(long pid, int usageId, String name, int referenceId) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/usages/" + usageId + "/vernaculars").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\",\"language\":\"eng\",\"referenceId\":" + referenceId + "}"))
        .andExpect(status().isCreated());
  }

  private String idsJson(int... ids) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) sb.append(",");
      sb.append(ids[i]);
    }
    return sb.append("]").toString();
  }

  private String previewRequest(int... ids) {
    return "{\"ids\":" + idsJson(ids) + "}";
  }

  private String mergeRequest(int survivorId, int... ids) {
    return "{\"survivorId\":" + survivorId + ",\"ids\":" + idsJson(ids) + "}";
  }

  @Test
  void previewReturnsCitationAndCounts() throws Exception {
    ensureUser("rmOwner");
    long pid = createProject("rm-preview");

    int s = createReference(pid, "Survivor 2020");
    int d = createReference(pid, "Duplicate 2020");
    createUsage(pid, "Aus bus", d); // published_in_reference_id = D -> counts.publishedIn on d

    mvc.perform(post("/api/projects/" + pid + "/references/merge/preview").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(previewRequest(d, s)))
        .andExpect(status().isOk())
        // sorted by id ascending: s (lower id) first
        .andExpect(jsonPath("$[0].id").value(s))
        .andExpect(jsonPath("$[0].citation").value("Survivor 2020"))
        .andExpect(jsonPath("$[0].counts.publishedIn").value(0))
        .andExpect(jsonPath("$[1].id").value(d))
        .andExpect(jsonPath("$[1].citation").value("Duplicate 2020"))
        .andExpect(jsonPath("$[1].counts.publishedIn").value(1));
  }

  @Test
  void mergeRepointsPublishedInAndVernacularAndDeletesDuplicate() throws Exception {
    ensureUser("rmOwner");
    long pid = createProject("rm-apply");

    int s = createReference(pid, "Survivor 2020");
    int d = createReference(pid, "Duplicate 2020");

    int u = createUsage(pid, "Aus bus", d); // published_in_reference_id = D
    int vernUsage = createUsage(pid, "Aus cus", null);
    createVernacular(pid, vernUsage, "Lion", d); // vernacular.reference_id = D

    mvc.perform(post("/api/projects/" + pid + "/references/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.survivorId").value(s))
        .andExpect(jsonPath("$.mergedCount").value(1));

    // D is gone
    mvc.perform(get("/api/projects/" + pid + "/references/" + d))
        .andExpect(status().isNotFound());

    // the usage's publishedInReferenceId now points at S, not left null (proves repoint ran
    // BEFORE delete -- the FK's ON DELETE SET NULL would otherwise have nulled it out)
    mvc.perform(get("/api/projects/" + pid + "/usages/" + u))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishedInReferenceId").value(s));

    // the vernacular's referenceId now points at S
    mvc.perform(get("/api/projects/" + pid + "/usages/" + vernUsage + "/vernaculars"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].referenceId").value(s));
  }

  @Test
  void mergeRepointsAndDedupsReferenceIdArray() throws Exception {
    ensureUser("rmOwner");
    long pid = createProject("rm-array");

    int s = createReference(pid, "Survivor 2020");
    int d = createReference(pid, "Duplicate 2020");

    int u = createUsage(pid, "Aus bus", null);
    // usage cites BOTH S and D taxonomically -- after merge it must list S exactly once, not twice
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/references").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"referenceIds\":[" + s + "," + d + "],\"version\":0}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/projects/" + pid + "/references/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/projects/" + pid + "/usages/" + u))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.referenceId", Matchers.contains(s)));
  }

  @Test
  void mergeRequiresEditorRole() throws Exception {
    ensureUser("rmOwner");
    ensureUser("rmViewer");
    long pid = createProject("rm-authz");

    AppUser viewer = users.requireByUsernameOrNull("rmViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    int s = createReference(pid, "Survivor 2020");
    int d = createReference(pid, "Duplicate 2020");

    mvc.perform(post("/api/projects/" + pid + "/references/merge").with(csrf()).with(user("rmViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isForbidden());
  }

  @Test
  void mergeRequiresAtLeastTwoIds() throws Exception {
    ensureUser("rmOwner");
    long pid = createProject("rm-min");

    int s = createReference(pid, "Survivor 2020");

    mvc.perform(post("/api/projects/" + pid + "/references/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s)))
        .andExpect(status().isBadRequest());
  }
}
