# Blixa — Backlog

Consolidated **2026-07-20** from `todo2.md`, `todo.md`, and `features.md`, audited
against the git history and the actual code. Only **open** items are listed here;
everything that the commit audit showed as already implemented was filtered out
(a short summary of what's done is at the bottom). This file supersedes the three
source files.

Rough priority: the **Big features** are the substantial, design-first pieces
(tackled one at a time, starting with the Issue tracker). The rest are smaller,
mostly self-contained follow-ups.

---

## Big features (new — each needs its own design pass)

### 1. Discussions  *(feature-complete — all 6 phases + identity polish + mention autocomplete shipped; see `docs/superpowers/specs/2026-07-20-discussions-design.md`)*
A forum-style, per-project discussion tracker keyed on **ORCIDs** (formerly "issue tracker" —
renamed to avoid collision with the validation *Issues*, and because the shape is a conversation thread).

- [x] **Phase 1 — foundation** *(shipped: V27 table, `discussion` API + `DiscussionsPage`)*: per-project
  discussions with **full-text search**, status + author filters, created/modified **sort**, paged;
  **states** for-review → open → rejected/resolved; create (any member), edit/status/delete (author or editor).
- [x] **Phase 2 — comments + inline links** *(shipped)*: markdown comments (author-editable) with
  `@orcid`/`@username` + `#nameID` rendered as links; reverse-link from a name to its discussions
  (Discussions tab on TaxonDetail). *(`#Genus_species` fuzzy name mentions still deferred.)*
- [x] **Phase 3 — public visibility + public URL route** *(shipped)*: editor toggles INTERNAL/PUBLIC;
  unauthenticated `/api/public/projects/{pid}/discussions[/{id}[/comments]]` (INTERNAL → 404); public
  read-only page at `/p/:pid/discussions/:id` (name mentions render as plain text there).
- [x] **Phase 4 — external API + per-project token** *(shipped)*: editor-managed API token (settings
  modal); token-gated `POST /api/public/projects/{pid}/discussions` (X-Api-Token) creates a `REVIEW`
  submission; editors Accept → `OPEN` / Reject on the detail page (turns COL user comments into discussions).
- [x] **Phase 5 — follow (heart) + email notifications** *(shipped)*: follow/unfollow with a heart on
  the detail page (following + follower count); authors/commenters auto-follow; new comments email
  followers (minus the actor) best-effort via `spring-boot-starter-mail` (sends only when
  `spring.mail.host` + `coldp.mail.from` are set, else logs).
- [x] **Phase 6 — link discussions to changes & work locks** *(shipped)*: `discussion_change` links a
  discussion to audit changelog entries (each carries its `task_id`, so the work session/lock is
  referenced too); "Linked changes" section on the detail page (editors link a recent change / unlink).
  *(Reverse indicator on the History page + explicit task/lock linking are optional follow-ups.)*
- [x] **Mention autocomplete** *(shipped — `MentionTextarea`)*: in the discussion/comment composer,
  `#` + a **capital letter** + 3+ letters (`#Xyz…`) opens a **name autocomplete** (reuses usage
  search) that inserts `#<id>` (the stable usage id), never the typed string; `@` + letters opens a
  member-username autocomplete inserting `@username`. Every mention stays precise, so fuzzy
  `#Genus_species` resolution isn't needed.

**Discussions — user identity polish (after Phase 2):** *(all shipped)*
- [x] **Avatars** — initials-based `UserAvatar` (ORCID exposes no photo) next to authors in the list,
  detail, comments, and the header user menu.
- [x] **Custom, unique username** — `PUT /api/me/username` + Account modal (header → Account).
- [x] **`@username`** mention syntax, alongside `@orcid`.

Spec: `docs/superpowers/specs/2026-07-20-discussions-design.md`.

### 2. AI-assisted curation  *(v1 slice shipped; spec `docs/superpowers/specs/2026-07-22-ai-assisted-curation-design.md`)*
- [x] **Backend config + availability** — `coldp.ai.*` (backend-only keys, installation-wide default
  provider/model); `GET /ai/config → {available, provider, model}` (never a key).
- [x] **Suggest pipeline** — `POST /usages/{id}/ai/suggest` (editor-only): context → LLM provider →
  **reference verification** against Crossref/DataCite (hallucinated refs dropped) → per-project
  **usage recording** (`ai_usage`). Provider abstraction (`LlmProvider`), **Anthropic adapter** via
  the official Java SDK (claude-opus-4-8).
- [x] **Frontend** — config-gated **brain icon** on the focal taxon → suggestions modal, **synonyms
  leading** (each with its verified nomenclatural reference); **one-click accept** creates the
  `SYNONYM` usage + link. Verified references listed; other categories shown for manual review.
- [x] **All four provider adapters** — Anthropic (official Java SDK) + OpenAI, Mistral
  (OpenAI-compatible), Google/Gemini via the shared `RestClient` + `AiPrompts`/`AiJson` helpers.
- **Follow-ups:** one-click accept for the non-synonym categories (references-attach — nomenclatural
  ref → the **name**, basionym's ref → the **basionym**; vernaculars; distribution; description;
  etymology); **per-project provider/model override** + settings UI; richer context (classification);
  "children" and bulk "review" scopes; live-key runtime test of the provider adapters (they compile
  but make live calls, so none is test-exercised).

### 3. Compare focal taxon with CLB  *(shipped; spec `docs/superpowers/specs/2026-07-20-clb-comparison-design.md`)*
- [x] Compare the focal taxon against one in ChecklistBank — side-by-side (name/authorship/rank/status
  + classification + synonyms, differences highlighted): `CompareClbModal` + `ClbComparisonView`,
  `GET /api/clb/{key}/compare/{id}`.
- [x] Pick a **dataset**, or **search the name across all datasets** (`GET /api/clb/usages`).
- [x] Keep a list of **favorite CLB datasets** in project settings (`project.favorite_clb_datasets` JSONB;
  managed in ProjectMetadataPage → Settings; favorite chips in the compare modal's "by dataset" mode).
- Note: the all-datasets search JSON parsing needs a live-CLB check (the by-dataset path reuses proven endpoints).

### 4. Delete options  *(shipped)*
- [x] Deleting a taxon asks what to remove: **focal only**, **focal + synonyms**, or **entire subtree** (DeleteNameModal + `DELETE /usages/{id}?mode=&reparentTo=`); non-subtree modes reparent children to the grandparent (default) or a searched-for new parent.

### 5. Global admin & user lifecycle  *(shipped)*
- [x] A global **admin** role (distinct from per-project owner/editor/viewer): `app_user.admin` flag → `ROLE_ADMIN`; bootstrapped from the `editor.admin.orcids` allowlist (and the dev login).
- [x] User **states** PENDING → ACTIVE → DISABLED (`app_user.state`): new ORCID self-signups land PENDING; local/allowlisted logins are ACTIVE. `ActiveUserFilter` 403s non-ACTIVE users on all protected routes; the SPA shows an "awaiting approval" / "account disabled" gate (RequireAuth → PendingApprovalPage).
- [x] Admin **Users** page (`/admin/users`, admin-only nav): list users, approve pending signups, disable/reactivate, toggle the admin flag (`GET/POST /api/admin/users…`); self-lockout guarded (can't demote or disable yourself).
- (The lighter public **join-request** flow already exists; this is the account-level layer above it.)

---

## References

- **Abbreviated botanical citations** — abbreviated **author** form and abbreviated **nomenclatural / reference title** for the botanical citation tradition (short *container* title already done). ColDP supports these.
- **CSL-JSON reference import** — reuse the `RefMapping` path that DOI / BibTeX / RIS already use.
- **DOI consolidation** — find DOIs for existing references (Crossref / DataCite lookup on the structured fields).
- **References list total count** — the list endpoint returns a bare `List` (prev/next paging, no total); add a count for a richer MRT table.
- **BHL page links (nomenclatural protologue)** *(shipped; spec `docs/superpowers/specs/2026-07-23-bhl-integration-design.md`)* — for literature with **no DOI** (not in Crossref). Two-level flow, key-gated (`coldp.bhl.api-key`): (1) link a **reference → a BHL item** (volume) from the References editor (`reference.bhl_item_id`, "Find on BHL"); (2) on the taxon form, once the name's nomenclatural reference has an item, **"Find page on BHL"** shows the pages where the name appears (BHL `GetNameMetadata` index — the likely protologue) plus all item pages, and picking one fills **`publishedInPageLink`** + `publishedInPage`. Follow-ups: validate the `GetNameMetadata` response mapping on the first live call (BhlClient is mocked in tests); a global "earliest appearance" suggestion across all of BHL before a reference is linked.

## Validation & issues

- **Remaining validation rules** — infraspecific-epithet-vs-parent; dangling pointers (references to deleted usages/refs). *(rank_vs_parent, genus_mismatch, species_epithet_mismatch, genus_year_after_species, year_vs_reference, synonym_of_non_accepted, synonym_without_accepted, duplicate_name, unparsable_name, missing_published_in are all done.)*
- **Evaluate the CLB backend issue enum** — decide which CLB issues are worth surfacing / mapping to Blixa validation rules.
- **Backend test coverage** — `GET /issues?entityId=` filter and the etymology PUT round-trip (code correct on inspection, untested).

## Tools & import

- **DwC-A import adapter** — map Darwin Core terms → ColDP into the shared staging path (reuse GBIF `dwca-io` / CLB readers). *(ColDP and TextTree adapters already exist.)*
- **Homotypic grouping** — *Side 1 shipped:* in-taxon detection + nested synonymy. Select an accepted
  taxon, auto-detect basionym-anchored homotypic groups among its synonyms (BasionymSorter-lite over
  parsed authorship), confirm, and persist as `name_relation` rows; the synonymy renders nested (≡/=)
  like the COL portal. `name_relation` is now the single source of truth for basionym (the
  `basionym_id` column was dropped; import/export go through the relation). *Side 2 shipped:* scan a focal taxon's accepted subtree for homotypic clusters resolving to >1
  accepted name (incl. via synonyms), pick a survivor (most descendants suggested), and demote the
  others to homotypic synonyms; pro-parte / dual-status flagged. Consolidation page + `GET/POST
  …/usages/{id}/homotypic/{conflicts,consolidate}`.
- **GBIF occurrence import into TypeMaterial** — by `occurrenceId` (the field is already carried).
- **Distribution map preview** — via portal-components, using the gazetteer `areaId`.
- **Shared taxon property keys** — manage a project's standard **property keys** (`col:property`): **autocomplete** the key when adding a new taxon property, an optional **description** per key, an on-demand **project-wide overview** of unique keys with usage counts, and **reconciliation** (merge two keys into one) from that overview. Mirrors the existing journal-title reconciliation pattern.

## UI / polish

- **Tree virtualization** — lazy-per-node is fine for now; needed at Lepidoptera scale (large sibling lists render in full today).
- **nomStatus as a Select** — currently a free-text input showing the enum name.
- **Link tracked changes to the current work objective / lock** — a prominent **top-right selector** to pick the active work objective/lock (or **none**) so subsequent tracked changes attach to it. Much of the plumbing may already exist (tasks/work-sessions, soft locks, `discussion_change`); the gap is mainly a convenient, prominent UI switch that sets the active objective for the change log.

## Deployment / docs

- Drop the Postgres-only docker compose; document configuring the pg dbname/user/password + Flyway init (confirm whether the backend auto-migrates on boot).
- Rename the "full" docker compose to just `docker-compose.yml`.

---

## Audited as DONE (filtered out of the backlog)

For transparency — these were open in the source files but the commit audit shows them shipped:

- **From `todo-next.md`/small items**: env-var dir configs, landing/login cleanup + ORCID-aware `/signin`, larger logo, metadata-page reorg, license gating, status-column chips, Change-status submenu, wider reference modal, BibTeX file upload, `/names` h3 heading, member auto-provision bug, BibTeX double-bracket bug, **reference full-text search**, **year/range filter**, reference-type enum, structured CSL authors, CSL-generated citations + style picker, member join flow, history deep-links, DOI import (3 forms + DataCite), journal reconciliation.
- **From `todo.md`/`features.md`**: acc↔syn workflow (P1–P4), the 10 validation rules above, issues dashboard + reviewer/datetime tracking + revalidate-on-demand, history changelog, references editor, all 7 name/taxon child entities, COL map + single & bulk identifier matching, ColDP export + import, supervised project merge, per-scope identifier match, bulk name insert, direct CLB taxon import, reference PDF upload/hosting, RIS import, landing page + join-request onboarding, Release entity (version/issued/metrics).
