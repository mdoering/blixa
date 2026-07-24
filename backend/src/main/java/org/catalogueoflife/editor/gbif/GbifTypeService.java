package org.catalogueoflife.editor.gbif;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.child.TypeMaterialMapper;
import org.catalogueoflife.editor.child.dto.TypeMaterialResponse;
import org.catalogueoflife.editor.name.ColMatchService;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// Resolves a usage to its COL taxon (ColMatchService), queries GBIF for that taxon's type specimens
// (GbifOccurrenceClient), and maps each occurrence onto a TypeMaterial-shaped candidate, flagging any
// whose occurrenceId is already imported on this usage. Read-only: it neither persists a col: id nor
// creates any TypeMaterial -- the frontend imports ticked candidates through the normal create path.
@Service
public class GbifTypeService {

  private final GbifOccurrenceClient gbif;
  private final ColMatchService colMatch;
  private final TypeMaterialMapper typeMaterial;
  private final NameUsageMapper usages;
  private final ProjectService projects;

  public GbifTypeService(GbifOccurrenceClient gbif, ColMatchService colMatch,
      TypeMaterialMapper typeMaterial, NameUsageMapper usages, ProjectService projects) {
    this.gbif = gbif;
    this.colMatch = colMatch;
    this.typeMaterial = typeMaterial;
    this.usages = usages;
    this.projects = projects;
  }

  public GbifTypesResponse findTypeSpecimens(int userId, int projectId, int usageId) {
    projects.requireVisible(userId, projectId); // any member may read
    NameUsage u = usages.findByIdInProject(projectId, usageId);
    if (u == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    String colId = colMatch.resolveColId(userId, projectId, usageId);
    if (colId == null) {
      return new GbifTypesResponse(u.getScientificName(), null, false, List.of());
    }
    JsonNode root = gbif.searchTypeSpecimens(colId);

    Set<String> existing = typeMaterial.findByUsage(projectId, usageId).stream()
        .map(TypeMaterialResponse::occurrenceId).filter(Objects::nonNull).collect(Collectors.toSet());

    List<GbifTypeCandidate> candidates = new ArrayList<>();
    for (JsonNode r : root.path("results")) {
      candidates.add(toCandidate(r, existing));
    }
    int count = root.path("count").asInt(candidates.size());
    boolean truncated = count > GbifOccurrenceClient.LIMIT;
    return new GbifTypesResponse(u.getScientificName(), colId, truncated, candidates);
  }

  private static GbifTypeCandidate toCandidate(JsonNode r, Set<String> existing) {
    String occId = text(r, "occurrenceID");
    Long key = r.path("key").isNumber() ? r.path("key").asLong() : null;
    if (occId == null && key != null) {
      occId = String.valueOf(key); // fall back to the GBIF occurrence key when there's no occurrenceID
    }
    String link = key == null ? null : "https://www.gbif.org/occurrence/" + key;
    return new GbifTypeCandidate(
        text(r, "scientificName"),
        typeStatus(r),
        text(r, "institutionCode"),
        text(r, "catalogNumber"),
        occId,
        text(r, "locality"),
        text(r, "country"),
        text(r, "recordedBy"),
        text(r, "eventDate"),
        text(r, "sex"),
        link,
        dbl(r, "decimalLatitude"),
        dbl(r, "decimalLongitude"),
        occId != null && existing.contains(occId));
  }

  // typeStatus can be a plain string or an array in GBIF's occurrence JSON -- take the first value.
  private static String typeStatus(JsonNode r) {
    JsonNode ts = r.path("typeStatus");
    if (ts.isArray()) {
      return ts.isEmpty() ? null : ts.get(0).asString(null);
    }
    return ts.isNull() || ts.isMissingNode() ? null : ts.asString(null);
  }

  private static String text(JsonNode r, String field) {
    JsonNode n = r.path(field);
    return n.isNull() || n.isMissingNode() ? null : n.asString(null);
  }

  private static Double dbl(JsonNode r, String field) {
    JsonNode n = r.path(field);
    return n.isNumber() ? n.asDouble() : null;
  }
}
