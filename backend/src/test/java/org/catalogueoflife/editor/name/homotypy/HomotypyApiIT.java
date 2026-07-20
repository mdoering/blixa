package org.catalogueoflife.editor.name.homotypy;

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
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@WithMockUser(username = "homoOwner")
class HomotypyApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"botanical\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsage(long pid, String name, String authorship, String rank, String statusValue)
      throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"authorship\":\"" + authorship
                + "\",\"rank\":\"" + rank + "\",\"status\":\"" + statusValue + "\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private void link(long pid, long synId, long acceptedId) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + acceptedId).with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  void detectApplySynonymyAndAuthz() throws Exception {
    ensureUser("homoOwner");
    ensureUser("homoViewer");
    ensureUser("nonMemberHomo"); // exists as a user, but deliberately NOT added as a member of pid
    long pid = createProject("homoproj");

    AppUser viewer = users.requireByUsernameOrNull("homoViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    // Poa annua L. (accepted, basionym) + Ochlopoa annua (L.) H.Scholz (recombination synonym)
    // + Aira pumila (Ronniger) Pursh (heterotypic synonym, recombination of Zophora pumila below).
    // Aira/Zophora is chosen deliberately so the basionym's scientificName ("Zophora pumila")
    // sorts alphabetically AFTER its recombination's ("Aira pumila"): this makes the
    // basionym-first ordering assertion below discriminate from a pure-alphabetical sort.
    long acceptedId = createUsage(pid, "Poa annua", "L.", "species", "accepted");
    long recombId = createUsage(pid, "Ochlopoa annua", "(L.) H.Scholz", "species", "synonym");
    long airaId = createUsage(pid, "Aira pumila", "(Ronniger) Pursh", "species", "synonym");
    // Zophora pumila Ronniger is the basionym of Aira pumila: a second member of the same
    // heterotypic group, added below via an explicit basionym relation.
    long zophoraId = createUsage(pid, "Zophora pumila", "Ronniger", "species", "synonym");
    link(pid, recombId, acceptedId);
    link(pid, airaId, acceptedId);
    link(pid, zophoraId, acceptedId);

    // 1. detect returns a group anchored on the accepted with a basionym relation, alreadyExists=false.
    String detectBody = mvc.perform(
            get("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/detect").with(user("homoOwner")))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    var detectGroups = json.readTree(detectBody).get("groups");
    var acceptedGroup = java.util.stream.StreamSupport.stream(detectGroups.spliterator(), false)
        .filter(g -> g.get("basionymUsageId").asLong() == acceptedId)
        .findFirst().orElseThrow(() -> new AssertionError("no group anchored on " + acceptedId));
    assertThat(acceptedGroup.get("relations")).hasSize(1);
    var basionymRel = acceptedGroup.get("relations").get(0);
    assertThat(basionymRel.get("usageId").asLong()).isEqualTo(recombId);
    assertThat(basionymRel.get("relatedUsageId").asLong()).isEqualTo(acceptedId);
    assertThat(basionymRel.get("type").asString()).isEqualTo("basionym");
    assertThat(basionymRel.get("alreadyExists").asBoolean()).isFalse();

    // authz: viewer may GET detect (200); non-member gets 404.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/detect").with(user("homoViewer")))
        .andExpect(status().isOk());

    // 2. apply persists the basionym relation; a second apply is idempotent (no duplicate).
    String body = "{\"relations\":[{\"usageId\":" + recombId + ",\"relatedUsageId\":" + acceptedId
        + ",\"type\":\"basionym\"}]}";
    mvc.perform(post("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/apply").with(user("homoOwner"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.homotypic[?(@.id == " + recombId + ")]").exists());

    mvc.perform(post("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/apply").with(user("homoOwner"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.homotypic.length()").value(1))
        .andExpect(jsonPath("$.homotypic[?(@.id == " + recombId + ")]").exists());

    // 2b. link Aira pumila (recombination of Zophora pumila) into the same heterotypic group via
    // an explicit basionym relation: Aira -> Zophora.
    String basionymBody = "{\"relations\":[{\"usageId\":" + airaId + ",\"relatedUsageId\":" + zophoraId
        + ",\"type\":\"basionym\"}]}";
    mvc.perform(post("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/apply").with(user("homoOwner"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(basionymBody))
        .andExpect(status().isOk());

    // 3. synonymy returns the recomb under `homotypic` and Zophora pumila + Aira pumila together
    // under `heterotypicGroups`, basionym (Zophora, no parenthetical authorship) sorting first
    // even though "Zophora" sorts alphabetically AFTER "Aira" — this is what distinguishes the
    // basionym-first comparator from a pure-alphabetical one.
    String synonymyBody = mvc.perform(
            get("/api/projects/" + pid + "/usages/" + acceptedId + "/synonymy").with(user("homoOwner")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.homotypic.length()").value(1))
        .andExpect(jsonPath("$.homotypic[0].id").value((int) recombId))
        .andExpect(jsonPath("$.heterotypicGroups.length()").value(1))
        .andExpect(jsonPath("$.heterotypicGroups[0][?(@.id == " + airaId + ")]").exists())
        .andExpect(jsonPath("$.heterotypicGroups[0][?(@.id == " + zophoraId + ")]").exists())
        .andExpect(jsonPath("$.misapplied.length()").value(0))
        .andReturn().getResponse().getContentAsString();

    var heterotypicGroup0 = json.readTree(synonymyBody).get("heterotypicGroups").get(0);
    assertThat(heterotypicGroup0).hasSize(2);
    assertThat(heterotypicGroup0.get(0).get("id").asLong()).isEqualTo(zophoraId);
    assertThat(heterotypicGroup0.get(1).get("id").asLong()).isEqualTo(airaId);

    // authz: viewer may GET synonymy (200) but POST apply is 403; non-member gets 404 everywhere.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + acceptedId + "/synonymy").with(user("homoViewer")))
        .andExpect(status().isOk());
    mvc.perform(post("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/apply").with(user("homoViewer"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());

    mvc.perform(get("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/detect")
            .with(user("nonMemberHomo")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + acceptedId + "/synonymy").with(user("nonMemberHomo")))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/projects/" + pid + "/usages/" + acceptedId + "/homotypic/apply")
            .with(user("nonMemberHomo")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNotFound());
  }
}
