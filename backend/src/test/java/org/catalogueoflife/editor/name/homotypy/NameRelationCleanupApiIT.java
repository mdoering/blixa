package org.catalogueoflife.editor.name.homotypy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.project.ProjectMember;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.catalogueoflife.editor.validation.BulkValidationEvent;
import org.catalogueoflife.editor.validation.ValidationAsyncConfig;
import org.catalogueoflife.editor.validation.ValidationEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// The read-only relation cleanup scan (GET /name-relations/conflicts) against the real
// Postgres+MockMvc stack. Helpers follow ConsolidationApiIT; the project is zoological so the
// bracketed authorship "(Smith, 1900)" marks the name with a basionym.
@AutoConfigureMockMvc
@WithMockUser(username = "relOwner")
@RecordApplicationEvents
class NameRelationCleanupApiIT extends AbstractPostgresIT {

  private static final String OWNER = "relOwner";
  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired AppUserService users;
  @Autowired ProjectMemberMapper members;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired @Qualifier(ValidationAsyncConfig.EXECUTOR_BEAN) Executor validationExecutor;
  @MockitoSpyBean NameUsageMapper usageMapper;
  @MockitoSpyBean NameRelationMapper relationMapper;
  @Autowired ApplicationEvents appEvents;

  // ---- helpers ----

  private void ensureUser(String username) {
    if (users.requireByUsernameOrNull(username) == null) users.createLocal(username, "pw", username);
  }

  private long createProject() throws Exception {
    ensureUser(OWNER);
    String body = mvc.perform(post("/api/projects").with(csrf()).with(user(OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"relproj " + SEQ.incrementAndGet() + "\",\"nomCode\":\"zoological\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asLong();
  }

  private void addMember(long pid, String username, Role role) {
    ensureUser(username);
    AppUser u = users.requireByUsernameOrNull(username);
    members.upsert(new ProjectMember((int) pid, u.getId(), role.dbValue()));
  }

  private long createUsage(long pid, String name, String authorship, String rank, String statusValue,
      Long parentId) throws Exception {
    String parentField = parentId == null ? "" : ",\"parentId\":" + parentId;
    String body = "{\"scientificName\":\"" + name + "\",\"authorship\":\"" + authorship
        + "\",\"rank\":\"" + rank + "\",\"status\":\"" + statusValue + "\"" + parentField + "}";
    String resp = mvc.perform(post("/api/projects/" + pid + "/usages").with(csrf()).with(user(OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(resp).get("id").asLong();
  }

  private long species(long pid, String name, String authorship, long familyId) throws Exception {
    return createUsage(pid, name, authorship, "species", "accepted", familyId);
  }

  private long synonym(long pid, String name, String authorship, long acceptedId) throws Exception {
    long id = createUsage(pid, name, authorship, "species", "synonym", null);
    link(pid, id, acceptedId);
    return id;
  }

  private long family(long pid, String name) throws Exception {
    return createUsage(pid, name, "", "family", "accepted", null);
  }

  private void link(long pid, long synId, long acceptedId) throws Exception {
    mvc.perform(put("/api/projects/" + pid + "/usages/" + synId + "/synonym-of/" + acceptedId).with(csrf())
            .with(user(OWNER)))
        .andExpect(status().isNoContent());
  }

  private long relate(long pid, long usageId, Long relatedId, String type) throws Exception {
    return relate(pid, usageId, relatedId, type, null, null, null);
  }

  // Created through the API, which stores the type string as given (no normalization).
  private long relate(long pid, long usageId, Long relatedId, String type, Integer referenceId,
      String page, String remarks) throws Exception {
    var body = json.createObjectNode();
    if (relatedId != null) body.put("relatedUsageId", relatedId);
    body.put("type", type);
    if (referenceId != null) body.put("referenceId", referenceId);
    if (page != null) body.put("page", page);
    if (remarks != null) body.put("remarks", remarks);
    String resp = mvc.perform(post("/api/projects/" + pid + "/usages/" + usageId + "/relations")
            .with(csrf()).with(user(OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(resp).get("id").asLong();
  }

  private JsonNode scan(long pid, Long rootId) throws Exception {
    return scanAs(pid, rootId, OWNER);
  }

  private JsonNode scanAs(long pid, Long rootId, String username) throws Exception {
    String url = "/api/projects/" + pid + "/name-relations/conflicts" + (rootId == null ? "" : "?rootId=" + rootId);
    String resp = mvc.perform(get(url).with(user(username)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return json.readTree(resp);
  }

  private static List<JsonNode> list(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false).toList();
  }

  private static JsonNode pair(JsonNode pairs, long id1, long id2) {
    return list(pairs).stream()
        .filter(p -> Set.of(p.get("n1").get("id").asLong(), p.get("n2").get("id").asLong()).equals(Set.of(id1, id2)))
        .findFirst().orElseThrow(() -> new AssertionError("no pair " + id1 + "/" + id2 + " in " + pairs));
  }

  private static boolean hasPair(JsonNode pairs, long id1, long id2) {
    return list(pairs).stream()
        .anyMatch(p -> Set.of(p.get("n1").get("id").asLong(), p.get("n2").get("id").asLong()).equals(Set.of(id1, id2)));
  }

  private static JsonNode relation(JsonNode pair, long relationId) {
    return list(pair.get("relations")).stream()
        .filter(r -> r.get("id").asLong() == relationId)
        .findFirst().orElseThrow(() -> new AssertionError("no relation " + relationId + " in " + pair));
  }

  private static List<String> issues(JsonNode relation) {
    return list(relation.get("issues")).stream().map(JsonNode::asString).toList();
  }

  // Waits until the async validation triggered by the seeding requests has finished, so its own
  // mapper calls do not land inside the scan we count calls of.
  private void awaitValidation() throws InterruptedException {
    ThreadPoolTaskExecutor ex = (ThreadPoolTaskExecutor) validationExecutor;
    long deadline = System.currentTimeMillis() + 60_000;
    int idle = 0;
    while (idle < 5 && System.currentTimeMillis() < deadline) {
      boolean busy = ex.getActiveCount() > 0 || !ex.getThreadPoolExecutor().getQueue().isEmpty();
      idle = busy ? 0 : idle + 1;
      Thread.sleep(100);
    }
    assertThat(idle).as("validation pool still busy after 60s").isGreaterThanOrEqualTo(5);
  }

  // ---- tests ----

  @Test
  void basionymAndSpellingCorrectionPair() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    // neither cites a basionym authorship, so the basionym is implausible and goes
    long bus = species(pid, "Aus bus", "Smith, 1900", fam);
    long buss = species(pid, "Aus buss", "Smith, 1900", fam);
    long basionym = relate(pid, buss, bus, "basionym");
    long spelling = relate(pid, buss, bus, "spelling correction");

    JsonNode pairs = scan(pid, null);
    assertThat(pairs.size()).isEqualTo(1);
    JsonNode p = pair(pairs, bus, buss);
    assertThat(p.get("relations").size()).isEqualTo(2);
    JsonNode b = relation(p, basionym);
    assertThat(b.get("remove").asBoolean()).isTrue();
    assertThat(b.get("preselected").asBoolean()).isTrue();
    assertThat(b.get("duplicate").asBoolean()).isFalse();
    assertThat(issues(b)).containsExactly("NAME_RELATION_CONFLICT");
    assertThat(b.get("type").asString()).isEqualTo("basionym");
    assertThat(b.get("usageId").asLong()).isEqualTo(buss);
    assertThat(b.get("relatedUsageId").asLong()).isEqualTo(bus);
    assertThat(b.get("version").asInt()).isEqualTo(0);
    JsonNode s = relation(p, spelling);
    assertThat(s.get("remove").asBoolean()).isFalse();
    assertThat(s.get("preselected").asBoolean()).isFalse();
    assertThat(issues(s)).isEmpty();
    // labels and statuses of both names
    JsonNode bn = p.get("n1").get("id").asLong() == bus ? p.get("n1") : p.get("n2");
    assertThat(bn.get("label").asString()).isEqualTo("Aus bus Smith, 1900");
    assertThat(bn.get("status").asString()).isEqualTo("ACCEPTED");
  }

  @Test
  void laterHomonymWithHomotypicRelation() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long senior = species(pid, "Aus cus", "Smith, 1900", fam);
    long junior = species(pid, "Aus cus", "Jones, 1950", fam);
    long homonym = relate(pid, junior, senior, "later homonym");
    long homotypic = relate(pid, junior, senior, "homotypic");

    JsonNode p = pair(scan(pid, null), senior, junior);
    JsonNode h = relation(p, homotypic);
    assertThat(h.get("remove").asBoolean()).isTrue();
    assertThat(h.get("preselected").asBoolean()).isTrue();
    assertThat(issues(h)).containsExactly("NAME_RELATION_CONFLICT");
    JsonNode lh = relation(p, homonym);
    assertThat(lh.get("remove").asBoolean()).isFalse();
    assertThat(lh.get("preselected").asBoolean()).isFalse();
  }

  @Test
  void relationGivenBothWays() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long original = species(pid, "Aus dus", "Smith, 1900", fam);
    long combination = species(pid, "Bus dus", "(Smith, 1900)", fam);
    long right = relate(pid, combination, original, "basionym");
    long wrong = relate(pid, original, combination, "basionym");

    JsonNode p = pair(scan(pid, null), original, combination);
    assertThat(relation(p, wrong).get("remove").asBoolean()).isTrue();
    assertThat(relation(p, wrong).get("preselected").asBoolean()).isTrue();
    assertThat(relation(p, right).get("remove").asBoolean()).isFalse();
    assertThat(relation(p, right).get("preselected").asBoolean()).isFalse();
  }

  @Test
  void exactDuplicateWithoutExtraContentIsPreselected() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long original = species(pid, "Aus eus", "Smith, 1900", fam);
    long combination = species(pid, "Bus eus", "(Smith, 1900)", fam);
    long first = relate(pid, combination, original, "basionym");
    long second = relate(pid, combination, original, "basionym");

    JsonNode p = pair(scan(pid, null), original, combination);
    assertThat(p.get("relations").size()).isEqualTo(2);
    JsonNode kept = relation(p, first);
    assertThat(kept.get("duplicate").asBoolean()).isFalse();
    assertThat(kept.get("preselected").asBoolean()).isFalse();
    assertThat(issues(kept)).isEmpty();
    JsonNode dup = relation(p, second);
    assertThat(dup.get("duplicate").asBoolean()).isTrue();
    assertThat(dup.get("preselected").asBoolean()).isTrue();
    assertThat(dup.get("remove").asBoolean()).isFalse();
    assertThat(issues(dup)).containsExactly("DUPLICATE");
  }

  @Test
  void duplicateCarryingAReferenceIsKeptOrUnticked() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long original = species(pid, "Aus fus", "Smith, 1900", fam);
    long combination = species(pid, "Bus fus", "(Smith, 1900)", fam);
    long bare = relate(pid, combination, original, "basionym");
    long referenced = relate(pid, combination, original, "basionym", 7, null, null);

    JsonNode p = pair(scan(pid, null), original, combination);
    // the row with the reference is kept, even though the judge kept the first (lower id) one
    JsonNode kept = relation(p, referenced);
    assertThat(kept.get("duplicate").asBoolean()).isFalse();
    assertThat(kept.get("preselected").asBoolean()).isFalse();
    assertThat(kept.get("referenceId").asInt()).isEqualTo(7);
    JsonNode dup = relation(p, bare);
    assertThat(dup.get("duplicate").asBoolean()).isTrue();
    assertThat(dup.get("preselected").asBoolean()).isTrue();
    assertThat(issues(dup)).containsExactly("DUPLICATE");

    // both rows carry different remarks: the duplicate holds content the kept row lacks
    long pid2 = createProject();
    long fam2 = family(pid2, "Aidae");
    long original2 = species(pid2, "Aus fus", "Smith, 1900", fam2);
    long combination2 = species(pid2, "Bus fus", "(Smith, 1900)", fam2);
    long one = relate(pid2, combination2, original2, "basionym", null, null, "from WoRMS");
    long two = relate(pid2, combination2, original2, "basionym", null, null, "see Smith 1900, p. 3");

    JsonNode p2 = pair(scan(pid2, null), original2, combination2);
    JsonNode kept2 = relation(p2, one); // equally filled -> lowest id kept
    assertThat(kept2.get("duplicate").asBoolean()).isFalse();
    assertThat(kept2.get("remarks").asString()).isEqualTo("from WoRMS");
    JsonNode dup2 = relation(p2, two);
    assertThat(dup2.get("duplicate").asBoolean()).isTrue();
    assertThat(dup2.get("preselected").asBoolean()).isFalse();
    assertThat(issues(dup2)).containsExactly("DUPLICATE");
  }

  // The judge removes the basionym (neither name cites a basionym authorship) and attaches its
  // verdict to the first row it sees, the bare one. The service keeps the referenced row instead:
  // the verdict must follow to it, and the bare twin is removed and preselected too.
  @Test
  void removedRelationWithContentCarryingDuplicate() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long bus = species(pid, "Aus bus", "Smith, 1900", fam);
    long buss = species(pid, "Aus buss", "Smith, 1900", fam);
    long bare = relate(pid, buss, bus, "basionym");
    long referenced = relate(pid, buss, bus, "basionym", 7, null, null);
    long spelling = relate(pid, buss, bus, "spelling correction");

    JsonNode p = pair(scan(pid, null), bus, buss);
    assertThat(p.get("relations").size()).isEqualTo(3);
    JsonNode kept = relation(p, referenced);
    assertThat(kept.get("duplicate").asBoolean()).isFalse();
    assertThat(kept.get("remove").asBoolean()).isTrue();
    assertThat(kept.get("preselected").asBoolean()).isTrue();
    assertThat(issues(kept)).containsExactly("NAME_RELATION_CONFLICT");
    JsonNode twin = relation(p, bare);
    assertThat(twin.get("duplicate").asBoolean()).isTrue();
    assertThat(twin.get("remove").asBoolean()).isTrue();
    assertThat(twin.get("preselected").asBoolean()).isTrue();
    assertThat(issues(twin)).containsExactly("NAME_RELATION_CONFLICT", "DUPLICATE");
    JsonNode s = relation(p, spelling);
    assertThat(s.get("remove").asBoolean()).isFalse();
    assertThat(s.get("preselected").asBoolean()).isFalse();
    assertThat(issues(s)).isEmpty();

    // Both basionym rows carry different remarks: the duplicate holds content the kept row lacks,
    // yet it is preselected, as the relation itself is removed. The kept row needs more filled
    // fields than the first-seen bare-remarks row, so the swap is exercised here too.
    long pid2 = createProject();
    long fam2 = family(pid2, "Aidae");
    long bus2 = species(pid2, "Aus bus", "Smith, 1900", fam2);
    long buss2 = species(pid2, "Aus buss", "Smith, 1900", fam2);
    long first = relate(pid2, buss2, bus2, "basionym", null, null, "from WoRMS");
    long richer = relate(pid2, buss2, bus2, "basionym", null, "p. 3", "see Smith 1900");
    relate(pid2, buss2, bus2, "spelling correction");

    JsonNode p2 = pair(scan(pid2, null), bus2, buss2);
    JsonNode kept2 = relation(p2, richer);
    assertThat(kept2.get("duplicate").asBoolean()).isFalse();
    assertThat(kept2.get("remove").asBoolean()).isTrue();
    assertThat(kept2.get("preselected").asBoolean()).isTrue();
    assertThat(issues(kept2)).containsExactly("NAME_RELATION_CONFLICT");
    JsonNode twin2 = relation(p2, first);
    assertThat(twin2.get("duplicate").asBoolean()).isTrue();
    assertThat(twin2.get("remove").asBoolean()).isTrue();
    assertThat(twin2.get("preselected").asBoolean()).isTrue();
    assertThat(issues(twin2)).containsExactly("NAME_RELATION_CONFLICT", "DUPLICATE");
  }

  @Test
  void cleanPairNotListed() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long original = species(pid, "Aus gus", "Smith, 1900", fam);
    long combination = species(pid, "Bus gus", "(Smith, 1900)", fam);
    relate(pid, combination, original, "basionym");

    assertThat(scan(pid, null).size()).isZero();
    assertThat(scan(pid, fam).size()).isZero();
  }

  @Test
  void subtreeScope() throws Exception {
    long pid = createProject();
    long aidae = family(pid, "Aidae");
    long bidae = family(pid, "Bidae");
    long inside = species(pid, "Aus hus", "Smith, 1900", aidae);
    long outside1 = species(pid, "Bus hus", "(Smith, 1900)", bidae);
    long outside2 = species(pid, "Bus ius", "Smith, 1900", bidae);
    long outside3 = species(pid, "Cus ius", "(Smith, 1900)", bidae);
    // a synonym of a name in the subtree is in scope too, though it has no parent
    long synonym = createUsage(pid, "Dus jus", "(Smith, 1900)", "species", "synonym", null);
    link(pid, synonym, inside);
    long outside4 = species(pid, "Bus jus", "Smith, 1900", bidae);

    // each pair holds an exact duplicate, so each would be listed in a project scan
    relate(pid, outside1, inside, "basionym");
    relate(pid, outside1, inside, "basionym");
    relate(pid, outside3, outside2, "basionym");
    relate(pid, outside3, outside2, "basionym");
    relate(pid, synonym, outside4, "basionym");
    relate(pid, synonym, outside4, "basionym");

    JsonNode scoped = scan(pid, aidae);
    assertThat(scoped.size()).isEqualTo(2);
    assertThat(hasPair(scoped, inside, outside1)).isTrue();
    assertThat(hasPair(scoped, synonym, outside4)).isTrue();
    assertThat(hasPair(scoped, outside2, outside3)).isFalse();

    JsonNode all = scan(pid, null);
    assertThat(all.size()).isEqualTo(3);
    assertThat(hasPair(all, outside2, outside3)).isTrue();

    mvc.perform(get("/api/projects/" + pid + "/name-relations/conflicts?rootId=999999").with(user(OWNER)))
        .andExpect(status().isNotFound());
  }

  @Test
  void danglingAndSelfRelationsSkipped() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long a = species(pid, "Aus kus", "Smith, 1900", fam);
    long b = species(pid, "Bus kus", "(Smith, 1900)", fam);
    // without a related usage, twice
    relate(pid, a, null, "basionym");
    relate(pid, a, null, "basionym");
    // to itself, twice
    relate(pid, a, a, "basionym");
    relate(pid, a, a, "basionym");
    // an unknown type next to a basionym, the unknown one twice
    relate(pid, b, a, "basionym");
    relate(pid, b, a, "foo");
    relate(pid, b, a, "foo");
    // to a usage that does not exist (no FK on related_usage_id), twice
    for (int i = 0; i < 2; i++) {
      jdbc.update("INSERT INTO name_relation (project_id, id, usage_id, related_usage_id, type) "
              + "VALUES (?, (SELECT coalesce(max(id),0)+1000 FROM name_relation WHERE project_id = ?), ?, ?, 'basionym')",
          (int) pid, (int) pid, (int) a, 999999);
    }

    assertThat(scan(pid, null).size()).isZero();
    assertThat(scan(pid, fam).size()).isZero();
  }

  @Test
  void rawTypeSpellingsJudged() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long bus = species(pid, "Aus lus", "Smith, 1900", fam);
    long buss = species(pid, "Aus luss", "Smith, 1900", fam);
    long basionym = relate(pid, buss, bus, "BASIONYM");
    long spelling = relate(pid, buss, bus, "spelling-correction");

    JsonNode pairs = scan(pid, null);
    assertThat(pairs.size()).isEqualTo(1);
    JsonNode p = pair(pairs, bus, buss);
    assertThat(relation(p, basionym).get("remove").asBoolean()).isTrue();
    assertThat(relation(p, basionym).get("preselected").asBoolean()).isTrue();
    assertThat(relation(p, basionym).get("type").asString()).isEqualTo("BASIONYM");
    assertThat(relation(p, spelling).get("remove").asBoolean()).isFalse();
    assertThat(relation(p, spelling).get("preselected").asBoolean()).isFalse();
  }

  @Test
  void unparsedNamesJudgedWithoutError() throws Exception {
    long pid = createProject();
    long a = createUsage(pid, "Xus yus", "", "unranked", "accepted", null);
    long b = createUsage(pid, "Xus zus", "", "unranked", "accepted", null);
    // strip every atomized part, as an unparsed import leaves them
    jdbc.update("UPDATE name_usage SET authorship = NULL, uninomial = NULL, genus = NULL, "
        + "infrageneric_epithet = NULL, specific_epithet = NULL, infraspecific_epithet = NULL, "
        + "combination_authorship = NULL, combination_authorship_year = NULL, basionym_authorship = NULL, "
        + "basionym_authorship_year = NULL, name_type = NULL, rank = 'unranked' WHERE project_id = ? AND id IN (?, ?)",
        (int) pid, (int) a, (int) b);
    long homotypic = relate(pid, a, b, "homotypic");
    relate(pid, a, b, "basionym");

    JsonNode pairs = scan(pid, null);
    assertThat(pairs.size()).isEqualTo(1);
    // the homotypic relation is redundant next to the basionym
    JsonNode h = relation(pair(pairs, a, b), homotypic);
    assertThat(h.get("remove").asBoolean()).isTrue();
    assertThat(issues(h)).containsExactly("NAME_RELATION_REDUNDANT");
  }

  @Test
  void viewerCanScan() throws Exception {
    long pid = createProject();
    addMember(pid, "relViewer", Role.VIEWER);
    ensureUser("relStranger");
    long fam = family(pid, "Aidae");
    long a = species(pid, "Aus mus", "Smith, 1900", fam);
    long b = species(pid, "Bus mus", "(Smith, 1900)", fam);
    relate(pid, b, a, "basionym");
    relate(pid, b, a, "basionym");

    assertThat(scanAs(pid, null, "relViewer").size()).isEqualTo(1);
    assertThat(scanAs(pid, fam, "relViewer").size()).isEqualTo(1);
    mvc.perform(get("/api/projects/" + pid + "/name-relations/conflicts").with(user("relStranger")))
        .andExpect(status().isNotFound());
  }

  @Test
  void wholeProjectScanBulkLoads() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    List<long[]> pairs = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      String epithet = "x" + (char) ('a' + i / 26) + (char) ('a' + i % 26) + "us";
      long original = species(pid, "Aus " + epithet, "Smith, 1900", fam);
      long combination = species(pid, "Bus " + epithet, "(Smith, 1900)", fam);
      relate(pid, combination, original, "basionym");
      relate(pid, combination, original, "basionym");
      pairs.add(new long[] {original, combination});
    }
    awaitValidation();
    clearInvocations(usageMapper, relationMapper);

    JsonNode result = scan(pid, null);

    assertThat(result.size()).isEqualTo(50);
    for (long[] p : pairs) {
      assertThat(hasPair(result, p[0], p[1])).isTrue();
    }
    verify(usageMapper, times(1)).findAllByProject((int) pid);
    verify(usageMapper, never()).findByIdInProject(eq((int) pid), anyInt());
    verify(relationMapper, times(1)).findByProject((int) pid);
    verify(relationMapper, never()).findByUsage(eq((int) pid), anyInt());
    verify(relationMapper, never()).findByRelated(eq((int) pid), anyInt());
  }

  // Part 1 (the homotypic grouping) never creates what Part 2 (this cleanup) flags: everything the
  // detector proposes is applied the way the grouping dialog does by default, and the scan of the
  // subtree then lists no pair. The seed has a stored basionym the detector must reuse and a stored
  // later homonym between two names of one authorship group, which it must not relate homotypically.
  @Test
  void appliedHomotypyProposalsAreNeverFlagged() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Felidae");
    long leo = species(pid, "Panthera leo", "(Linnaeus, 1758)", fam);
    long felisLeo = synonym(pid, "Felis leo", "Linnaeus, 1758", leo);
    long leoLeo = synonym(pid, "Leo leo", "(Linnaeus, 1758)", leo);
    long leoNobilis = synonym(pid, "Leo nobilis", "(Smith, 1900)", leo);
    long felisNobilis = synonym(pid, "Felis nobilis", "(Smith, 1900)", leo);
    synonym(pid, "Uncia nobilis", "(Smith, 1900)", leo);
    synonym(pid, "Felis nubica", "Blainville, 1843", leo);
    relate(pid, leoLeo, felisLeo, "basionym");
    relate(pid, felisNobilis, leoNobilis, "later homonym");
    long stored = relationCount(pid);

    String detect = mvc.perform(get("/api/projects/" + pid + "/usages/" + leo + "/homotypic/detect")
            .with(user(OWNER)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    var proposed = json.createArrayNode();
    for (JsonNode g : json.readTree(detect).get("groups")) {
      for (JsonNode r : g.get("relations")) {
        if (!r.get("alreadyExists").asBoolean()) {
          proposed.addObject().put("usageId", r.get("usageId").asLong())
              .put("relatedUsageId", r.get("relatedUsageId").asLong()).put("type", r.get("type").asString());
        }
      }
    }
    var body = json.createObjectNode();
    body.set("relations", proposed);
    mvc.perform(post("/api/projects/" + pid + "/usages/" + leo + "/homotypic/apply").with(csrf())
            .with(user(OWNER)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    // the basionym of the accepted name and a homotypic link among the "nobilis" names were created
    assertThat(proposed.size()).isGreaterThanOrEqualTo(2);
    assertThat(relationCount(pid)).isEqualTo(stored + proposed.size());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_relation WHERE project_id = ? AND usage_id = ?"
        + " AND related_usage_id = ? AND type = 'basionym'", Long.class, pid, leo, felisLeo)).isEqualTo(1);

    assertThat(list(scan(pid, leo))).isEmpty();
    assertThat(list(scan(pid, null))).isEmpty();
  }

  // ---- apply ----

  private String refs(long[]... idVersion) {
    var arr = json.createArrayNode();
    for (long[] r : idVersion) arr.addObject().put("id", r[0]).put("version", r[1]);
    var body = json.createObjectNode();
    body.set("relations", arr);
    return json.writeValueAsString(body);
  }

  private org.springframework.test.web.servlet.ResultActions cleanup(long pid, String body, String username)
      throws Exception {
    return mvc.perform(post("/api/projects/" + pid + "/name-relations/cleanup").with(csrf())
        .with(user(username)).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private long relationCount(long pid) {
    return jdbc.queryForObject("SELECT count(*) FROM name_relation WHERE project_id = ?", Long.class, pid);
  }

  private long deleteAudits(long pid) {
    return jdbc.queryForObject("SELECT count(*) FROM change WHERE project_id = ? AND entity_type = 'name_relation'"
        + " AND operation = 'DELETE'", Long.class, pid);
  }

  private long[] idVersion(JsonNode pairs, long id1, long id2, long relationId) {
    JsonNode r = relation(pair(pairs, id1, id2), relationId);
    return new long[] {relationId, r.get("version").asLong()};
  }

  // Two pairs, scanned first; the request is built from the scan result:
  //  pair A: a basionym given both ways between two original names (no basionym authorship, same
  //          year): nothing tells the direction, so both rows are flag-only (issue, remove=false,
  //          preselected=false); the editor ticks one of them anyway;
  //  pair B: basionym removed by the judge (referenced row kept, remove=true) with a bare twin
  //          (remove=true, preselected=true) that is deliberately NOT sent.
  @Test
  void applyDeletesExactlyTheSentRelations() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long fus = species(pid, "Aus fus", "Smith, 1900", fam);
    long gus = species(pid, "Bus gus", "Jones, 1900", fam);
    long forward = relate(pid, fus, gus, "basionym");
    long backward = relate(pid, gus, fus, "basionym");
    long bus = species(pid, "Aus bus", "Smith, 1900", fam);
    long buss = species(pid, "Aus buss", "Smith, 1900", fam);
    long bare = relate(pid, buss, bus, "basionym");
    long referenced = relate(pid, buss, bus, "basionym", 7, null, null);
    long spelling = relate(pid, buss, bus, "spelling correction");
    awaitValidation();

    JsonNode pairs = scan(pid, null);
    for (long id : new long[] {forward, backward}) {
      JsonNode flagged = relation(pair(pairs, fus, gus), id);
      assertThat(issues(flagged)).containsExactly("NAME_RELATION_CONFLICT");
      assertThat(flagged.get("remove").asBoolean()).isFalse();
      assertThat(flagged.get("preselected").asBoolean()).isFalse();
    }
    JsonNode removed = relation(pair(pairs, bus, buss), referenced);
    assertThat(removed.get("remove").asBoolean()).isTrue();
    JsonNode twin = relation(pair(pairs, bus, buss), bare);
    assertThat(twin.get("remove").asBoolean()).isTrue();
    assertThat(twin.get("preselected").asBoolean()).isTrue();

    cleanup(pid, refs(idVersion(pairs, fus, gus, forward), idVersion(pairs, bus, buss, referenced)), OWNER)
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.deleted").value(2));

    // the other flag-only row, the preselected twin and the spelling row were not sent: they survive
    List<Long> survivors = jdbc.queryForList("SELECT id FROM name_relation WHERE project_id = ? ORDER BY id",
        Long.class, pid);
    assertThat(survivors).containsExactlyInAnyOrder(backward, bare, spelling);
    assertThat(deleteAudits(pid)).isEqualTo(2);
    assertBeforeImage(pid, forward, fus, gus);
    assertBeforeImage(pid, referenced, buss, bus);
    awaitValidation();
  }

  // A cleanup revalidates the owning usages of all deleted relations in ONE bulk event, each usage
  // once, instead of one event per relation that could overflow the bounded validation queue. A 409
  // publishes nothing.
  @Test
  void applyPublishesOneBulkValidationEvent() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long a = species(pid, "Aus mus", "Smith, 1900", fam);
    long b = species(pid, "Bus mus", "(Smith, 1900)", fam);
    long r1 = relate(pid, b, a, "basionym");
    long r2 = relate(pid, b, a, "basionym");
    long r3 = relate(pid, a, b, "basionym");
    long r4 = relate(pid, a, b, "homotypic");
    awaitValidation();

    appEvents.clear();
    cleanup(pid, refs(new long[] {r1, 0}, new long[] {r2, 0}, new long[] {r3, 0}), OWNER)
        .andExpect(status().isOk());
    List<BulkValidationEvent> bulk = appEvents.stream(BulkValidationEvent.class).toList();
    assertThat(bulk).hasSize(1);
    assertThat(bulk.get(0).projectId()).isEqualTo((int) pid);
    assertThat(bulk.get(0).usageIds()).containsExactlyInAnyOrder((int) b, (int) a);
    assertThat(appEvents.stream(ValidationEvent.class)).isEmpty();
    awaitValidation();

    // r1 is gone: the whole request fails, r4 survives and nothing is published
    appEvents.clear();
    cleanup(pid, refs(new long[] {r4, 0}, new long[] {r1, 0}), OWNER).andExpect(status().isConflict());
    assertThat(appEvents.stream(BulkValidationEvent.class)).isEmpty();
    assertThat(appEvents.stream(ValidationEvent.class)).isEmpty();
    assertThat(relationCount(pid)).isEqualTo(1);
  }

  private void assertBeforeImage(long pid, long relationId, long usageId, long relatedId) throws Exception {
    String diff = jdbc.queryForObject("SELECT diff::text FROM change WHERE project_id = ? AND entity_type ="
        + " 'name_relation' AND entity_id = ? AND operation = 'DELETE'", String.class, pid, relationId);
    JsonNode before = json.readTree(diff).get("before");
    assertThat(before.get("id").asLong()).isEqualTo(relationId);
    assertThat(before.get("usageId").asLong()).isEqualTo(usageId);
    assertThat(before.get("relatedUsageId").asLong()).isEqualTo(relatedId);
  }

  @Test
  void missingVersionIsRejected() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long a = species(pid, "Aus mus", "Smith, 1900", fam);
    long b = species(pid, "Bus mus", "(Smith, 1900)", fam);
    long r1 = relate(pid, b, a, "basionym");
    relate(pid, b, a, "basionym");

    cleanup(pid, "{\"relations\":[{\"id\":" + r1 + "}]}", OWNER).andExpect(status().isBadRequest());
    cleanup(pid, "{\"relations\":[{\"version\":0}]}", OWNER).andExpect(status().isBadRequest());
    assertThat(relationCount(pid)).isEqualTo(2);
    assertThat(deleteAudits(pid)).isZero();
  }

  @Test
  void staleVersionFailsWholeRequest() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long bus = species(pid, "Aus bus", "Smith, 1900", fam);
    long buss = species(pid, "Aus buss", "Smith, 1900", fam);
    long basionym = relate(pid, buss, bus, "basionym");
    long spelling = relate(pid, buss, bus, "spelling correction");
    JsonNode pairs = scan(pid, null);
    long[] a = idVersion(pairs, bus, buss, basionym);
    long[] b = idVersion(pairs, bus, buss, spelling);
    // an editor changes the second relation after the scan
    mvc.perform(put("/api/projects/" + pid + "/usages/" + buss + "/relations/" + spelling).with(csrf())
            .with(user(OWNER)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"relatedUsageId\":" + bus + ",\"type\":\"spelling correction\",\"remarks\":\"x\",\"version\":" + b[1] + "}"))
        .andExpect(status().isOk());
    long auditsBefore = deleteAudits(pid);

    cleanup(pid, refs(a, b), OWNER).andExpect(status().isConflict());

    assertThat(relationCount(pid)).isEqualTo(2);
    assertThat(deleteAudits(pid)).isEqualTo(auditsBefore).isZero();
  }

  @Test
  void missingRelationFailsWholeRequest() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long bus = species(pid, "Aus bus", "Smith, 1900", fam);
    long buss = species(pid, "Aus buss", "Smith, 1900", fam);
    long basionym = relate(pid, buss, bus, "basionym");
    long spelling = relate(pid, buss, bus, "spelling correction");
    JsonNode pairs = scan(pid, null);
    long[] a = idVersion(pairs, bus, buss, basionym);
    long[] b = idVersion(pairs, bus, buss, spelling);
    appEvents.clear();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/api/projects/" + pid + "/usages/" + buss + "/relations/" + spelling)
            .with(csrf()).with(user(OWNER)))
        .andExpect(status().isNoContent());
    // the single delete still revalidates its usage on its own
    assertThat(appEvents.stream(ValidationEvent.class)).containsExactly(ValidationEvent.forUsage((int) pid, (int) buss));
    long auditsBefore = deleteAudits(pid);

    cleanup(pid, refs(a, b), OWNER).andExpect(status().isConflict());

    assertThat(relationCount(pid)).isEqualTo(1);
    assertThat(deleteAudits(pid)).isEqualTo(auditsBefore);
  }

  @Test
  void viewerCannotApply() throws Exception {
    long pid = createProject();
    addMember(pid, "relViewer", Role.VIEWER);
    long fam = family(pid, "Aidae");
    long a = species(pid, "Aus mus", "Smith, 1900", fam);
    long b = species(pid, "Bus mus", "(Smith, 1900)", fam);
    long r1 = relate(pid, b, a, "basionym");
    relate(pid, b, a, "basionym");
    long[] ref = idVersion(scan(pid, null), a, b, r1);

    cleanup(pid, refs(ref), "relViewer").andExpect(status().isForbidden());
    assertThat(relationCount(pid)).isEqualTo(2);
  }

  @Test
  void emptyRequestIs400AndDuplicateIdsDeleteOnce() throws Exception {
    long pid = createProject();
    long fam = family(pid, "Aidae");
    long a = species(pid, "Aus mus", "Smith, 1900", fam);
    long b = species(pid, "Bus mus", "(Smith, 1900)", fam);
    long r1 = relate(pid, b, a, "basionym");
    relate(pid, b, a, "basionym");
    long[] ref = idVersion(scan(pid, null), a, b, r1);

    cleanup(pid, "{\"relations\":[]}", OWNER).andExpect(status().isBadRequest());
    cleanup(pid, "{}", OWNER).andExpect(status().isBadRequest());
    cleanup(pid, refs(new long[] {ref[0], ref[1]}, new long[] {ref[0], ref[1] + 1}), OWNER)
        .andExpect(status().isBadRequest());
    assertThat(relationCount(pid)).isEqualTo(2);

    cleanup(pid, refs(ref, ref), OWNER)
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.deleted").value(1));
    assertThat(relationCount(pid)).isEqualTo(1);
    assertThat(deleteAudits(pid)).isEqualTo(1);
  }
}
