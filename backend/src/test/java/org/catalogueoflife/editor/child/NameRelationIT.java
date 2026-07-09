package org.catalogueoflife.editor.child;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

@AutoConfigureMockMvc
@WithMockUser(username = "relOwner")
class NameRelationIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject() throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"rels\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private long createUsage(long pid, String name) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  @Test
  void crudNameRelation() throws Exception {
    ensureUser("relOwner");
    long pid = createProject();
    long a = createUsage(pid, "Aus bus");
    long b = createUsage(pid, "Xus bus");

    // create a basionym relation a -> b
    String body = mvc.perform(post("/api/projects/" + pid + "/usages/" + a + "/relations").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"relatedUsageId\":" + b + ",\"type\":\"basionym\",\"remarks\":\"orig comb\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("basionym"))
        .andExpect(jsonPath("$.relatedUsageId").value((int) b))
        .andExpect(jsonPath("$.relatedName").value("Xus bus")) // joined display name
        .andReturn().getResponse().getContentAsString();
    long relId = json.readTree(body).get("id").asLong();
    int version = json.readTree(body).get("version").asInt();

    // list
    mvc.perform(get("/api/projects/" + pid + "/usages/" + a + "/relations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value((int) relId));

    // update (version CAS)
    mvc.perform(put("/api/projects/" + pid + "/usages/" + a + "/relations/" + relId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"relatedUsageId\":" + b + ",\"type\":\"homotypic\",\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("homotypic"));

    // stale version -> 409
    mvc.perform(put("/api/projects/" + pid + "/usages/" + a + "/relations/" + relId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"relatedUsageId\":" + b + ",\"type\":\"x\",\"version\":" + version + "}"))
        .andExpect(status().isConflict());

    // delete
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + a + "/relations/" + relId).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + a + "/relations"))
        .andExpect(jsonPath("$.length()").value(0));
  }
}
