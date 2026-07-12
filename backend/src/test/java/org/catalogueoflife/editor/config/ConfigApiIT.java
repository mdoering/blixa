package org.catalogueoflife.editor.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ConfigApiIT extends AbstractPostgresIT {
  @Autowired MockMvc mvc;

  @Test
  void configIsPublicAndReportsOrcid() throws Exception {
    // no @WithMockUser: must be reachable anonymously. application-test.yml sets a real client-id.
    mvc.perform(get("/api/config"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.orcidEnabled").value(true));
  }
}
