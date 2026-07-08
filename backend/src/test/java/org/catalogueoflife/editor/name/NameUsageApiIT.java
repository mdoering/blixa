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

    // fuzzy search -- GET /usages now returns {items, total}, not a bare array.
    mvc.perform(get("/api/projects/" + pid + "/usages").param("q", "Abies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(accId));
    mvc.perform(get("/api/projects/" + pid + "/usages").param("q", "zzzzz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.total").value(0));

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

  // GET /usages's rank/status filters + total count (Task 1 of the create/search/actions plan).
  // rank is stored lower-case on name_usage (see parse/ParsedNameMapping.applyTo) while the wire
  // filter is the upper-case enum name like every other vocab field here -- this exercises that
  // NameUsageService normalizes the filter into the stored form before the exact-match SQL, not
  // just that the value is accepted.
  @Test
  @WithMockUser(username = "usageSearchOwner")
  void searchFiltersByRankAndStatusWithTotal() throws Exception {
    ensureUser("usageSearchOwner");
    long pid = createProject("usagesearchproj");

    long albaId = createUsage(pid, "Abies alba", "Mill.", "species", "accepted");
    createUsage(pid, "Abies alpina", "Vill.", "species", "accepted");
    createUsage(pid, "Abies", "Mill.", "genus", "accepted");
    createUsage(pid, "Piceabies alba", "(L.) H.Karst.", "species", "synonym");
    createUsage(pid, "Abies misapplicata", "", "species", "misapplied");
    // 5 usages total: 4 at rank species (2 accepted, 1 synonym, 1 misapplied), 1 at rank genus.

    // no filters: all 5, ordered by scientificName (default order when q is absent).
    mvc.perform(get("/api/projects/" + pid + "/usages"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.total").value(5));

    // rank filter is upper-case on the wire but the column stores the lower-cased form --
    // this only passes if the filter value is normalized before the exact match.
    mvc.perform(get("/api/projects/" + pid + "/usages").param("rank", "SPECIES"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(4))
        .andExpect(jsonPath("$.total").value(4));

    // status filter, independently.
    mvc.perform(get("/api/projects/" + pid + "/usages").param("status", "SYNONYM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].scientificName").value("Piceabies alba"));

    // q fuzzy filter still works standalone: an exact-text query sorts its own exact match first
    // by similarity (avoids hard-coding an exact total, which depends on pg_trgm's similarity
    // threshold/other near-matches in the seed data).
    mvc.perform(get("/api/projects/" + pid + "/usages").param("q", "Abies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].scientificName").value("Abies"));

    // q + rank + status combined with AND: rank=species/status=accepted alone are exact filters
    // that already narrow to exactly {Abies alba, Abies alpina} regardless of q, so adding
    // q="Abies" (which both fuzzy-match comfortably) must still total exactly 2 -- proving the
    // three filters are ANDed, not OR'd or applied independently.
    mvc.perform(get("/api/projects/" + pid + "/usages")
            .param("q", "Abies").param("rank", "species").param("status", "accepted"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.total").value(2));

    // total reflects ALL matches while limit caps the returned items.
    mvc.perform(get("/api/projects/" + pid + "/usages").param("rank", "species").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.total").value(4));

    // an unknown rank/status filter value is a 400, not a 500 or a silently-empty result.
    mvc.perform(get("/api/projects/" + pid + "/usages").param("rank", "bogus"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/projects/" + pid + "/usages").param("status", "bogus"))
        .andExpect(status().isBadRequest());

    // sanity: the accepted-species usage is actually reachable via a plain get, unaffected by the
    // filter normalization above.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + albaId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rank").value("species"));
  }

  // Guards against a synonym leaking a parentId-driven bypass of TreeService.move's
  // cycle/accepted-parent checks: the generic create/update endpoints let parentId be set
  // directly, so they must apply the same guards themselves (see NameUsageService.requireValidParent).
  @Test
  @WithMockUser(username = "parentGuardOwner")
  void parentIdCycleAndAcceptedGuards() throws Exception {
    ensureUser("parentGuardOwner");
    long pid = createProject("parentguardproj");

    // A (accepted, root) -> B (accepted, child of A); S is a synonym, never a valid parent.
    long aId = createUsage(pid, "Guardus alpha", "", "species", "accepted");
    String bBody = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Guardus beta\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + aId + "}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    long bId = json.readTree(bBody).get("id").asLong();
    long sId = createUsage(pid, "Guardus synonym", "", "species", "synonym");

    // create with parentId = a synonym is rejected: a synonym was never a tree node.
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Guardus gamma\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + sId + "}"))
        .andExpect(status().isBadRequest());

    // update setting parentId to itself is rejected.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + aId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Guardus alpha\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + aId + ",\"version\":0}"))
        .andExpect(status().isBadRequest());

    // update setting parentId to one of its own descendants would create a cycle: A -> B is the
    // current tree, so making A a child of its own child B loops the tree back on itself.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + aId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Guardus alpha\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + bId + ",\"version\":0}"))
        .andExpect(status().isBadRequest());

    // update with parentId = a synonym is rejected too, not just on create.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + bId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Guardus beta\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + sId + ",\"version\":0}"))
        .andExpect(status().isBadRequest());

    // sanity: none of the rejected attempts above disturbed the tree -- A is still B's parent.
    mvc.perform(get("/api/projects/" + pid + "/tree/children/" + aId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(bId));
  }

  @Test
  @WithMockUser(username = "synRelOwner")
  void synonymAndAcceptedListingEndpoints() throws Exception {
    ensureUser("synRelOwner");
    ensureUser("synRelViewer");
    long pid = createProject("synrelproj");
    // "synRelViewer" is deliberately NOT added as a member of pid -- used below for the 404 authz check.

    long accId = createUsage(pid, "Zebra accepted", "", "species", "accepted");
    long s1Id = createUsage(pid, "Alpha synonym", "", "species", "synonym");
    long s2Id = createUsage(pid, "Beta synonym", "", "species", "synonym");

    mvc.perform(put("/api/projects/" + pid + "/usages/" + s1Id + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(put("/api/projects/" + pid + "/usages/" + s2Id + "/synonym-of/" + accId).with(csrf()))
        .andExpect(status().isNoContent());

    // GET /{accepted}/synonyms returns both synonyms, ordered by scientificName (Alpha, Beta).
    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId + "/synonyms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(s1Id))
        .andExpect(jsonPath("$[0].scientificName").value("Alpha synonym"))
        .andExpect(jsonPath("$[1].id").value(s2Id))
        .andExpect(jsonPath("$[1].scientificName").value("Beta synonym"));

    // GET /{synonym}/accepted returns the accepted usage it points to.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + s1Id + "/accepted"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(accId));

    // a plain accepted usage with no synonyms has an empty list, not a 404.
    long lonelyAccId = createUsage(pid, "Lonely accepted", "", "species", "accepted");
    mvc.perform(get("/api/projects/" + pid + "/usages/" + lonelyAccId + "/synonyms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // pro parte: s1 also links to a second accepted usage, so it must appear under BOTH accepteds'
    // synonym lists, and s1's own accepted-list must contain both.
    long accId2 = createUsage(pid, "Yankee accepted", "", "species", "accepted");
    mvc.perform(put("/api/projects/" + pid + "/usages/" + s1Id + "/synonym-of/" + accId2).with(csrf()))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId2 + "/synonyms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(s1Id));

    String s1Accepted = mvc.perform(get("/api/projects/" + pid + "/usages/" + s1Id + "/accepted"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    List<Long> s1AcceptedIds = new ArrayList<>();
    json.readTree(s1Accepted).forEach(n -> s1AcceptedIds.add(n.get("id").asLong()));
    assertThat(s1AcceptedIds).containsExactlyInAnyOrder(accId, accId2);

    // a user who isn't a member of the project gets 404, matching GET /usages/{id}'s own authz.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + accId + "/synonyms").with(user("synRelViewer")))
        .andExpect(status().isNotFound());

    // anchor id that doesn't exist in the project -> 404, not an empty list.
    mvc.perform(get("/api/projects/" + pid + "/usages/999999/synonyms"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/projects/" + pid + "/usages/999999/accepted"))
        .andExpect(status().isNotFound());
  }
}
