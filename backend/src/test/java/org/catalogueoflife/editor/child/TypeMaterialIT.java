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
@WithMockUser(username = "typeOwner")
class TypeMaterialIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject() throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"types\",\"nomCode\":\"zoological\"}"))
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
  void crudTypeMaterial() throws Exception {
    ensureUser("typeOwner");
    long pid = createProject();
    long u = createUsage(pid, "Aus bus");

    // create a holotype with a GBIF occurrenceId
    String body = mvc.perform(post("/api/projects/" + pid + "/usages/" + u + "/type-material").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"BMNH 1901.1.1\",\"status\":\"holotype\","
                + "\"institutionCode\":\"BMNH\",\"occurrenceId\":\"gbif:12345\","
                + "\"country\":\"GB\",\"date\":\"1901-01-01\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("holotype"))
        .andExpect(jsonPath("$.occurrenceId").value("gbif:12345"))
        .andExpect(jsonPath("$.date").value("1901-01-01"))
        .andReturn().getResponse().getContentAsString();
    long tmId = json.readTree(body).get("id").asLong();
    int version = json.readTree(body).get("version").asInt();

    // list
    mvc.perform(get("/api/projects/" + pid + "/usages/" + u + "/type-material"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value((int) tmId))
        .andExpect(jsonPath("$[0].institutionCode").value("BMNH"));

    // update (version CAS)
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/type-material/" + tmId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"citation\":\"BMNH 1901.1.1\",\"status\":\"lectotype\",\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("lectotype"));

    // stale version -> 409
    mvc.perform(put("/api/projects/" + pid + "/usages/" + u + "/type-material/" + tmId).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"x\",\"version\":" + version + "}"))
        .andExpect(status().isConflict());

    // delete
    mvc.perform(delete("/api/projects/" + pid + "/usages/" + u + "/type-material/" + tmId).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + u + "/type-material"))
        .andExpect(jsonPath("$.length()").value(0));
  }
}
