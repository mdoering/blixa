# A tour of the screens

Once you are signed in, every Blixa screen has the same frame: a header across the top, a
sidebar on the left and the current page in the middle.

## Header

From left to right:

- **☰ / ✕** — collapses the sidebar to icons (or opens it on a small screen).
- **BLIXA** — takes you to your [Dashboard](#dashboard).
- **Project name** — the project you are working in, shown whenever you are inside one.
- **Work objective** (inside a project only) — shows **No objective** or the objective you are
  working on. Open it to pick an objective or create one with **New objective…**. See
  [Key concepts](/help/concepts#work-objectives).
- **?** — opens this help in a new tab. Many screens and dialogs have their own **?** that opens
  the matching help section.
- **Moon / sun** — switches between light and dark mode.
- **Your name** — opens a menu with **Account** (username and email, see
  [Your account settings](/help/access#your-account-settings)) and **Logout**.

## Sidebar

The top part is always there:

- **Dashboard** — your personal start page.
- **Projects** — all projects you are a member of.
- **Users** — only for administrators.

Inside a project, a **Project** section adds: **Tree**, **Names**, **References**, **Issues**,
**Discussions**, **History**, **Activity**, **Project** and **Members**. Owners see a badge on
**Members** when there are join requests waiting.

## Dashboard

![The Dashboard](/help/img/dashboard.png)

Your home page after signing in. **Needs attention** collects things to act on: projects with
missing metadata, open validation errors, names you still have locked, new comments and mentions
in discussions, discussion submissions waiting for review and (for administrators) people
awaiting approval. **Recently edited by you** links back
to the names you worked on last, and **My projects** shows each project with its numbers of
accepted names, synonyms and open issues.

## Projects

![The Projects page](/help/img/projects-list.png)

Lists the projects you belong to, with your role. Click a title to open the project. From here
you start new work: **New project** creates an empty project, **Import ColDP** creates one from a
file, and **Try it with demo data** creates your own copy of the Felidae playground. See
[Start a checklist](/help/start-a-checklist).

## Tree

![The Tree page with Panthera leo selected](/help/img/tree-overview.png)

The main workspace. On the left is the **Classification tree**: click the arrows to expand it,
click a name to select it. Hover a row (or right-click it) for its **⋮** actions menu — add a
child or synonym, move, change status, bulk add, import from ChecklistBank and more. The **Show
unassessed** switch reveals provisional names; **New name** adds one. The **‹** button collapses
the tree to give the details more room.

On the right is the selected name: its heading, rank and status, a breadcrumb of its parents and
these tabs:

- **Details** — the name itself: scientific name, authorship, rank, status, publication,
  nomenclatural status, gender, identifiers, etymology and remarks. Click **Save** after editing.
- **Synonyms** — the synonymy of an accepted name, grouped into homotypic (≡) and heterotypic (=)
  names.
- **Relations** — nomenclatural relations to other names (basionym, replacement name …).
- **Types** — type material.
- **Vernaculars**, **Distribution**, **Media**, **Estimates**, **Biology** — taxon data (accepted
  names only).
- **Issues** — validation findings for this name.
- **References** — supporting literature.
- **Discussions** — discussions that mention this name.

Above the tabs, **Download subtree** saves the selected branch as a TextTree file, **Compare with
CLB…** compares the name with ChecklistBank, and the circular-arrow button revalidates the
selected group. See [Build the classification](/help/build-the-tree) and
[Edit names & taxon data](/help/edit-names).

## Names

![The Names search](/help/img/names-search.png)

A searchable table of every name in the project, including synonyms. Filter with **Search
scientific name…**, **Rank** and **Status**; click a row to open the same details pane as on the
Tree page. Tick several rows to merge them or change their status together, and use **Download
TSV** to save the results.

## References

![The References page](/help/img/references.png)

The project's bibliography. Search by citation or year, click a row to edit it, and add
references with **New reference**, **Import DOI** or **Import** (BibTeX, CSL-JSON or RIS files).
**Reconcile journals…** tidies journal titles; ticking several rows lets you merge duplicates.
See [References](/help/references).

## Issues

![The Issues page](/help/img/issues.png)

All validation findings of the project, most severe first. The badges at the top
summarise them by severity and review status; filter by status and severity below. Click the
entity (e.g. *name_usage #432*) to open the name, use the **⋮** on a row to **Accept**, **Reject**
or **Reopen** it, and **Revalidate** to recheck the whole project. See
[Validation issues](/help/validation).

## Discussions

![The Discussions page](/help/img/discussions.png)

Threaded conversations about the checklist. Search, filter by status and author, sort, and open a
discussion to read and add comments. **New discussion** starts one; in its text, `#` mentions a
name and `@` a member. Open discussions double as [work objectives](/help/concepts#work-objectives).
See [Work together](/help/collaborate).

## History & Activity

![The History page](/help/img/history.png)

**History** is the project's change log: every create, update and delete, with who made it, when,
and under which objective. Click **Show diff** to see exactly what changed, and filter by
objective with **All objectives**.

**Activity** (titled **Current work**) lists who is editing which name right now — the current
soft locks — with their objective and when the lock expires.

## Project

![The Project page, Metadata tab](/help/img/project-metadata.png)

Everything about the project itself, on four tabs:

- **Metadata** — title, alias, license, description, geographic and taxonomic scope.
- **Settings** — public visibility (owners), nomenclatural code, GBIF map layer, citation style,
  identifier scopes, favourite ChecklistBank datasets and taxon property keys.
- **Releases** — project metrics, publishing a new release (owners) and the release history.
- **Tools** — **Export ColDP**, **Match all identifiers**, **Link genera**, **Merge project** and,
  for owners, the **Danger zone** with **Delete project**.

![The Project page, Tools tab](/help/img/project-tools.png)

See [Publish, release & export](/help/publish).

## Members

![The Members page](/help/img/members.png)

Who belongs to the project and with which role. Owners add or change a member by username with
**Add / update**, send email invitations with **Invite by email**, see **Pending invitations**,
handle **Join requests** and remove members. Everyone else sees the list read-only. See
[Work together](/help/collaborate).
