package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class NameUsageApiIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    AppUser existing = users.requireByUsernameOrNull(username);
    if (existing == null) users.createLocal(username, "pw", username);
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

  private List<Long> longList(JsonNode arrayNode) {
    List<Long> out = new ArrayList<>();
    arrayNode.forEach(n -> out.add(n.asLong()));
    return out;
  }

  @Test
  @WithMockUser(username = "usageOwner")
  void crudSearchSynonymyAndAuthz() throws Exception {
    ensureUser("usageOwner");
    ensureUser("usageViewer");
    long pid = createProject("usageproj");
    long otherPid = createProject("otherusageproj");

    AppUser viewer = users.requireByUsernameOrNull("usageViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    // create an accepted usage: parse-on-write atomizes genus/specificEpithet + formattedName.
    // "status" is a tolerant lower-case string on the wire, parsed into the Status enum
    // server-side and round-tripped back out as the enum's name().
    String createBody = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Abies alba\",\"authorship\":\"Mill.\","
                + "\"rank\":\"species\",\"status\":\"accepted\",\"gender\":\"feminine\","
                + "\"environment\":[\"terrestrial\"],\"temporalRangeStart\":\"Holocene\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.genus").value("Abies"))
        .andExpect(jsonPath("$.specificEpithet").value("alba"))
        .andExpect(jsonPath("$.formattedName").value(containsString("Abies alba")))
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.gender").value("FEMININE"))
        .andExpect(jsonPath("$.environment[0]").value("TERRESTRIAL"))
        .andExpect(jsonPath("$.temporalRangeStart").value("Holocene"))
        .andReturn().getResponse().getContentAsString();
    long accId = json.readTree(createBody).get("id").asLong();

    // an unrecognized status value is a 400, not a 500 or a silently-accepted garbage value
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Bogus status\",\"rank\":\"species\",\"status\":\"bogus\"}"))
        .andExpect(status().isBadRequest());

    // ... same for an unrecognized nomenclatural vocabulary value
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Bogus nomStatus\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"nomStatus\":\"bogus\"}"))
        .andExpect(status().isBadRequest());

    // temporalRange is a free string for now (no vocab validation yet), so an arbitrary value
    // is accepted and stored/returned as-is rather than being rejected as an unrecognized term.
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Free text geotime\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"temporalRangeEnd\":\"bogus\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.temporalRangeEnd").value("bogus"));

    // fuzzy search
    mvc.perform(get("/api/projects/" + pid + "/usages").param("q", "Abies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(accId));
    mvc.perform(get("/api/projects/" + pid + "/usages").param("q", "zzzzz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // the two remaining Status values also round-trip through the enum
    long misappliedId = createUsage(pid, "Abies misapplied", "", "species", "misapplied");
    mvc.perform(get("/api/projects/" + pid + "/usages/" + misappliedId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MISAPPLIED"));
    long unassessedId = createUsage(pid, "Abies unassessed", "", "species", "unassessed");
    mvc.perform(get("/api/projects/" + pid + "/usages/" + unassessedId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNASSESSED"));

    // create a synonym
    long synId = createUsage(pid, "Picea abies", "(L.) H.Karst.", "species", "synonym");

    // link synonym -> accepted
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.synonymIds[0]").value(synId));

    mvc.perform(get("/api/projects/" + pid + "/usages/" + synId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptedParentIds[0]").value(accId));

    // pro parte: attach the same synonym to a SECOND accepted usage too
    long accId2 = createUsage(pid, "Pinus picea", "L.", "species", "accepted");
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId2).with(csrf()))
        .andExpect(status().isNoContent());

    String synAfterProParte = mvc.perform(get("/api/projects/" + pid + "/usages/" + synId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(longList(json.readTree(synAfterProParte).get("acceptedParentIds")))
        .containsExactlyInAnyOrder(accId, accId2);

    // unlink from the second accepted usage again
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId2).with(csrf()))
        .andExpect(status().isNoContent());
    String synAfterUnlink = mvc.perform(get("/api/projects/" + pid + "/usages/" + synId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(longList(json.readTree(synAfterUnlink).get("acceptedParentIds")))
        .containsExactly(accId);

    // update with the loaded version re-parses the changed scientificName
    String getAcc = mvc.perform(get("/api/projects/" + pid + "/usages/" + accId))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    int version = json.readTree(getAcc).get("version").asInt();

    mvc.perform(put("/api/projects/" + pid + "/usages/" + accId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Abies procera\",\"authorship\":\"Rehder\","
                + "\"rank\":\"species\",\"status\":\"accepted\",\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(version + 1))
        .andExpect(jsonPath("$.specificEpithet").value("procera"))
        .andExpect(jsonPath("$.formattedName").value(containsString("Abies procera")));

    // retrying with the now-stale version conflicts
    mvc.perform(put("/api/projects/" + pid + "/usages/" + accId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Abies stale\",\"authorship\":\"Rehder\","
                + "\"rank\":\"species\",\"status\":\"accepted\",\"version\":" + version + "}"))
        .andExpect(status().isConflict());

    // a viewer may read but not write
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user("usageViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Viewer attempt\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/projects/" + pid + "/usages/" + accId).with(csrf()).with(user("usageViewer"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Viewer attempt\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"version\":" + (version + 1) + "}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + accId).with(csrf()).with(user("usageViewer")))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + accId2).with(csrf())
            .with(user("usageViewer")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId).with(user("usageViewer")))
        .andExpect(status().isOk());

    // cross-project guard: linking to/from a usage that belongs to a DIFFERENT project is rejected.
    // Ids are per-project sequences (chunk 2), so bump otherPid's sequence past pid's own usage
    // count (1..6 so far: the "Free text geotime" temporalRange passthrough usage, accId,
    // misappliedId, unassessedId, synId, accId2) first -- otherwise otherPid's usage would happen
    // to reuse one of pid's own ids (e.g. synId's), and the "cross-project" checks below would
    // silently resolve to pid's OWN usage instead of 404/400-ing.
    createUsage(otherPid, "Filler one", "", "species", "accepted");
    createUsage(otherPid, "Filler two", "", "species", "accepted");
    createUsage(otherPid, "Filler three", "", "species", "accepted");
    createUsage(otherPid, "Filler four", "", "species", "accepted");
    createUsage(otherPid, "Filler five", "", "species", "accepted");
    createUsage(otherPid, "Filler six", "", "species", "accepted");
    long otherUsageId = createUsage(otherPid, "Alien name", "Auth.", "species", "accepted");
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + otherUsageId).with(csrf()))
        .andExpect(status().isNotFound());

    // cross-project guard: a parentId pointing at a usage from a DIFFERENT project is rejected on create
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Child of alien\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + otherUsageId + "}"))
        .andExpect(status().isBadRequest());

    // ... and on update
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Picea abies\",\"authorship\":\"(L.) H.Karst.\","
                + "\"rank\":\"species\",\"status\":\"synonym\",\"parentId\":" + otherUsageId
                + ",\"version\":0}"))
        .andExpect(status().isBadRequest());

    // delete then confirm gone
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + accId2).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId2))
        .andExpect(status().isNotFound());
  }
}
