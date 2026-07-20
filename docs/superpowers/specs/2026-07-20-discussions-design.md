# Discussions — design

**Date:** 2026-07-20
**Status:** in progress (Phase 1)

Per-project, ORCID-keyed **forum-style discussions**: threaded markdown comments with
@mentions and name links, follow/subscribe with email, public or internal visibility, a
public URL route, an external submission API, and a small state machine. Lets COL users
and editors discuss taxa / data-quality feedback in place (mirroring the col-feedback
flow in the `data` repo).

Named **Discussions** (not "issues") because the shape is a conversation thread, and the
app already owns **"Issues"** for **validation** findings — the `issue` table, the
`/api/projects/{pid}/issues` route, and the `IssueService`/`IssueController`/`IssueMapper`
Spring beans. A distinct name avoids table/route/bean collisions with zero renames.

## Namespace
- Package `org.catalogueoflife.editor.discussion`.
- Tables `discussion`, `discussion_comment` (P2), `discussion_follow` (P5), `discussion_usage` (P2).
- Route `/api/projects/{pid}/discussions`; public read `/api/public/projects/{pid}/discussions/{id}` (P3).
- Entities `Discussion`, `DiscussionComment`; enums `DiscussionStatus`, `DiscussionVisibility`.
- UI nav **"Discussions"** (validation "Issues" nav untouched).

## Data model (Phase 1 creates only `discussion`)
Compound `(project_id, id)` PK, id allocated per project via
`IdSeqMapper.allocate(projectId, "discussion")` — reads as `#1, #2…` like reference/name_usage.

- `title TEXT NOT NULL`, `body TEXT` (markdown)
- `status TEXT NOT NULL DEFAULT 'OPEN'` — `REVIEW | OPEN | REJECTED | RESOLVED`
- `visibility TEXT NOT NULL DEFAULT 'INTERNAL'` — `INTERNAL | PUBLIC` (changing it is P3)
- `author_id INTEGER REFERENCES app_user(id) ON DELETE SET NULL` (nullable — P4 API submissions)
- `author_orcid TEXT` (denormalized display key)
- `created_via TEXT NOT NULL DEFAULT 'UI'` — `UI | API`
- `created_at`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `version INTEGER NOT NULL DEFAULT 0` (optimistic locking, reference convention)
- Full-text GIN index on `to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(body,''))` (same as `reference_citation_fts`, V26).
- Indexes `(project_id, status)`, `(project_id, author_id)`.

Migration: `V27__discussion.sql`.

## States
`REVIEW → OPEN | REJECTED` (editor triage of externally-submitted, P4); `OPEN → RESOLVED | REJECTED`;
`RESOLVED/REJECTED → OPEN` (reopen). UI discussions start `OPEN`; API submissions start `REVIEW` (P4).

## Authorization (Phase 1)
- **create / read**: any project member (viewer+).
- **update (title/body)**: the author, or an editor/owner.
- **status**: the author, or an editor/owner.
- **delete**: editor/owner.
- All discussions are INTERNAL in P1; PUBLIC visibility + public route land in P3.

## API (Phase 1) — `/api/projects/{pid}/discussions`
- `GET ?q=&status=&authorId=&sort=created|modified&order=asc|desc&limit=&offset=` → `DiscussionPage{items,total}`; `q` = full-text over title+body.
- `POST` — create (member): `{title, body}`.
- `GET /{id}` · `PUT /{id}` (author/editor, optimistic `version`) · `POST /{id}/status` (author/editor) · `DELETE /{id}` (editor).

No changelog/audit entry for discussions in P1 (the History changelog is for taxonomic curation).

## Frontend (Phase 1)
- Sidebar nav **"Discussions"** per project.
- `DiscussionsPage`: MRT tabular list (full-text search, status filter, author filter,
  sortable created/modified, paged off `total`); row → detail/edit; `DiscussionForm`
  modal (title + markdown body). `<Title order={3}>Discussions</Title>`.

## Phasing
1. **Foundation** *(this pass)* — schema, states, CRUD, tabular list (full-text + status + author + sort), frontend list/create/edit.
2. **Comments + markdown + inline entity links** — `@orcid`, `#Genus_species`, `#nameID`; author-editable comments; `discussion_usage` reverse-links from names.
3. **Public visibility + public URL route** — editor sets PUBLIC; unauthenticated read.
4. **External API + per-project token** — submissions arrive `REVIEW`; editor accept → `OPEN`.
5. **Follow (heart) + email** — `discussion_follow`; needs SMTP (`coldp.mail.*`).
6. **Link (closed) discussions to changes & work locks**.

## Testing
TDD. Backend `DiscussionApiIT` (MockMvc + Postgres); frontend `DiscussionsPage.test.tsx`.
Full-text reuses the `websearch_to_tsquery('simple', …)` pattern from the reference search work (V26).
