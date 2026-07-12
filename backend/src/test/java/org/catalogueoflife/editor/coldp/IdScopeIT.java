package org.catalogueoflife.editor.coldp;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// IdScopeService fetches the identifier-scope vocab from the live ChecklistBank endpoint, so it is
// mocked here to keep the test deterministic + offline -- the controller just delegates to it.
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdScopeIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @MockitoBean IdScopeService idScopeService;

  @Test
  @WithMockUser(username = "idScopeUser")
  void listsScopesFromService() throws Exception {
    when(idScopeService.scopes()).thenReturn(List.of("col", "ipni", "inat"));

    mvc.perform(get("/api/coldp/id-scopes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("col")))
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("ipni")))
        // the generic scopes are excluded by IdScopeService (unit-tested in IdScopeFilterTest)
        .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("local"))));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/coldp/id-scopes"))
        .andExpect(status().isUnauthorized());
  }
}
