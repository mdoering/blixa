package org.catalogueoflife.editor.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.catalogueoflife.editor.name.ReferenceImportService;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "coldp.ai.default-provider=anthropic",
    "coldp.ai.default-model=claude-opus-4-8",
    "coldp.ai.api-keys.anthropic=test-key",
})
class AiSuggestIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired AiUsageMapper usageLog;
  @Autowired ObjectMapper json;

  // The real Anthropic call is never made: the provider registry (and DOI verifier) are mocked.
  @MockitoBean LlmProviderRegistry registry;
  @MockitoBean ReferenceImportService referenceImport;

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

  private long createUsage(long pid, String name) throws Exception {
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"" + name + "\",\"rank\":\"species\",\"status\":\"accepted\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  @WithMockUser(username = "aiEditor")
  void verifiesReferencesRecordsUsageAndGatesToEditors() throws Exception {
    ensureUser("aiEditor");
    ensureUser("aiViewer");
    long pid = createProject("aisuggest");
    long usage = createUsage(pid, "Aus bus");

    AppUser viewer = users.requireByUsernameOrNull("aiViewer");
    members.upsert(new ProjectMember((int) pid, viewer.getId(), Role.VIEWER.dbValue()));

    // canned provider output: a synonym (with a good DOI), and two key references (one good, one bad)
    AiSuggestions canned = new AiSuggestions(
        List.of(new SynonymSuggestion("Aus vetus", "Mill.", "synonym", "10.1/good")),
        List.of(new VernacularSuggestion("bugweed", "eng")),
        List.of(new DistributionSuggestion("Europe")),
        List.of("A small herb."),
        List.of(new ReferenceSuggestion("10.1/good", "Good ref 1859"),
            new ReferenceSuggestion("10.9/bad", "Hallucinated ref")),
        "from Latin");
    LlmProvider provider = mock(LlmProvider.class);
    when(provider.suggest(any(), any())).thenReturn(new AiResult(canned, 120, 60));
    when(registry.require(any())).thenReturn(provider);
    // the "bad" DOI does not resolve -> dropped; the "good" one resolves (default null return = verified)
    when(referenceImport.resolveDoi(anyInt(), anyInt(), eq("10.9/bad")))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "no such doi"));

    mvc.perform(post("/api/projects/" + pid + "/usages/" + usage + "/ai/suggest").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("anthropic"))
        .andExpect(jsonPath("$.model").value("claude-opus-4-8"))
        .andExpect(jsonPath("$.synonyms[0].scientificName").value("Aus vetus"))
        .andExpect(jsonPath("$.synonyms[0].reference.verified").value(true))
        .andExpect(jsonPath("$.vernacularNames[0].name").value("bugweed"))
        // only the resolvable reference survives verification; the hallucinated one is dropped
        .andExpect(jsonPath("$.references.length()").value(1))
        .andExpect(jsonPath("$.references[0].doi").value("10.1/good"))
        .andExpect(jsonPath("$.references[0].verified").value(true));

    // one run recorded for the project
    assertThat(usageLog.countForProject((int) pid)).isEqualTo(1);

    // a viewer (not an editor) is rejected
    mvc.perform(post("/api/projects/" + pid + "/usages/" + usage + "/ai/suggest")
            .with(csrf()).with(user("aiViewer")))
        .andExpect(status().isForbidden());
  }
}
