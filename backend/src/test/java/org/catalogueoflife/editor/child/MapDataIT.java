package org.catalogueoflife.editor.child;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// Subtree map-data endpoint: aggregates distributions + type-specimen points for a focal usage and
// its whole (accepted) subtree, for the map view. See colmap-task-6-brief.md.
@AutoConfigureMockMvc
@WithMockUser(username = "mapOwner")
class MapDataIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject() throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"map\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private String createUsage(long pid, String name, String rank, Long parentId) throws Exception {
    String parentJson = parentId == null ? "" : ",\"parentId\":" + parentId;
    return mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank
                + "\",\"status\":\"accepted\"" + parentJson + "}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  }

  @Test
  void subtreeMapData() throws Exception {
    ensureUser("mapOwner");
    long pid = createProject();

    // Panthera (genus) -> Panthera leo, Panthera tigris (both accepted species).
    String genusBody = createUsage(pid, "Panthera", "genus", null);
    long genusId = json.readTree(genusBody).get("id").asLong();
    int genusVersion = json.readTree(genusBody).get("version").asInt();

    long leoId = json.readTree(createUsage(pid, "Panthera leo", "species", genusId)).get("id").asLong();
    long tigrisId = json.readTree(createUsage(pid, "Panthera tigris", "species", genusId)).get("id").asLong();

    // Distribution on the genus itself.
    mvc.perform(post("/api/projects/" + pid + "/usages/" + genusId + "/distributions").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"areaId\":\"XY\",\"gazetteer\":\"tdwg\"}"))
        .andExpect(status().isCreated());

    // Distribution on a descendant species.
    mvc.perform(post("/api/projects/" + pid + "/usages/" + leoId + "/distributions").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"areaId\":\"AB\",\"gazetteer\":\"tdwg\"}"))
        .andExpect(status().isCreated());

    // Type material WITH lat/lon on that species -- must be included.
    mvc.perform(post("/api/projects/" + pid + "/usages/" + leoId + "/type-material").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"BMNH 1.1\",\"status\":\"holotype\",\"locality\":\"Cape of Good Hope\","
                + "\"latitude\":-33.9,\"longitude\":18.4}"))
        .andExpect(status().isCreated());

    // Type material WITHOUT lat/lon on the same species -- must be excluded.
    mvc.perform(post("/api/projects/" + pid + "/usages/" + leoId + "/type-material").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"BMNH 1.2\",\"status\":\"paratype\"}"))
        .andExpect(status().isCreated());

    // Tag the genus with a COL id via the identifiers endpoint (Task 2).
    mvc.perform(put("/api/projects/" + pid + "/usages/" + genusId + "/identifiers").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"alternativeId\":[\"col:PANTH\"],\"version\":" + genusVersion + "}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/projects/" + pid + "/usages/" + genusId + "/map"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.colId").value("PANTH"))
        .andExpect(jsonPath("$.distributions.length()").value(2))
        .andExpect(jsonPath("$.distributions[0].usageId").value(genusId))
        .andExpect(jsonPath("$.distributions[0].name").value("Panthera"))
        .andExpect(jsonPath("$.distributions[0].focal").value(true))
        .andExpect(jsonPath("$.distributions[0].gazetteer").value("tdwg"))
        .andExpect(jsonPath("$.distributions[0].areaId").value("XY"))
        .andExpect(jsonPath("$.distributions[1].usageId").value(leoId))
        .andExpect(jsonPath("$.distributions[1].name").value("Panthera leo"))
        .andExpect(jsonPath("$.distributions[1].focal").value(false))
        .andExpect(jsonPath("$.distributions[1].gazetteer").value("tdwg"))
        .andExpect(jsonPath("$.distributions[1].areaId").value("AB"))
        .andExpect(jsonPath("$.typeSpecimens.length()").value(1))
        .andExpect(jsonPath("$.typeSpecimens[0].usageId").value(leoId))
        .andExpect(jsonPath("$.typeSpecimens[0].name").value("Panthera leo"))
        .andExpect(jsonPath("$.typeSpecimens[0].focal").value(false))
        .andExpect(jsonPath("$.typeSpecimens[0].status").value("holotype"))
        .andExpect(jsonPath("$.typeSpecimens[0].latitude").value(-33.9))
        .andExpect(jsonPath("$.typeSpecimens[0].longitude").value(18.4))
        .andExpect(jsonPath("$.typeSpecimens[0].locality").value("Cape of Good Hope"));

    // A usage not in the project (or the project not visible to the caller) -> 404, not a leak.
    // (tigrisId exists but has no distribution/type data of its own -- present only to exercise a
    // subtree with multiple descendants.)
    mvc.perform(get("/api/projects/" + pid + "/usages/" + (tigrisId + 100000) + "/map"))
        .andExpect(status().isNotFound());
  }
}
