# Key concepts

A short tour of the ideas behind Blixa. Each section links to the how-to page with the details.

## Projects

A **project** is one checklist: its names, classification, references and supporting data, its
members, discussions and history. Everything in Blixa happens inside a project, and projects
never share data (you can [merge one project into another](/help/merge)).

Any approved user can create projects; whoever creates or imports a project becomes its owner.
Projects are private to their members until an owner makes them [public](/help/publish).
See [Start a checklist](/help/start-a-checklist).

## Roles

Every project member has one of three roles:

| What you can do | Owner | Editor | Viewer |
|---|:-:|:-:|:-:|
| Browse the tree, names, references, issues, history and activity | ✓ | ✓ | ✓ |
| Download subtrees and search results, export ColDP | ✓ | ✓ | ✓ |
| Start discussions, comment, follow; edit or close your own discussions | ✓ | ✓ | ✓ |
| Edit names, the tree, synonyms, references and taxon data | ✓ | ✓ | |
| Edit project metadata and settings | ✓ | ✓ | |
| Review validation issues, revalidate | ✓ | ✓ | |
| Import from ChecklistBank, match identifiers, merge names or projects | ✓ | ✓ | |
| Edit, close or delete anyone's discussion; make a discussion public | ✓ | ✓ | |
| Add and remove members, send invitations, handle join requests | ✓ | | |
| Make the project public, publish or delete releases | ✓ | | |
| Delete the project | ✓ | | |

A project always keeps at least one owner. Viewers see the same screens as everyone else, but
editing controls are hidden or read-only. See [Work together](/help/collaborate#members--roles).

## Name usages and status

Each row in a checklist is a **name usage**: a scientific name with its authorship and rank, used
in this checklist with a particular **status**:

- **Accepted** — the name of a taxon recognised in this checklist. Accepted names form the tree.
- **Synonym** — a name that refers to an accepted taxon under another name.
- **Misapplied** — a name that has been wrongly used for an accepted taxon (a misidentification
  in the literature).
- **Unassessed** — provisionally accepted: a taxon that sits in the tree and can hold data, but
  has not been reviewed yet. Unassessed names are hidden from the tree unless you switch on
  **Show unassessed**.

Each name usage has a numeric id that never changes while you edit it, so links, mentions and
merges keep pointing at the right name. See [Edit names & taxon data](/help/edit-names).

## The classification tree

The **tree** is built from accepted (and unassessed) names only: each one has a single parent,
from kingdom down to subspecies. Synonyms and misapplied names are not tree nodes — they hang off
the accepted name they belong to and are listed in its **Synonyms** tab.

A synonym normally points at one accepted name. A **pro parte** synonym points at several,
because the name's original concept was split. See
[Build the classification](/help/build-the-tree).

## Homotypic and heterotypic synonyms

- **Homotypic** (nomenclatural) synonyms share the same type — typically a **basionym** and its
  recombinations, e.g. *Felis concolor* Linnaeus, 1771 and *Puma concolor* (Linnaeus, 1771).
  They are marked **≡**.
- **Heterotypic** (taxonomic) synonyms are based on different types and only considered the same
  taxon by opinion. They are marked **=**, each heterotypic group led by its own basionym.

Because a type can belong to only one taxon, all homotypic names must end up under a single
accepted name; Blixa can find and resolve **homotypic conflicts** where they don't. See
[Synonyms & homotypy](/help/synonyms).

## Parsed names

You type a name into two fields: **Scientific name** (e.g. *Lynx pardinus*) and **Authorship**
(e.g. *(Temminck, 1827)*). These are the source of truth. Blixa's name parser derives the parts —
genus, epithets, name type and parse state — and shows them read-only under **Parsed name**;
they update whenever you change the name.

Names the parser cannot treat as a normal scientific name are flagged on the form, e.g.
**Informal name**, **Placeholder** or **Unparsed**. See
[Edit names & taxon data](/help/edit-names).

## References

**References** are bibliographic records — articles, books, web pages — stored with structured
fields (authors, year, title, container, volume, pages, DOI …). Unless you choose **Enter
citation manually**, the citation text is generated from those fields in the project's citation
style (APA by default). A name links to references as its original publication (**Published in
reference**) and as supporting literature (its **References** tab). See
[References](/help/references).

## Identifiers and identifier scopes

Besides its own id, a name can carry identifiers from other systems, written as
`scope:identifier` — for example `col:4QHKG` for a Catalogue of Life id, or an IPNI id under the
`ipni` scope. Under **Project → Settings → Identifier scopes** you choose which scopes get their
own input field on the name form; a scope with a ChecklistBank dataset key can also be matched
automatically for all names. See [Use ChecklistBank & COL](/help/checklistbank).

## Links to a name

When you select a name on the **Tree** or **Names** page, its id appears in the browser address
as `?usage=<id>`. Copy the address to share a direct link to that name — it opens with the name
selected and the tree expanded to it.

## Soft locks and edit conflicts

Several people can work in a project at the same time:

- When you start editing a name, Blixa places a **soft lock** on it. Others see a lock icon next
  to the name in the tree and a banner "*someone* is editing this name — your changes may
  conflict". The lock is a warning, not a block, and it is released when you move on (or after a
  few minutes of inactivity). The **Activity** page lists all current locks.
- If someone else saves the same name before you, your save is refused with **Changed by someone
  else — reloading**, and the form reloads with their version so nothing is silently overwritten.
  Re-apply your change and save again.

See [Work together](/help/collaborate).

## Validation issues

After every change Blixa checks the affected names against a set of rules — duplicate names,
synonyms pointing at non-accepted names, look-alike characters, genus mismatches, missing
publication and more. Findings appear as **issues** with a severity (error, warning, info) on the
**Issues** page and in a name's **Issues** tab. You fix the data, or mark a finding as reviewed.
See [Validation issues](/help/validation) and [Validation rules](/help/validation-rules).

## Work objectives

A **work objective** describes the piece of work you are doing, e.g. "Revise genus *Lynx*". It is
simply an open discussion that you select in the header. While it is active, your changes and
locks are tagged with it, so the History page can show everything done for that objective.
Working without an objective (**No objective**) is the default and perfectly fine. See
[Work together](/help/collaborate).

## Releases and exports

- An **export** is a ColDP archive of the project's current state, which any member can create
  and download at any time.
- A **release** is a numbered, permanent snapshot (e.g. version 1.0) published by an owner. It
  requires a license; releases of a public project are listed on its public page.

See [Publish, release & export](/help/publish).
