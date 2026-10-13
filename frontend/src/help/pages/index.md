# Welcome to Blixa

Blixa is a collaborative online editor for taxonomic checklists. You build and curate a
classification with its synonyms, references and supporting data, together with colleagues, and
publish it in the [Catalogue of Life Data Package (ColDP)](https://github.com/CatalogueOfLife/coldp)
format.

> Blixa is in active development. It runs as a development instance at
> [blixa.dev.catalogueoflife.org](https://blixa.dev.catalogueoflife.org) — features and screens
> may still change, and data there is not guaranteed to be kept.

## Who it is for

- **Taxonomists** who maintain a checklist for their group and want a proper tool instead of a
  spreadsheet.
- **Checklist curators and editors** who review and clean data from several sources.
- **Teams** who share the work on one checklist and need to see who changed what, and why.

## What you can do

### Start a checklist

- Create an empty project, or import a ColDP archive, a Darwin Core Archive or a TextTree file.
  See [Start a checklist](/help/start-a-checklist).
- Practise first on a ready-made Felidae dataset with **Try it with demo data** — see the
  [tutorial](/help/tutorial).

### Build and edit the classification

- Add, move, delete and bulk-add names in a classification tree; review provisional
  (unassessed) names. See [Build the classification](/help/build-the-tree).
- Edit a name's authorship, rank, status, publication and identifiers, plus vernacular names,
  distributions, media, type material and more. See [Edit names & taxon data](/help/edit-names).
- Demote accepted names to synonyms (and back), group homotypic synonyms and resolve homotypic
  conflicts. See [Synonyms & homotypy](/help/synonyms).
- Manage CSL-style references, look them up by DOI and attach PDFs. See
  [References](/help/references).

### Check quality

- Every change is checked automatically against validation rules; review the findings on the
  Issues page. See [Validation issues](/help/validation).
- Compare a name with the Catalogue of Life or any other ChecklistBank dataset, copy data
  across, or import whole subtrees. See [Use ChecklistBank & COL](/help/checklistbank).
- Merge duplicate names or references, or merge a whole project into another. See
  [Merge duplicates & projects](/help/merge).

### Work together

- Invite colleagues as owners, editors or viewers; see who is editing what; discuss names in
  threaded discussions with `#name` and `@person` mentions; follow the full change history. See
  [Work together](/help/collaborate).

### Publish

- Export a ColDP archive at any time, publish versioned releases, and make a project public. See
  [Publish, release & export](/help/publish).

## How Blixa relates to ColDP, ChecklistBank and COL

- **ColDP** is the data format of the Catalogue of Life. Blixa's data model follows it: what you
  edit in Blixa is what ends up in an exported ColDP archive, and you can import a ColDP archive
  to start a project.
- **[ChecklistBank](https://www.checklistbank.org)** is the repository of published checklists
  behind the Catalogue of Life. Blixa reads from it to compare names, copy data and import
  subtrees, so you can build on existing work.
- **COL (Catalogue of Life)** is assembled from checklists in ChecklistBank. A checklist you
  curate and release in Blixa as ColDP can be published to ChecklistBank and so contribute to
  COL. Blixa does not upload anything to ChecklistBank itself.

## Where to start

1. [Signing in & access](/help/access) — sign in with ORCID and get your account approved.
2. [Key concepts](/help/concepts) — projects, roles, name usages, the tree, synonyms.
3. [A tour of the screens](/help/tour) — what each part of the interface is for.
4. [Tutorial: the Felidae playground](/help/tutorial) — fix real problems in a practice
   dataset in about 20 minutes.

Looking for a term? Look it up in the [glossary of terms](/help/glossary). Something not
working? See the [frequently asked questions](/help/faq).
