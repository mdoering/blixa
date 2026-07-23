# Live verification checklist

Everything built while you were away (2026-07-22 → ~08-15) was verified as far as it
can be *without live external services or real API keys* — unit tests, Testcontainers
integration tests, frontend suites, tsc, prod builds all green. This list is the
**remainder**: things whose external call or key-gated flow was **mocked** in tests and
so need a human with a key to confirm end-to-end. Check them off when you're back.

Legend: ⚠️ = highest uncertainty (mapping guessed against docs, never hit live).

---

## 0. Config to set first (dev backend env / deploy repo)

Until these are set, the AI and BHL features are **unavailable by design** (their UI
affordances stay hidden — that itself is worth confirming).

- [ ] **AI:** `COLDP_AI_DEFAULT_PROVIDER` (`anthropic`|`openai`|`google`|`mistral`),
      the matching `AI_MODEL_<PROVIDER>` (e.g. `AI_MODEL_ANTHROPIC=claude-opus-4-8`,
      `AI_MODEL_OPENAI=gpt-5`, …), and that provider's key
      (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GOOGLE_API_KEY` / `MISTRAL_API_KEY`).
- [ ] **BHL:** `BHL_API_KEY` (free from biodiversitylibrary.org).

## 1. Migrations on the real dev DB

Applied cleanly under Testcontainers; the persistent dev Postgres migrates on deploy.

- [ ] Confirm Flyway applied **V2** (`ai_usage`) and **V3** (`reference.bhl_item_id`)
      on the dev deploy (startup logs / `journalctl -u col-blixa`) — no failed boot.

## 2. AI-assisted curation — provider adapters

**None of the four adapters is runtime-tested** (each makes a live call; the pipeline is
verified against a mocked provider, and JSON parsing is unit-tested in the abstract).
For each provider you configure, run **"gather suggestions"** (brain icon on a taxon):

- [ ] **Anthropic** (`claude-opus-4-8`) — official Java SDK call returns text; JSON parses
      into suggestions.
- [ ] **OpenAI** — `/v1/chat/completions` with `response_format: json_object`; content parses.
- [ ] **Mistral** — same OpenAI-compatible path against `api.mistral.ai`.
- [ ] ⚠️ **Google / Gemini** — `models/{model}:generateContent` with
      `responseMimeType: application/json`; response read from
      `candidates[0].content.parts[0].text` (verify that path is right).
- [ ] **Per-provider model** actually used when you switch `default-provider` (no
      "model not found" — i.e. it isn't sending an Anthropic model to OpenAI).
- [ ] **Reference verification**: a suggested real DOI shows a green *verified* ref; a
      hallucinated DOI is dropped (this reuses the proven Crossref/DataCite path).
- [ ] **Frontend end-to-end**: brain icon only when configured; modal leads with synonyms
      (each with its verified reference); **"Add synonym"** creates the SYNONYM usage and
      links it into the tree; an `ai_usage` row is recorded per run.
- [ ] **Quality eyeball**: are the suggestions actually good per provider? The prompt is
      shared (`AiPrompts`); tune if a provider underperforms.

## 3. BHL integration

**`BhlClient` makes live calls and is mocked in tests** — the BHL JSON field mapping is
per the v3 docs but never hit live.

- [ ] **Gating**: BHL affordances hidden without `BHL_API_KEY`, appear with it.
- [ ] **Piece 1 — reference → item** ("Find on BHL" on a reference): `PublicationSearch`
      results map correctly (title / authors / year / item id + the `/item/{id}` link);
      linking stores `bhl_item_id`; the reference then shows the linked item + Unlink.
- [ ] **Piece 2 — name → page** ("Find page on BHL" on a taxon whose nomenclatural
      reference has a linked item):
  - [ ] **All pages** (`GetItemMetadata`) list with correct page numbers; thumbnails load
        (`/pagethumb/{id}`).
  - [ ] ⚠️ **Suggested pages** (`GetNameMetadata`, name→pages index) — **the most
        uncertain mapping**. For a name that genuinely appears in the item, confirm the
        "Where '<name>' appears" section is populated. If it's empty when it shouldn't be,
        BHL's response nesting differs from the defensive mapping in
        `BhlClient.namePagesInItem` (it handles *item-with-Pages* and *flat-page*; BHL may
        nest *Titles → Items → Pages*) — adjust it.
  - [ ] Picking a page fills **`publishedInPageLink`** (a `/page/{id}` URL) +
        **`publishedInPage`**; saving the taxon persists them.
  - [ ] Confirm BHL's real page + thumbnail URL formats match what's derived
        (`/page/{id}`, `/pagethumb/{id}`).

## 4. Exports (IT-verified — quick browser sanity only)

- [ ] Names TSV download opens with the expected columns.
- [ ] References TSV download.
- [ ] Subtree TextTree download (round-trip re-import proven in test; nice to see in-app).

## 5. UI (fully tested — a glance)

- [ ] Collapsible left pane on Tree & Names remembers its collapsed state per page.

---

## What does NOT need live checking (covered by ITs against a real Postgres)

Search endpoints & pagination, `/ai/config` and `/bhl/config` availability + gating,
editor/member auth gates, `ai_usage` recording, the reference↔item link column and
set/clear, per-provider model resolution logic, the TSV writer and TextTree writer
(round-trip), and all frontend component behaviour — these are exercised by the test
suites; only the *external HTTP calls* above are unverified.
