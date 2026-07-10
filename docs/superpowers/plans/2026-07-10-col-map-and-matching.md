# COL Map + Single-Taxon Matching — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a distribution-tab map (curated polygons + type-specimen points over the focal taxon and its subtree, focal/children on separate layers) with an optional project-gated GBIF occurrence layer, plus COL name matching that stores `col:<id>` on the name usage.

**Architecture:** New backend proxies/services beside the existing child-entity + `CrossrefClient` patterns (audit + optimistic locking + validation events). Frontend adds a lazy-loaded MapLibre panel and a match modal into the existing `DistributionTab`.

**Tech Stack:** Spring Boot 4.1 / Java 25 / Postgres 17 / MyBatis / Flyway; React 18 / Mantine 7 / @tanstack/react-query / MapLibre GL; Vitest + MSW.

## Global Constraints

- Build with **JDK 25**: `cd backend && JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca ./mvnw ...` (default java 21 won't compile).
- Commit directly to `main` (no branches). One commit per task after its tests pass.
- Child-entity request records use boxed `Integer version` (never primitive) — Jackson maps a missing `version` to null.
- MyBatis mappers map snake_case→camelCase by constructor (records) with `-parameters`.
- Wire-format: usage `status` UPPERCASE, `rank` lowercase; project `nomCode` lowercase.
- `RestClient.builder()` (static) — the `RestClient.Builder` bean is NOT present in this app (injecting it breaks the context).
- Config properties live in `backend/src/main/resources/application.yaml` (create keys under `coldp.*`); bind via `@Value` or a `@ConfigurationProperties` record.
- Templates to mirror (already committed): `backend/.../child/TypeMaterialMapper.java`, `TypeMaterialService.java`, `TypeMaterialController.java`, `dto/TypeMaterial{Request,Response}.java`; `backend/.../name/CrossrefClient.java`; `frontend/src/child/TypeMaterialTab.tsx`, `frontend/src/child/ChildEntityTab.tsx`, `frontend/src/child/taxonTabs.tsx`, `frontend/src/api/childApi.ts`.

---

### Task 1: TypeMaterial latitude/longitude (V11 migration + entity end-to-end)

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__map_and_col.sql`
- Modify: `backend/.../child/dto/TypeMaterialRequest.java`, `TypeMaterialResponse.java`
- Modify: `backend/.../child/TypeMaterialMapper.java`
- Modify: `frontend/src/child/TypeMaterialTab.tsx`
- Test: `backend/src/test/.../child/TypeMaterialIT.java`

**Interfaces:**
- Produces: `type_material.latitude DOUBLE PRECISION`, `type_material.longitude DOUBLE PRECISION`; `project.gbif_occurrence_layer BOOLEAN NOT NULL DEFAULT true` (the latter consumed by Task 5). `TypeMaterialRequest`/`Response` gain `Double latitude, Double longitude` (appended before `Integer version` in Response, before `version` in Request).

- [ ] **Step 1: Write V11 migration**

`V11__map_and_col.sql`:
```sql
ALTER TABLE type_material ADD COLUMN latitude  DOUBLE PRECISION;
ALTER TABLE type_material ADD COLUMN longitude DOUBLE PRECISION;

ALTER TABLE project ADD COLUMN gbif_occurrence_layer BOOLEAN NOT NULL DEFAULT true;
```

- [ ] **Step 2: Add fields to the DTOs**

In `TypeMaterialRequest` add `Double latitude, Double longitude` (immediately before `Integer version`). In `TypeMaterialResponse` add `Double latitude, Double longitude` immediately before `Integer version`. Keep constructor order = SELECT column order.

- [ ] **Step 3: Extend the mapper SQL**

In `TypeMaterialMapper.SELECT` append `, latitude, longitude` after `link, remarks` — wait: current order ends `... reference_id, link, remarks, version`. Insert `latitude, longitude` right before `version` in SELECT, INSERT column list, and INSERT VALUES (`#{r.latitude}, #{r.longitude}`), and add to UPDATE `set` (`latitude = #{r.latitude}, longitude = #{r.longitude},`). The Response constructor order must match SELECT: `..., reference_id, link, remarks, latitude, longitude, version`. Update the DTO field order to match (latitude/longitude AFTER remarks, before version).

- [ ] **Step 4: Add the two number fields to the Types tab**

In `TypeMaterialTab.tsx` `fields`, add after `link`:
```tsx
{ name: 'latitude', label: 'Latitude', type: 'number', span: 3 },
{ name: 'longitude', label: 'Longitude', type: 'number', span: 3 },
```
and in `toForm` add `latitude: r.latitude == null ? '' : String(r.latitude), longitude: r.longitude == null ? '' : String(r.longitude),`. `TypeMaterial` interface (in `api/typeMaterial.ts`) gains `latitude: number | null; longitude: number | null;`.

- [ ] **Step 5: Extend the IT to round-trip lat/lon**

In `TypeMaterialIT.crudTypeMaterial`, add `\"latitude\":-0.5,\"longitude\":36.8` to the create body and assert `jsonPath("$.latitude").value(-0.5)` and `$.longitude` = 36.8.

- [ ] **Step 6: Run + commit**

Run: `JAVA_HOME=~/.sdkman/candidates/java/25.0.1-librca ./mvnw -Dtest=TypeMaterialIT test` → PASS.
Run (frontend): `cd frontend && npx vitest run src/tree/TaxonDetail.test.tsx && npm run build` → PASS.
Commit: `feat(type-material): latitude/longitude (V11) + Types-tab fields`.

---

### Task 2: Name-usage identifiers — expose + optimistic-locked write

**Files:**
- Modify: `backend/.../name/dto/NameUsageResponse.java`
- Modify: `backend/.../name/NameUsageService.java` (toResponse; add setIdentifiers method)
- Modify: `backend/.../name/NameUsageMapper.java` (add `updateAlternativeId` CAS)
- Create: `backend/.../name/dto/IdentifiersRequest.java`
- Modify: `backend/.../name/NameUsageController.java` (add PUT identifiers)
- Modify: `frontend/src/api/types.ts` (NameUsage.alternativeId), `frontend/src/api/usages.ts` (updateIdentifiers)
- Test: `backend/src/test/.../name/NameUsageApiIT.java` (or new `UsageIdentifiersIT.java`)

**Interfaces:**
- Produces:
  - `NameUsageResponse.alternativeId : List<String>` (populated in `toResponse` from `u.getAlternativeId()`).
  - `record IdentifiersRequest(List<String> alternativeId, Integer version)`.
  - `PUT /api/projects/{pid}/usages/{uid}/identifiers` → 200 `NameUsageResponse`, 409 on stale version.
  - `NameUsageMapper.updateAlternativeId(projectId, id, alternativeId(StringArrayTypeHandler), modifiedBy, version) : int` (rows updated; 0 ⇒ stale).
  - `NameUsageService.setIdentifiers(userId, pid, id, req)` and a static helper `mergeColId(List<String> ids, String colId)` (replaces any `col:` entry, preserves others; used again in the bulk-match plan).
- Consumed by: Task 4/8 (match write), the bulk-match plan.

- [ ] **Step 1: Write the failing IT**

`UsageIdentifiersIT` (mirror `TypeMaterialIT` scaffolding — createProject/createUsage): create a usage, `PUT …/identifiers` with `{"alternativeId":["col:6W3C4","tsn:1"],"version":0}` → 200, assert `$.alternativeId` contains `col:6W3C4`. Then GET the usage → alternativeId present. Then PUT again with a merge that replaces col: (`mergeColId` used by the service? — no, the endpoint takes the full array; test replacement by sending `["col:XYZ","tsn:1"]` with the new version) → assert col:XYZ. Stale version (send version 0 again) → 409.

- [ ] **Step 2: Run it — FAIL** (`-Dtest=UsageIdentifiersIT`, compile error / 404).

- [ ] **Step 3: Add `alternativeId` to the response + toResponse**

Add `List<String> alternativeId` to `NameUsageResponse` (near the id fields). In `NameUsageService.toResponse`, pass `u.getAlternativeId()`.

- [ ] **Step 4: Add the mapper CAS update**

```java
@Update("""
    UPDATE name_usage SET alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
        modified_by = #{modifiedBy}, version = version + 1
    WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
    """)
int updateAlternativeId(@Param("projectId") int projectId, @Param("id") int id,
    @Param("alternativeId") java.util.List<String> alternativeId,
    @Param("modifiedBy") int modifiedBy, @Param("version") int version);
```
(Confirm the existing `modified` column is bumped elsewhere or add `modified = now()` to match sibling updates.)

- [ ] **Step 5: Service method + controller**

`IdentifiersRequest(List<String> alternativeId, Integer version)`. In `NameUsageService`:
```java
@Transactional
public NameUsageResponse setIdentifiers(int userId, int projectId, int id, IdentifiersRequest req) {
  requireEditor(userId, projectId);
  Project project = requireProject(projectId);
  NameUsage before = requireInProject(projectId, id);
  var ids = req.alternativeId() == null ? java.util.List.<String>of() : req.alternativeId();
  if (usages.updateAlternativeId(projectId, id, ids, userId, req.version()) == 0) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
  }
  NameUsage after = requireInProject(projectId, id);
  audit.record(projectId, userId, ENTITY, id, Operation.UPDATE, before, after);
  events.publishEvent(ValidationEvent.forUsage(projectId, id));
  return toResponse(after, project);
}
public static java.util.List<String> mergeColId(java.util.List<String> ids, String colId) {
  var out = new java.util.ArrayList<String>();
  if (ids != null) ids.stream().filter(s -> !s.toLowerCase().startsWith("col:")).forEach(out::add);
  if (colId != null && !colId.isBlank()) out.add("col:" + colId);
  return out;
}
```
Controller: `@PutMapping("/{id}/identifiers")` → `service.setIdentifiers(currentUser…, pid, id, req)`.

- [ ] **Step 6: Frontend type + api**

`types.ts`: `NameUsage.alternativeId?: string[]`. `usages.ts`:
```ts
export function updateIdentifiers(pid: number, id: number, alternativeId: string[], version: number) {
  return api<NameUsage>(`/api/projects/${pid}/usages/${id}/identifiers`, { method: 'PUT', json: { alternativeId, version } });
}
```

- [ ] **Step 7: Run + commit**

Run: `-Dtest=UsageIdentifiersIT` → PASS. `npm run build` → PASS.
Commit: `feat(usages): expose alternativeId + PUT /identifiers (optimistic-locked)`.

---

### Task 3: Ancestor classification query (recursive CTE)

**Files:**
- Modify: `backend/.../name/NameUsageMapper.java` (add `findClassification`)
- Create: `backend/.../name/dto/RankName.java` (`record RankName(String rank, String name)`)
- Test: `backend/src/test/.../name/NameUsageMapperIT.java`

**Interfaces:**
- Produces: `List<RankName> NameUsageMapper.findClassification(projectId, usageId)` — the usage's **ancestors** (excluding itself), ordered root→parent, each `{rank, name}`. Consumed by Task 4 and the bulk-match plan.

- [ ] **Step 1: Write the failing mapper IT**

In `NameUsageMapperIT`, build root(kingdom "Animalia")→(family "Felidae")→(genus "Panthera")→(species "Panthera leo"); assert `findClassification(pid, leoId)` returns `[{kingdom,Animalia},{family,Felidae},{genus,Panthera}]` (self excluded, root first).

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Add the recursive CTE**

```java
@Select("""
    WITH RECURSIVE anc AS (
      SELECT parent_id, rank, scientific_name, 0 AS depth
        FROM name_usage WHERE project_id = #{projectId} AND id = #{usageId}
      UNION ALL
      SELECT p.parent_id, p.rank, p.scientific_name, anc.depth + 1
        FROM name_usage p JOIN anc ON p.project_id = #{projectId} AND p.id = anc.parent_id
    )
    SELECT rank, scientific_name AS name FROM anc
    WHERE depth > 0 AND rank IS NOT NULL
    ORDER BY depth DESC
    """)
List<RankName> findClassification(@Param("projectId") int projectId, @Param("usageId") int usageId);
```
(`depth > 0` drops the focal node; `ORDER BY depth DESC` = root→parent. Uses `scientific_name`; higher taxa are uninomials so this is the taxon name.)

- [ ] **Step 4: Run — PASS. Commit** `feat(usages): findClassification ancestor CTE`.

---

### Task 4: COL match proxy + single-usage match endpoint

**Files:**
- Create: `backend/.../name/ClbMatchClient.java`
- Create: `backend/.../name/ColMatchService.java`, `ColMatchController.java`
- Create: `backend/.../name/dto/ColMatchCandidate.java`
- Modify: `backend/src/main/resources/application.yaml` (coldp.clb.*, coldp.col.*)
- Modify: `frontend/src/api/usages.ts` (colMatch)
- Test: `backend/src/test/.../name/ColMatchIT.java`

**Interfaces:**
- Produces:
  - `record ColMatchCandidate(String colId, String name, String authorship, String rank, String status, String matchType, String classification)`.
  - `ClbMatchClient.match(String sciName, String authorship, String rank, String code, List<RankName> classification) : JsonNode` (the raw CLB match response `message`/root).
  - `GET /api/projects/{pid}/usages/{uid}/col-match` → `List<ColMatchCandidate>` (best match first, then alternatives). Empty when `type=NONE`.
  - Config: `coldp.clb.base-url`, `coldp.col.match-dataset`, `coldp.col.gbif-checklist-key`.
- Consumed by: Task 8 (match modal), bulk-match plan.

- [ ] **Step 1: Add config keys**

`application.yaml`:
```yaml
coldp:
  clb:
    base-url: https://api.checklistbank.org
  col:
    match-dataset: 3LXR
    gbif-checklist-key: 7ddf754f-d193-4cc9-b351-99906754a03b
```

- [ ] **Step 2: Write the failing IT**

`ColMatchIT` — `@MockitoBean ClbMatchClient clb;` (mirror how ReferenceImportIT mocks CrossrefClient). Stub `clb.match(...)` to return a parsed JSON with a best `usage{id:"6W3C4",name:"Panthera leo",authorship:"Linnaeus, 1758",rank:"species",status:"accepted"}`, `type:"EXACT"`, and one `alternatives[]` entry. Create a project+usage, `GET …/col-match` → assert `$[0].colId=6W3C4`, `$[0].matchType=EXACT`, and length ≥ 2 (best + alternative). Then stub `type:"NONE"`, `usage:null` → assert `$.length()=0`.

- [ ] **Step 3: Run — FAIL.**

- [ ] **Step 4: ClbMatchClient (mirror CrossrefClient)**

Constructor injects `ObjectMapper` + `@Value` base-url + match-dataset; builds `RestClient.builder().baseUrl(baseUrl)`. `match(...)`:
```java
public JsonNode match(String sciName, String authorship, String rank, String code, List<RankName> classification) {
  var uri = UriComponentsBuilder.fromPath("/dataset/{ds}/match/nameusage")
      .queryParam("scientificName", sciName)
      .queryParam("verbose", true);
  if (authorship != null) uri.queryParam("authorship", authorship);
  if (rank != null) uri.queryParam("rank", rank);
  if (code != null) uri.queryParam("code", code);
  for (var rn : classification) {
    String param = CLASSIFICATION_PARAM.get(rn.rank());   // null ⇒ skip (unsupported rank / species)
    if (param != null && rn.name() != null) uri.queryParam(param, rn.name());
  }
  try {
    String body = http.get().uri(uri.buildAndExpand(matchDataset).toUriString()).retrieve().body(String.class);
    return objectMapper.readTree(body);
  } catch (RestClientResponseException e) {
    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "COL matching failed");
  } catch (RestClientException e) {
    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "COL matching unavailable");
  }
}
```
`CLASSIFICATION_PARAM` = `Map.of(...)` from our lowercase rank → CLB param, covering: superkingdom, kingdom, subkingdom, superphylum, phylum, subphylum, superclass, class, subclass, superorder, order, suborder, superfamily, family, subfamily, tribe, subtribe, genus, subgenus, and `section_botany`→`section`, `section_zoology`→`section`. **No `species` entry** (omitted per spec). Ranks absent from the map are skipped.

- [ ] **Step 5: ColMatchService + controller**

```java
public List<ColMatchCandidate> match(int userId, int projectId, int usageId) {
  projects.requireRole(userId, projectId);
  NameUsage u = usages.findByIdInProject(projectId, usageId);
  if (u == null) throw notFound;
  Project p = projects.requireProject? // reuse existing project lookup
  String code = p.getNomCode() == null ? null : p.getNomCode().toUpperCase(Locale.ROOT);
  var cls = usages.findClassification(projectId, usageId);
  JsonNode root = clb.match(u.getScientificName(), u.getAuthorship(), lower(u.getRank()), code, cls);
  var out = new ArrayList<ColMatchCandidate>();
  addCandidate(out, root.path("usage"), root.path("type").asText(null));
  for (JsonNode alt : root.path("alternatives")) addCandidate(out, alt, "ALTERNATIVE");
  return out; // empty if usage node missing
}
```
`addCandidate` skips null/missing nodes; `colId = usage.id`, classification = join of `usage.classification[].name` with " > " (for homonym disambiguation display). Controller: `GET …/{uid}/col-match`.

- [ ] **Step 6: Frontend api**

`usages.ts`:
```ts
export interface ColMatchCandidate { colId: string; name: string; authorship: string | null; rank: string | null; status: string | null; matchType: string; classification: string | null; }
export function colMatch(pid: number, usageId: number) {
  return api<ColMatchCandidate[]>(`/api/projects/${pid}/usages/${usageId}/col-match`);
}
```

- [ ] **Step 7: Run + commit**

Run: `-Dtest=ColMatchIT` → PASS. `npm run build` → PASS.
Commit: `feat(col): match proxy (3LXR, verbose, full classification) + /col-match`.

---

### Task 5: Project GBIF-layer setting

**Files:**
- Modify: `backend/.../project/dto/ProjectResponse.java`, `UpdateProjectMetadataRequest.java`
- Modify: `backend/.../project/ProjectMapper.java` (insert/update/select include gbif_occurrence_layer)
- Modify: `backend/.../project/ProjectService.java` (carry-over on update; default handled by DB)
- Modify: `frontend/src/api/projects.ts` (Project type + update payload), the Project settings page
- Test: `backend/src/test/.../project/ProjectApiIT.java`

**Interfaces:**
- Produces: `ProjectResponse.gbifOccurrenceLayer : boolean`; `UpdateProjectMetadataRequest.gbifOccurrenceLayer : Boolean`. Consumed by Task 7 (panel reads it).

- [ ] **Step 1: Write/extend the failing IT** — create a project → assert `$.gbifOccurrenceLayer=true` (DB default). Update with `gbifOccurrenceLayer:false` → GET → false.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Backend wiring** — add the column to `ProjectMapper` SELECT + INSERT (omit ⇒ DB default true) + UPDATE (`gbif_occurrence_layer = #{gbifOccurrenceLayer}`); add field to `ProjectResponse` (map from row) and `UpdateProjectMetadataRequest`; in `ProjectService.update`, carry the value through (default to existing when the request field is null to avoid nulling it).
- [ ] **Step 4: Frontend** — `projects.ts` `Project.gbifOccurrenceLayer: boolean` + include in the metadata update payload; add a Mantine `Switch` "Show GBIF occurrence layer on maps" to the Project page form.
- [ ] **Step 5: Run + commit** — `-Dtest=ProjectApiIT` PASS; `npm run build` PASS. Commit `feat(project): gbifOccurrenceLayer setting (default on)`.

---

### Task 6: Subtree map-data endpoint

**Files:**
- Create: `backend/.../child/MapDataController.java`, `MapDataService.java`, `MapDataMapper.java`
- Create: `backend/.../child/dto/MapData.java`, `MapAreaRecord.java`, `MapPointRecord.java`
- Modify: `frontend/src/api/map.ts` (new)
- Test: `backend/src/test/.../child/MapDataIT.java`

**Interfaces:**
- Produces: `GET /api/projects/{pid}/usages/{uid}/map` →
  `MapData(String colId, List<MapAreaRecord> distributions, List<MapPointRecord> typeSpecimens)`
  - `MapAreaRecord(Integer usageId, String name, boolean focal, String gazetteer, String areaId, String area)`
  - `MapPointRecord(Integer usageId, String name, boolean focal, String status, Double latitude, Double longitude, String locality)`
  - `focal = (usageId == uid)`. `colId` parsed from the focal usage's `alternative_id` (`col:` entry, bare id) else null.
- Consumed by: Task 7.

- [ ] **Step 1: Write the failing IT** — genus "Panthera" (accepted) with 2 accepted species; add a distribution (`tdwg:AB`) + a type material with lat/lon to one species, and a distribution on the genus. Set the genus alternativeId to `["col:PANTH"]` (via Task 2 PUT). `GET …/{genusId}/map` → assert `colId=PANTH`; distributions include focal (genus) + descendant (species) with correct `focal` flags; typeSpecimens has exactly the one lat/lon point (others excluded); a type material WITHOUT lat/lon is not returned.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Mapper subtree CTEs**

```java
@Select("""
    WITH RECURSIVE sub AS (
      SELECT id FROM name_usage WHERE project_id=#{projectId} AND id=#{usageId}
      UNION ALL
      SELECT c.id FROM name_usage c JOIN sub ON c.project_id=#{projectId} AND c.parent_id=sub.id
    )
    SELECT d.usage_id, u.scientific_name AS name, (d.usage_id=#{usageId}) AS focal,
           d.gazetteer, d.area_id, d.area
    FROM distribution d JOIN sub ON d.usage_id=sub.id
    JOIN name_usage u ON u.project_id=#{projectId} AND u.id=d.usage_id
    WHERE d.project_id=#{projectId} ORDER BY focal DESC, d.usage_id
    """)
List<MapAreaRecord> findSubtreeDistributions(@Param("projectId") int p, @Param("usageId") int u);
```
Analogous `findSubtreeTypePoints` from `type_material` with `WHERE tm.project_id=#{projectId} AND latitude IS NOT NULL AND longitude IS NOT NULL`. (Quote `"date"` isn't needed here.)

- [ ] **Step 4: Service + controller** — service loads the focal usage, parses `colId` from `alternativeId` (helper `colIdFrom(List<String>)` → strip `col:` prefix, case-insensitive), calls the two mapper queries, returns `MapData`. Read access = `projects.requireRole`. Controller `GET …/{uid}/map`.
- [ ] **Step 5: Frontend api** — `api/map.ts` types + `getMapData(pid, usageId)`.
- [ ] **Step 6: Run + commit** — `-Dtest=MapDataIT` PASS; `npm run build` PASS. Commit `feat(map): subtree map-data endpoint (distributions + type points)`.

---

### Task 7: Frontend map panel (MapLibre, lazy) with layer control

**Files:**
- Add dep: `frontend/package.json` (`maplibre-gl`)
- Create: `frontend/src/child/map/mapUrls.ts` (pure helpers), `DistributionMapPanel.tsx`, `MapView.tsx` (the maplibre-touching component, lazy-loaded)
- Modify: `frontend/src/child/taxonTabs.tsx` (`DistributionTab` renders the panel above the table)
- Test: `frontend/src/child/map/mapUrls.test.ts`, `DistributionMapPanel.test.tsx`

**Interfaces:**
- Consumes: `getMapData` (Task 6), `Project.gbifOccurrenceLayer` (Task 5), the COL checklist UUID (frontend constant, commented to match backend `coldp.col.gbif-checklist-key`).
- Produces: pure helpers `colIdFrom(alternativeId: string[]): string | null`, `gbifTileUrl(colId: string, checklistKey: string): string`, `areaGeojsonUrl(gazetteer: string, areaId: string): string`; `<DistributionMapPanel pid usageId canEdit />`.

- [ ] **Step 1: Add the dep** — `cd frontend && npm i maplibre-gl` (+ `@types` not needed; ships its own types).

- [ ] **Step 2: Write helper unit tests + implement**

`mapUrls.test.ts`: `colIdFrom(['tsn:1','col:6W3C4'])==='6W3C4'`, `colIdFrom(['tsn:1'])===null` (case-insensitive `COL:`); `gbifTileUrl('6W3C4', UUID)` contains `checklistKey=<UUID>&taxonKey=6W3C4` and the density path; `areaGeojsonUrl('tdwg','AB')==='https://api.checklistbank.org/vocab/area/tdwg:AB'`. Implement `mapUrls.ts` with the exact templates from the spec (GBIF density `@1x.png?...&checklistKey=..&taxonKey=..`, base `https://api.gbif.org`).

- [ ] **Step 3: MapView (lazy, maplibre)** — `MapView.tsx` renders a `maplibre-gl` map (Positron style) into a ref; props `{ colId, checklistKey, distributions, typeSpecimens, layers, gbifEnabled }`. On mount: add Positron style; for each distribution with gazetteer+areaId, `fetch(areaGeojsonUrl(...))` → add a geojson fill+line source (focal vs children colour via the `focal` flag → two layer groups); add type points as a `circle` layer (two colours by focal). If `gbifEnabled && colId`, add the GBIF raster source (`gbifTileUrl`). Respect the `layers` visibility object. Guard all maplibre calls so a WebGL failure degrades gracefully. Export default (so it can be `React.lazy`-imported).

- [ ] **Step 4: DistributionMapPanel** — fetches `getMapData` + project; computes `colId` from map data; state `layers = { distFocal:true, distChildren:false, typeFocal:true, typeChildren:false, gbif: project.gbifOccurrenceLayer && !!colId }`. Renders a Mantine checkbox layer control + `<Suspense fallback=spinner><LazyMapView .../></Suspense>`. When `!colId`, render an inline `Alert` "Not matched to COL yet" with a **Match to COL** button (opens the modal from Task 8 — stub the handler here, wire in Task 8). List free-text-only (`area` without `areaId`) distributions as "not mappable" below the map.

- [ ] **Step 5: Wire into DistributionTab** — in `taxonTabs.tsx`, change `DistributionTab` to render `<Stack><DistributionMapPanel pid usageId canEdit/><ChildEntityTab .../></Stack>`.

- [ ] **Step 6: Component test** — `DistributionMapPanel.test.tsx`: `vi.mock('./MapView')` (or mock `maplibre-gl`) to a stub; MSW returns map data with a colId → assert the layer checkboxes render and children layers default unchecked; map data without colId → assert the "Match to COL" button shows. Add the `…/map` + project handlers to the shared MSW/`mockCommon` where needed.

- [ ] **Step 7: Run + commit** — `npx vitest run` (all) + `npm run build` PASS. Commit `feat(map): MapLibre distribution/type map panel in the Distribution tab`.

---

### Task 8: MatchColModal — confirm a candidate → store col id

**Files:**
- Create: `frontend/src/child/map/MatchColModal.tsx`
- Modify: `frontend/src/child/map/DistributionMapPanel.tsx` (open modal; on success invalidate)
- Modify: `frontend/src/tree/TaxonDetail.tsx` (read-only col id line in Details)
- Test: `frontend/src/child/map/MatchColModal.test.tsx`

**Interfaces:**
- Consumes: `colMatch` (Task 4), `updateIdentifiers` (Task 2), current usage `alternativeId` + `version`.
- Produces: `<MatchColModal pid usageId opened onClose />` — on confirm, PUTs merged identifiers and invalidates `['usage',pid,usageId]` + `['map',pid,usageId]`.

- [ ] **Step 1: Write the failing test** — MSW: `GET …/col-match` → two candidates; `PUT …/identifiers` captures the body. Render modal, pick candidate 1, click **Use this** → assert PUT body `alternativeId` contains `col:6W3C4` and the prior non-col ids are preserved (seed the usage with `['tsn:1']`); `NONE`/empty candidates → assert "No COL match found".
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement the modal** — `useQuery(colMatch)`; radio list rendering name · authorship · rank · status · matchType (+ classification dimmed); **Use this** → read the usage from cache (`['usage',pid,usageId]`) for its current `alternativeId`+`version`, compute `merged = [...ids.filter(not col:), 'col:'+colId]`, call `updateIdentifiers(pid, usageId, merged, version)`; on 409 show "changed — reopen"; on success invalidate and close.
- [ ] **Step 4: Wire the panel button** to open this modal; Details tab shows `col:<id>` (read-only) from `usage.alternativeId`.
- [ ] **Step 5: Run + commit** — `npx vitest run` + `npm run build` PASS; full `JAVA_HOME=… ./mvnw verify` PASS. Commit `feat(col): Match-to-COL modal writes col:<id> to the usage`.

---

## Self-review notes
- Spec coverage: phases 1–6 → Tasks 1–8 (identifiers split into expose/write = T2, classification CTE = T3, match = T4). Phase 7 (bulk) is the separate plan.
- Type consistency: `findClassification` (T3) returns `List<RankName>`, consumed by `ClbMatchClient.match` (T4). `mergeColId`/`colIdFrom` helpers are defined once (T2 backend, T7 frontend) and reused. `MapData.colId` (T6) and `colIdFrom` (T7) agree on the bare-id (no `col:` prefix) shape.
- Verify during T2: whether the existing name_usage UPDATE bumps `modified`; match that behaviour in `updateAlternativeId`.
- Verify during T5: `ProjectService.update` semantics for null carry-over (don't null the flag when the request omits it).
