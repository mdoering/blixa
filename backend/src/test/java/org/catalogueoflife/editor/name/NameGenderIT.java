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
// genusGender the form shows read-only on a bi/trinomial -- the gender of the NOMENCLATURAL genus
// (the genus token in the name), not the classification ancestor (they coincide for accepted names
// but diverge for synonyms).
@AutoConfigureMockMvc
@WithMockUser(username = "genderOwner")
class NameGenderIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String u) {
    if (users.requireByUsernameOrNull(u) == null) users.createLocal(u, "pw", u);
  }

  private long createProject(String title) throws Exception {
    String b = mvc.perform(post("/api/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"botanical\"}"))
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
    long pid = createProject("gender-agreement");

    long genus = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\","
        + "\"gender\":\"FEMININE\"}");
    // the genus stores its own gender.
    mvc.perform(get("/api/projects/" + pid + "/usages/" + genus))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gender").value("FEMININE"))
        .andExpect(jsonPath("$.genusGender").doesNotExist());

    // a species under the genus: no own gender, but genderAgreement + the derived genus gender.
    long species = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\","
        + "\"status\":\"accepted\",\"parentId\":" + genus + ",\"genderAgreement\":true}");
    String body = mvc.perform(get("/api/projects/" + pid + "/usages/" + species))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.genderAgreement").value(true))
        .andExpect(jsonPath("$.genusGender").value("FEMININE"))
        .andReturn().getResponse().getContentAsString();
    int version = json.readTree(body).get("version").asInt();

    // flipping the agreement flag persists.
    mvc.perform(put("/api/projects/" + pid + "/usages/" + species).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Abies alba\",\"rank\":\"species\",\"status\":\"accepted\","
                + "\"parentId\":" + genus + ",\"genderAgreement\":false,\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.genderAgreement").value(false))
        .andExpect(jsonPath("$.genusGender").value("FEMININE"));
  }

  // A synonym derives the gender of ITS OWN nomenclatural genus, not the accepted classification's.
  @Test
  void synonymDerivesItsOwnNomenclaturalGenusGenderNotTheAcceptedHierarchy() throws Exception {
    ensureUser("genderOwner");
    long pid = createProject("gender-synonym");

    // Accepted "Abies alba" under the feminine genus Abies.
    long abies = create(pid, "{\"scientificName\":\"Abies\",\"rank\":\"genus\",\"status\":\"accepted\","
        + "\"gender\":\"FEMININE\"}");
    long alba = create(pid, "{\"scientificName\":\"Abies alba\",\"rank\":\"species\","
        + "\"status\":\"accepted\",\"parentId\":" + abies + "}");
    // A separate genus Pinus with a DIFFERENT gender (neuter), also in the project.
    create(pid, "{\"scientificName\":\"Pinus\",\"rank\":\"genus\",\"status\":\"accepted\","
        + "\"gender\":\"NEUTER\"}");

    // A synonym "Pinus albus" of "Abies alba": its nomenclatural genus is Pinus (neuter), while its
    // accepted classification genus is Abies (feminine). The derived gender must be NEUTER (Pinus).
    long syn = create(pid, "{\"scientificName\":\"Pinus albus\",\"rank\":\"species\",\"status\":\"synonym\"}");
    mvc.perform(put("/api/projects/" + pid + "/usages/" + syn + "/synonym-of/" + alba).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + syn))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.genusGender").value("NEUTER"));

    // A synonym whose nomenclatural genus (Larix) is NOT a usage in the project -> no derived gender.
    long orphan = create(pid, "{\"scientificName\":\"Larix alba\",\"rank\":\"species\",\"status\":\"synonym\"}");
    mvc.perform(put("/api/projects/" + pid + "/usages/" + orphan + "/synonym-of/" + alba).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + orphan))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.genusGender").doesNotExist());
  }
}
