package org.catalogueoflife.editor.mergerecords;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// The destructive apply step of usage merge: repoint every FK pointing at the merged (duplicate)
// usages onto the survivor, then delete the duplicates -- all inside one transaction under the
// project advisory lock. See mergerecords/MergeRecordsService.mergeUsages.
@AutoConfigureMockMvc
@WithMockUser(username = "maOwner")
class UsageMergeApplyIT extends AbstractPostgresIT {

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

  private int createUsage(long pid, String name, String rank, String status, Integer parentId) throws Exception {
    String c = "{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank + "\",\"status\":\"" + status + "\""
        + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}";
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(c))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  private void linkSynonym(long pid, int synId, int accId) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());
  }

  private int createVernacular(long pid, int usageId, String name) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/usages/" + usageId + "/vernaculars").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\",\"language\":\"eng\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asInt();
  }

  private void createRelation(long pid, int usageId, int relatedUsageId, String type) throws Exception {
    mvc.perform(post("/api/projects/" + pid + "/usages/" + usageId + "/relations").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"relatedUsageId\":" + relatedUsageId + ",\"type\":\"" + type + "\"}"))
        .andExpect(status().isCreated());
  }

  private String mergeRequest(int survivorId, int... ids) {
    StringBuilder idList = new StringBuilder();
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) idList.append(",");
      idList.append(ids[i]);
    }
    return "{\"survivorId\":" + survivorId + ",\"ids\":[" + idList + "]}";
  }

  @Test
  void mergeRepointsAllFksAndDeletesDuplicate() throws Exception {
    ensureUser("maOwner");
    long pid = createProject("ma1");

    int s = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    int d = createUsage(pid, "Aus", "genus", "ACCEPTED", null); // duplicate of s

    // D has an accepted child
    int child = createUsage(pid, "Aus bus", "species", "ACCEPTED", d);
    // D has a synonym pointing at it
    int syn = createUsage(pid, "Xus", "genus", "SYNONYM", null);
    linkSynonym(pid, syn, d);
    // D is the target of a name relation (usage_id side)
    createRelation(pid, d, child, "basionym");
    // D is also the RELATED end of a name relation owned by a THIRD usage (child) -- exercises
    // repointRelationRelated, the no-FK soft-pointer repoint (related_usage_id has no FK/cascade)
    createRelation(pid, child, d, "spelling_correction");
    // D has a vernacular
    createVernacular(pid, d, "Lion");
    // D also has an advisory lock on it -- merge must release it (MergeRecordsService.mergeUsages's
    // locks.deleteByEntity call), since holding a lock on a now-deleted duplicate is meaningless.
    mvc.perform(post("/api/projects/" + pid + "/locks").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"entityType\":\"name_usage\",\"entityId\":" + d + "}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/projects/" + pid + "/usages/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.survivorId").value(s))
        .andExpect(jsonPath("$.mergedCount").value(1));

    // D is gone
    mvc.perform(get("/api/projects/" + pid + "/usages/" + d))
        .andExpect(status().isNotFound());

    // D's advisory lock is gone too -- no longer among the project's active locks.
    String activeLocksBody = mvc.perform(get("/api/projects/" + pid + "/locks"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    for (JsonNode lock : json.readTree(activeLocksBody)) {
      assertThat(lock.get("entityId").asInt()).isNotEqualTo(d);
    }

    // D's child now hangs under S
    mvc.perform(get("/api/projects/" + pid + "/usages/" + child))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentId").value(s));

    // S now lists the synonym that used to point at D
    mvc.perform(get("/api/projects/" + pid + "/usages/" + s + "/synonyms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(syn));

    // the name relation now hangs off S
    mvc.perform(get("/api/projects/" + pid + "/usages/" + s + "/relations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].relatedUsageId").value(child));

    // the OTHER relation's related_usage_id (soft pointer, no FK) is now repointed from D to S
    mvc.perform(get("/api/projects/" + pid + "/usages/" + child + "/relations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].relatedUsageId").value(s));

    // the vernacular now hangs under S
    mvc.perform(get("/api/projects/" + pid + "/usages/" + s + "/vernaculars"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Lion"));
  }

  @Test
  void mergeDedupsSynonymAcceptedLinksWithoutPkViolation() throws Exception {
    ensureUser("maOwner");
    long pid = createProject("ma2");

    int accepted = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    int s = createUsage(pid, "Xus", "genus", "SYNONYM", null);
    int d = createUsage(pid, "Xus", "genus", "SYNONYM", null); // duplicate synonym
    linkSynonym(pid, s, accepted);
    linkSynonym(pid, d, accepted); // both S and D point at the same accepted usage

    mvc.perform(post("/api/projects/" + pid + "/usages/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mergedCount").value(1));

    // only one synonym_accepted link remains -- no PK violation, no duplicate row
    mvc.perform(get("/api/projects/" + pid + "/usages/" + accepted + "/synonyms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(s));
  }

  @Test
  void mergeRejectsNonAcceptedSurvivorThatWouldReceiveChildren() throws Exception {
    ensureUser("maOwner");
    long pid = createProject("ma3");

    int s = createUsage(pid, "Xus", "genus", "SYNONYM", null); // survivor is a synonym
    int acc = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    linkSynonym(pid, s, acc);
    int d = createUsage(pid, "Xus", "genus", "ACCEPTED", null); // D is accepted, has a child
    createUsage(pid, "Xus bus", "species", "ACCEPTED", d);

    mvc.perform(post("/api/projects/" + pid + "/usages/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mergeRejectsNonAcceptedSurvivorThatWouldReceiveSynonyms() throws Exception {
    ensureUser("maOwner");
    long pid = createProject("ma3b");

    // A is accepted and has a synonym (synonym_accepted.accepted_id = A) -- merging A onto a
    // non-accepted survivor would repoint that synonym's accepted_id onto the survivor, chaining
    // a synonym onto a synonym, which is forbidden.
    int a = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    int syn = createUsage(pid, "Ausyn", "genus", "SYNONYM", null);
    linkSynonym(pid, syn, a);

    // survivor S is itself a synonym of some unrelated accepted usage -- not accepted
    int otherAcc = createUsage(pid, "Bus", "genus", "ACCEPTED", null);
    int s = createUsage(pid, "Bxus", "genus", "SYNONYM", null);
    linkSynonym(pid, s, otherAcc);

    mvc.perform(post("/api/projects/" + pid + "/usages/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, a)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mergeRejectsAcceptedSurvivorAbsorbingForeignSynonym() throws Exception {
    ensureUser("maOwner");
    long pid = createProject("ma3c");

    int s = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    int x = createUsage(pid, "Zus", "genus", "ACCEPTED", null); // a DIFFERENT accepted usage
    int d = createUsage(pid, "Xus", "genus", "SYNONYM", null);
    linkSynonym(pid, d, x); // d is a synonym of x, not of s

    mvc.perform(post("/api/projects/" + pid + "/usages/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isBadRequest());

    // rolled back: d still exists
    mvc.perform(get("/api/projects/" + pid + "/usages/" + d))
        .andExpect(status().isOk());
  }

  @Test
  void mergeAllowsSynonymIntoItsOwnAcceptedSurvivor() throws Exception {
    ensureUser("maOwner");
    long pid = createProject("ma3d");

    int s = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    int d = createUsage(pid, "Xus", "genus", "SYNONYM", null);
    linkSynonym(pid, d, s); // d is a synonym of s itself

    mvc.perform(post("/api/projects/" + pid + "/usages/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isOk());

    // d is gone
    mvc.perform(get("/api/projects/" + pid + "/usages/" + d))
        .andExpect(status().isNotFound());

    // the self-link was dropped, not left dangling on the survivor
    mvc.perform(get("/api/projects/" + pid + "/usages/" + s + "/synonyms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void mergeRequiresEditorRole() throws Exception {
    ensureUser("maOwner");
    ensureUser("maViewer");
    long pid = createProject("ma4");

    AppUser viewer = users.requireByUsernameOrNull("maViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    int s = createUsage(pid, "Aus", "genus", "ACCEPTED", null);
    int d = createUsage(pid, "Aus", "genus", "ACCEPTED", null);

    mvc.perform(post("/api/projects/" + pid + "/usages/merge").with(csrf()).with(user("maViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s, d)))
        .andExpect(status().isForbidden());
  }

  @Test
  void mergeRequiresAtLeastTwoIds() throws Exception {
    ensureUser("maOwner");
    long pid = createProject("ma5");

    int s = createUsage(pid, "Aus", "genus", "ACCEPTED", null);

    mvc.perform(post("/api/projects/" + pid + "/usages/merge").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mergeRequest(s, s)))
        .andExpect(status().isBadRequest());
  }
}
