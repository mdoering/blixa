package org.catalogueoflife.editor.tree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubtreeTxtreeExportIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
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

  private long createUsage(long pid, String name, String authorship, String rank, String stat,
      Long parentId) throws Exception {
    String content = "{\"scientificName\":\"" + name + "\",\"authorship\":\"" + authorship
        + "\",\"rank\":\"" + rank + "\",\"status\":\"" + stat + "\""
        + (parentId != null ? ",\"parentId\":" + parentId : "") + "}";
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(content))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  @Test
  @WithMockUser(username = "txOwner")
  void streamsReimportableSubtreeWithSynonyms() throws Exception {
    ensureUser("txOwner");
    ensureUser("txStranger");
    long pid = createProject("txtreeproj");
    long genus = createUsage(pid, "Aus", "", "genus", "accepted", null);
    long species = createUsage(pid, "Aus bus", "L.", "species", "accepted", genus);
    long syn = createUsage(pid, "Aus vetus", "Mill.", "species", "synonym", null);
    mvc.perform(put("/api/projects/" + pid + "/usages/" + syn + "/synonym-of/" + species).with(csrf()))
        .andExpect(status().isNoContent());

    MvcResult res = mvc.perform(get("/api/projects/" + pid + "/tree/" + genus + "/subtree.txtree"))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(res.getResponse().getHeader("Content-Disposition"))
        .contains("attachment").contains(".txtree");

    // the streamed body must re-parse to the same accepted hierarchy with the nested synonym
    Tree<SimpleTreeNode> tree = Tree.simple(
        new ByteArrayInputStream(res.getResponse().getContentAsString().getBytes(StandardCharsets.UTF_8)));
    assertThat(tree.getRoot()).hasSize(1);
    SimpleTreeNode g = tree.getRoot().get(0);
    assertThat(g.name).isEqualTo("Aus");
    assertThat(g.rank).isEqualTo("genus");
    assertThat(g.children).hasSize(1);
    SimpleTreeNode sp = g.children.get(0);
    assertThat(sp.name).isEqualTo("Aus bus L.");
    assertThat(sp.synonyms).hasSize(1);
    assertThat(sp.synonyms.get(0).name).isEqualTo("Aus vetus Mill.");

    // a synonym id is not an accepted taxon -> 404
    mvc.perform(get("/api/projects/" + pid + "/tree/" + syn + "/subtree.txtree"))
        .andExpect(status().isNotFound());

    // a non-member is rejected
    mvc.perform(get("/api/projects/" + pid + "/tree/" + genus + "/subtree.txtree")
            .with(user("txStranger")))
        .andExpect(status().is4xxClientError());
  }
}
