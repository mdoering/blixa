package org.catalogueoflife.editor.name;

import static org.hamcrest.Matchers.nullValue;
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

@AutoConfigureMockMvc
@WithMockUser(username = "accsynOwner")
class AccSynWorkflowIT extends AbstractPostgresIT {

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private long createProject(String title) throws Exception {
    String body = mvc.perform(post("/api/projects").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private long createUsage(long pid, String name, String rank, String status, Long parentId) throws Exception {
    String content = "{\"scientificName\":\"" + name + "\",\"rank\":\"" + rank + "\",\"status\":\"" + status + "\""
        + (parentId != null ? ",\"parentId\":" + parentId : "") + "}";
    String body = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(content))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private void link(long pid, long synId, long acceptedId) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + acceptedId).with(csrf()))
        .andExpect(status().isNoContent());
  }

  private int version(long pid, long id) throws Exception {
    String body = mvc.perform(get("/api/projects/" + pid + "/usages/" + id))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("version").asInt();
  }

  @Test
  void demoteMovesChildrenAndSynonymsThenPromoteBack() throws Exception {
    ensureUser("accsynOwner");
    long pid = createProject("accsyn");

    long aus = createUsage(pid, "Aus", "genus", "accepted", null);
    long ausBus = createUsage(pid, "Aus bus", "species", "accepted", aus);
    long bus = createUsage(pid, "Bus", "genus", "accepted", null);
    long xus = createUsage(pid, "Xus", "genus", "synonym", null);
    link(pid, xus, aus);

    // Demote Aus -> synonym of Bus; its child follows to Bus; its synonym Xus re-points to Bus.
    mvc.perform(post("/api/projects/" + pid + "/usages/" + aus + "/demote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptedId\":" + bus + ",\"status\":\"SYNONYM\",\"childrenTo\":\"new-accepted\","
                + "\"synonymsTo\":\"new-accepted\",\"version\":" + version(pid, aus) + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SYNONYM"))
        .andExpect(jsonPath("$.parentId").value(nullValue()))
        .andExpect(jsonPath("$.acceptedParentIds[0]").value((int) bus));

    mvc.perform(get("/api/projects/" + pid + "/usages/" + ausBus))
        .andExpect(jsonPath("$.parentId").value((int) bus));
    mvc.perform(get("/api/projects/" + pid + "/usages/" + xus))
        .andExpect(jsonPath("$.acceptedParentIds[0]").value((int) bus));

    // Promote Aus back to accepted under Bus; its synonym links are gone.
    mvc.perform(post("/api/projects/" + pid + "/usages/" + aus + "/promote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"parentId\":" + bus + ",\"version\":" + version(pid, aus) + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.parentId").value((int) bus))
        .andExpect(jsonPath("$.acceptedParentIds.length()").value(0));
  }

  @Test
  void demoteOrphansSynonymAsUnassessed() throws Exception {
    ensureUser("accsynOwner");
    long pid = createProject("accsynua");
    long acc = createUsage(pid, "Accepteda", "genus", "accepted", null);
    long target = createUsage(pid, "Targeta", "genus", "accepted", null);
    long syn = createUsage(pid, "Synonyma", "genus", "synonym", null);
    link(pid, syn, acc);

    mvc.perform(post("/api/projects/" + pid + "/usages/" + acc + "/demote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptedId\":" + target + ",\"status\":\"SYNONYM\",\"synonymsTo\":\"unassessed\","
                + "\"version\":" + version(pid, acc) + "}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/projects/" + pid + "/usages/" + syn))
        .andExpect(jsonPath("$.status").value("UNASSESSED"))
        .andExpect(jsonPath("$.acceptedParentIds.length()").value(0));
  }

  @Test
  void promoteProParteKeepsSelectedRelationAsCopy() throws Exception {
    ensureUser("accsynOwner");
    long pid = createProject("accsynpp");
    long a = createUsage(pid, "Genusa", "genus", "accepted", null);
    long b = createUsage(pid, "Genusb", "genus", "accepted", null);
    long syn = createUsage(pid, "Genusx", "genus", "synonym", null);
    link(pid, syn, a);
    link(pid, syn, b); // pro parte: a synonym of both a and b

    // Promote syn to a root accepted, keeping the relation to b as a separate synonym copy.
    mvc.perform(post("/api/projects/" + pid + "/usages/" + syn + "/promote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"parentId\":null,\"keepAcceptedIds\":[" + b + "],\"version\":" + version(pid, syn) + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.acceptedParentIds.length()").value(0));

    // a lost the synonym; b keeps a copy synonym named "Genusx".
    mvc.perform(get("/api/projects/" + pid + "/usages/" + a))
        .andExpect(jsonPath("$.synonymIds.length()").value(0));
    String bBody = mvc.perform(get("/api/projects/" + pid + "/usages/" + b))
        .andExpect(jsonPath("$.synonymIds.length()").value(1))
        .andReturn().getResponse().getContentAsString();
    long copyId = json.readTree(bBody).get("synonymIds").get(0).asLong();
    mvc.perform(get("/api/projects/" + pid + "/usages/" + copyId))
        .andExpect(jsonPath("$.status").value("SYNONYM"))
        .andExpect(jsonPath("$.scientificName").value("Genusx"))
        .andExpect(jsonPath("$.acceptedParentIds[0]").value((int) b));
  }

  @Test
  void demotePromoteValidation() throws Exception {
    ensureUser("accsynOwner");
    long pid = createProject("accsynval");
    long a = createUsage(pid, "Genusa", "genus", "accepted", null);
    long child = createUsage(pid, "Genusa speciesa", "species", "accepted", a);
    long b = createUsage(pid, "Genusb", "genus", "accepted", null);
    long c = createUsage(pid, "Genusc", "genus", "accepted", null);
    long syn = createUsage(pid, "Synonymb", "genus", "synonym", null);

    // has children but no childrenTo -> 400
    mvc.perform(post("/api/projects/" + pid + "/usages/" + a + "/demote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptedId\":" + b + ",\"status\":\"SYNONYM\",\"version\":" + version(pid, a) + "}"))
        .andExpect(status().isBadRequest());

    // target is a descendant of the node -> 400
    mvc.perform(post("/api/projects/" + pid + "/usages/" + a + "/demote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptedId\":" + child + ",\"status\":\"SYNONYM\",\"childrenTo\":\"new-accepted\","
                + "\"version\":" + version(pid, a) + "}"))
        .andExpect(status().isBadRequest());

    // demoting a non-accepted usage -> 400
    mvc.perform(post("/api/projects/" + pid + "/usages/" + syn + "/demote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptedId\":" + b + ",\"status\":\"SYNONYM\",\"version\":" + version(pid, syn) + "}"))
        .andExpect(status().isBadRequest());

    // stale version -> 409
    mvc.perform(post("/api/projects/" + pid + "/usages/" + b + "/demote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptedId\":" + c + ",\"status\":\"SYNONYM\",\"version\":999}"))
        .andExpect(status().isConflict());

    // promoting an accepted usage -> 400
    mvc.perform(post("/api/projects/" + pid + "/usages/" + b + "/promote").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"parentId\":null,\"version\":" + version(pid, b) + "}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bulkStatusChangeStaysWithinParentPreservingGroups() throws Exception {
    ensureUser("accsynOwner");
    long pid = createProject("bulkstatus");
    long g = createUsage(pid, "Genusb", "genus", "accepted", null);
    long sp1 = createUsage(pid, "Genusb speciesa", "species", "accepted", g);
    long sp2 = createUsage(pid, "Genusb speciesb", "species", "accepted", g);
    long syn1 = createUsage(pid, "Synonyma", "genus", "synonym", null);
    long syn2 = createUsage(pid, "Synonymb", "genus", "synonym", null);
    link(pid, syn1, g);
    link(pid, syn2, g);

    // accepted -> unassessed for both species; the taxonomic parent (g) is preserved.
    mvc.perform(post("/api/projects/" + pid + "/usages/bulk-status").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[" + sp1 + "," + sp2 + "],\"status\":\"UNASSESSED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(2));
    mvc.perform(get("/api/projects/" + pid + "/usages/" + sp1))
        .andExpect(jsonPath("$.status").value("UNASSESSED"))
        .andExpect(jsonPath("$.parentId").value((int) g));
    mvc.perform(get("/api/projects/" + pid + "/usages/" + sp2))
        .andExpect(jsonPath("$.status").value("UNASSESSED"))
        .andExpect(jsonPath("$.parentId").value((int) g));

    // synonym -> misapplied for both synonyms; the accepted name they hang under (g) is preserved.
    mvc.perform(post("/api/projects/" + pid + "/usages/bulk-status").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[" + syn1 + "," + syn2 + "],\"status\":\"MISAPPLIED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(2));
    mvc.perform(get("/api/projects/" + pid + "/usages/" + syn1))
        .andExpect(jsonPath("$.status").value("MISAPPLIED"))
        .andExpect(jsonPath("$.acceptedParentIds[0]").value((int) g));

    // A cross-group transition (unassessed -> synonym) would change the parent -> 400, all-or-nothing.
    mvc.perform(post("/api/projects/" + pid + "/usages/bulk-status").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[" + sp1 + "],\"status\":\"SYNONYM\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/projects/" + pid + "/usages/" + sp1))
        .andExpect(jsonPath("$.status").value("UNASSESSED"));
  }

  @Test
  void unassessedParentRulesAndBackboneGuards() throws Exception {
    ensureUser("accsynOwner");
    long pid = createProject("backbone");
    long accRoot = createUsage(pid, "Accroot", "genus", "accepted", null);
    // An unassessed taxon may hang under an accepted parent...
    long una = createUsage(pid, "Unassa", "species", "unassessed", accRoot);
    // ...and under an unassessed parent ("provisionally accepted" chains are allowed).
    long una2 = createUsage(pid, "Unassb", "subspecies", "unassessed", una);

    // But an ACCEPTED taxon may never hang under an unassessed parent.
    mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scientificName\":\"Accbad\",\"rank\":\"species\",\"status\":\"accepted\",\"parentId\":" + una + "}"))
        .andExpect(status().isBadRequest());

    // Backbone guard: an accepted taxon that still has accepted children can't be made unassessed
    // (those children would then sit under an unassessed parent).
    long accParent = createUsage(pid, "Accparent", "genus", "accepted", null);
    createUsage(pid, "Accparent speciesa", "species", "accepted", accParent);
    mvc.perform(post("/api/projects/" + pid + "/usages/bulk-status").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[" + accParent + "],\"status\":\"UNASSESSED\"}"))
        .andExpect(status().isBadRequest());

    // Backbone guard: an unassessed taxon under an unassessed parent can't be made accepted while
    // that parent is still unassessed.
    mvc.perform(post("/api/projects/" + pid + "/usages/bulk-status").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[" + una2 + "],\"status\":\"ACCEPTED\"}"))
        .andExpect(status().isBadRequest());
  }
}
