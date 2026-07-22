# AI-assisted curation — design (v1: focal taxon)

**Date:** 2026-07-22
**Status:** awaiting review

## Problem

Curating a taxon means gathering scattered facts — vernacular names, distribution,
a short description, key literature — and typing them in by hand. An LLM can draft
that supplementary content from what it knows plus the taxon's existing data, and
the curator keeps final say over what lands.

## Decisions (from brainstorming, 2026-07-22)

1. **Output = structured suggestions.** Per-field accept/reject cards that route
   through the *existing* create paths (so nothing bypasses validation or the audit
   log). Not a free-form report.
2. **Multi-provider now.** Claude / OpenAI / Google / Mistral behind one provider
   interface, selectable per project. Claude ships first and most complete.
3. **Grounding = verify references, mark the rest.** Every suggested reference is
   verified against Crossref/DataCite before it is offered; non-verifiable facts
   appear as clearly-marked "AI-suggested, unverified" cards.
4. **Scope = focal taxon first.** Single-taxon assist end-to-end. "Children" and
   bulk "review" modes are later increments (out of scope here).

## What exists to build on

- **Spring Boot backend** with SSRF-guarded `@Component` HTTP clients (`RestClient`).
- **`CrossrefClient` / `DataciteClient`** already fetch + validate a reference by DOI;
  **`RefMapping` / `ReferenceImportService`** turn a structured/DOI reference into a
  stored `Reference`. The AI reference path reuses these — it does not re-implement
  citation handling.
- **Per-project secret storage** pattern (`discussion_api_token` table) and per-project
  JSONB settings (`favorite_clb_datasets`) — the model for AI provider keys + config.
- **`TaxonDetail`** hosts child-entity tabs (`taxonTabs.tsx`); the AI surface is a new
  tab there. Child-entity create endpoints (vernacular, distribution, reference, etc.)
  already exist and are what an accepted suggestion calls.

## Architecture

```
TaxonDetail ─ "AI assist" tab
   │  POST /api/projects/{pid}/usages/{id}/ai/suggest   (editor-only)
   ▼
AiSuggestionService
   ├─ gather taxon context (name, authorship, rank, classification,
   │   existing vernaculars / distributions / descriptions / refs)
   ├─ LlmProvider.suggest(context) ── structured JSON (schema-constrained)
   │        └─ AnthropicProvider | OpenAiProvider | GoogleProvider | MistralProvider
   ├─ verify references (CrossrefClient / DataciteClient) → keep only resolvable
   └─ return SuggestionSet (categorised, each card: value + provenance + verified flag)
        ▼
   curator accepts a card → existing create endpoint (RefMapping / vernacular / …)
```

### Provider abstraction

```java
interface LlmProvider {
  AiSuggestions suggest(AiTaxonContext context, String model);   // returns validated POJO
  Provider id();                                                 // ANTHROPIC | OPENAI | GOOGLE | MISTRAL
}
```

- Each provider is a `@Component` adapter that takes the same `AiTaxonContext`, builds
  a provider-agnostic prompt asking for JSON matching one shared schema, calls the
  provider, and returns the validated `AiSuggestions` POJO. `@MockitoBean`-able in ITs.
- **Anthropic adapter uses the official Anthropic Java SDK** (`com.anthropic:anthropic-java`),
  per Anthropic's own guidance — structured outputs, retries, and model-specific
  handling come from the SDK rather than hand-rolled `RestClient` calls. Default model
  **`claude-opus-4-8`** (overridable per project). Structured output via the SDK's
  schema-constrained `outputConfig` (json_schema) so the response validates to
  `AiSuggestions` with no ad-hoc parsing.
- OpenAI / Google / Mistral adapters wrap each provider's official Java SDK where one
  exists, falling back to the existing SSRF-guarded `RestClient` pattern otherwise.
  Each requests the same JSON schema. *(SDK-vs-RestClient per non-Claude provider is an
  implementation detail confirmed during build.)*

### Suggestion categories (v1)

Mapped to existing ColDP child entities, each independently accept/reject-able:

- **Synonyms** *(primary output)* — other scientific names that refer to the focal
  accepted taxon. Each card bundles: `scientificName` + `authorship`, an optional
  **nomenclatural status**, and an optional **nomenclatural reference** (the place the
  name was published). Accepting creates a **`SYNONYM`** usage under the focal accepted
  taxon via the existing create-name path (`useNameActions` / `CreateNameModal`'s
  endpoint), and — if a reference was supplied — attaches it to the new name through the
  `RefMapping` path. The name itself is an unverified AI claim (badged); its attached
  reference is verified against Crossref/DataCite like any other reference. Homotypic
  vs. heterotypic grouping stays the job of the existing homotypic-grouping tool, run
  afterwards — v1 just creates the synonym usages.
- **Vernacular names** (name + language) → vernacular create endpoint
- **Distribution** (area + gazetteer/`areaId` where resolvable) → distribution create
- **Description / remarks** (short taxon remarks / a description block) → taxon field / description create
- **Key references** (nomenclatural → the **name**; basionym's ref → the **basionym**,
  not the current combination) → `RefMapping` path
- **Etymology** (name field) → name etymology

The exact category set is tunable; this is the v1 target. Synonyms are the headline
output for an accepted taxon.

### Grounding

- **References:** the model returns candidate references (ideally as DOIs, else as a
  citation string). Each is resolved: DOI → Crossref/DataCite lookup; bare citation →
  Crossref query to a DOI. Only references that resolve to a real record are offered;
  unresolvable ones are dropped (logged, count surfaced — no silent truncation).
- **Other facts:** synonym names, distribution, description, vernaculars, and etymology
  cannot be auto-verified, so each card is badged **"AI-suggested — verify before
  accepting"** and carries its provider/model provenance. Accepting is an explicit
  curator action. (A synonym card's *attached reference* is still verified via the
  reference path above; only the name-is-a-synonym claim is unverified.)

### Data model & config resolution

- **Provider API keys live in backend config only** (per provider, e.g.
  `coldp.ai.anthropic.api-key` in `application.yml` / env / secrets manager) — never in
  the DB, never sent to or displayed by the frontend. Which providers are *available* is
  whatever the operator has configured keys for.
- **Installation-wide defaults** in the same backend config:
  `coldp.ai.default-provider` and `coldp.ai.default-model`. With just the keys + these
  defaults the feature works for **every project with zero per-project setup**.
- **Optional per-project override:** `project.ai_provider` and `project.ai_model` (both
  nullable) let a project pick a different (server-configured) provider/model. Null =
  inherit the installation default. **Effective config = project override ?? installation
  default.** This is the only AI config the frontend reads/writes, and it holds no
  secrets.
- **Usage recording (per project):** an `ai_usage` table records one row per suggest
  run — `project_id`, `usage_id` (focal taxon, nullable), `user_id`, `provider`,
  `model`, `input_tokens`, `output_tokens` (+ cache tokens where the provider reports
  them), `created`. Written server-side from each provider response's usage block, so
  the operator can see AI consumption per project (sum tokens by project / provider /
  month). Estimated cost is derivable from a per-model rate table later; v1 stores raw
  tokens. A per-project total can be surfaced read-only in project settings / the admin
  projects view; the recording is the v1 deliverable, a richer dashboard a later increment.
- Flyway migration: next free version after V27 (discussions) — adds the two nullable
  project columns + the `ai_usage` table; no credential table.
- **No suggestion-content persistence in v1.** A run returns suggestions to the client,
  which holds them and accepts each via existing endpoints. We record usage *metrics*
  (above) but not the suggestion *content*; persisting full runs (audit/resume) is a
  later increment.

### Security

- **Provider keys are backend-only.** They live in server config, never in the DB, and
  never traverse or render in the frontend — so there is no masked-key UI and no
  encryption-at-rest question. Reading a project's AI config returns only the selected
  provider + model, never a key.
- The provider call is a fixed-host external request (no SSRF surface like the
  user-supplied-URL clients), but keeps timeouts + graceful degradation.
- **Tradeoff:** the operator (Blixa host) provisions and pays for the provider
  account(s); projects don't bring their own billing. If per-project bring-your-own-key
  billing is wanted later, add an optional per-project key *override* on top of the
  server default — that override alone would reintroduce the storage/exposure questions,
  scoped to those projects.

### Cost / safety controls (v1)

- One run per explicit "Suggest" click — never automatic.
- Per-project model selection; sensible `max_tokens`; the operator's configured provider
  account pays (a per-project usage cap can be added later).
- Adaptive thinking on the Claude adapter for suggestion quality.

## Frontend

- New **"AI" tab** in `TaxonDetail` (shown when an AI provider is available — the
  installation default or a project override — and the user can edit): a **"Gather
  suggestions"** button → calls the
  suggest endpoint → renders cards grouped by category. Each card: value, provenance
  (provider/model), a **verified** badge (green, references) or **unverified** badge
  (amber, other facts), and Accept / Dismiss.
- Accepting routes to the relevant existing create path and invalidates the affected
  child-entity query so the tab refreshes.
- **Project settings** (`ProjectMetadataPage` → Settings, alongside favorite CLB
  datasets & the discussion token): an **optional override** of provider + model (from
  the providers the server has keys for), defaulting to "use installation default
  (`<model>`)". **No key entry** — keys are backend config.

## Testing

- **Provider adapters** — unit tests with the provider HTTP mocked (canned JSON):
  request shape, schema-validated parse, error/degradation to empty suggestions.
- **Verification pipeline** — a suggested DOI that resolves is kept; one that doesn't
  is dropped; a bare citation resolved to a DOI is kept. (Crossref/Datacite clients
  `@MockitoBean`'d.)
- **`AiSuggestionService`** — context assembly, reference routing (nomenclatural → name,
  basionym → basionym), unverified-badge marking.
- **ITs** — suggest endpoint with a `@MockitoBean` provider; editor-only access; accepting
  a **synonym** card creates a `SYNONYM` usage under the focal taxon (with its verified
  reference attached); accepting other cards round-trips through their create endpoints.
- **Usage recording** — a successful suggest run inserts one `ai_usage` row carrying the
  provider's reported input/output tokens, project, model, and user; a failed provider
  call records no token row.
- **Frontend** — AI tab renders grouped cards, verified/unverified badges, Accept calls
  the create endpoint; settings save the (masked) key.

## Out of scope for v1

- "Children" and bulk "review" modes.
- Persisting AI runs / suggestions (audit, resume).
- Web-search / tool-use grounding (the model answers from its own knowledge +
  the supplied taxon context; only references are externally verified).
- Streaming the suggestion response.
