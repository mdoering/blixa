package org.catalogueoflife.editor.name;

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

// Name gender (editable on genera) + genderAgreement (species and below) round-trip, and the derived
// ancestorGenusGender the form shows read-only on a bi/trinomial.
@AutoConfigureMockMvc
@WithMockUser(username = "genderOwner")
class NameGenderIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject() throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"gender\",\"nomCode\":\"botanical\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  private long create(long pid, String content) throws Exception {
    String b = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(content))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(b).get("id").asLong();
  }

  @Test
  void genderOnGenusDerivesToSpeciesAndAgreementRoundTrips() throws Exception {
    ensureUser("genderOwner");
    long pid = createProject();

    long genus = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\","
        + "\"gender\":\"FEMININE\"}");
    // the genus stores its own gender.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + genus))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gender").value("FEMININE"))
        .andExpect(jsonPath("$.ancestorGenusGender").doesNotExist());

    // a species under the genus: no own gender, but genderAgreement + the derived parent gender.
    long species = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\","
        + "\"status\":\"accepted\",\"parentId\":" + genus + ",\"genderAgreement\":true}");
    String body = mvc.perform(get("/api/projects/" + pid + "/usages/" + species))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.genderAgreement").value(true))
        .andExpect(jsonPath("$.ancestorGenusGender").value("FEMININE"))
        .andReturn().getResponse().getContentAsString();
    int version = json.readTree(body).get("version").asInt();

    // flipping the agreement flag persists.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + species).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Abies alba\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + genus + ",\"genderAgreement\":false,\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.genderAgreement").value(false))
        .andExpect(jsonPath("$.ancestorGenusGender").value("FEMININE"));
  }
}
