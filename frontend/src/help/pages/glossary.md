# Glossary

Terms used in Blixa and in this help, in alphabetical order. For the bigger picture, see
[Key concepts](/help/concepts).

## Accepted name

The name that a checklist treats as correct for a taxon. Accepted names form the classification
tree: each has a parent and can have children and synonyms. In Blixa, **Accepted** is one of the
four statuses, alongside Synonym, Misapplied and Unassessed. See
[Build the classification](/help/build-the-tree).

## Authorship

The author(s) and year that follow a scientific name, for example `(Linnaeus, 1758)`. Authors in
parentheses are the basionym's authors, which shows that the name has since been moved to another
genus. Blixa keeps authorship in its own **Authorship** field. See
[Edit names & taxon data](/help/edit-names).

## Autonym

An infraspecific name that repeats the species epithet, for example *Panthera leo leo*. An autonym
has no authorship of its own: it takes the species' author. Blixa flags one with an authorship as
`superfluous_authorship` (see [Validation rules](/help/validation-rules)).

## Basionym

The original name on which later combinations are based. *Felis leo* Linnaeus, 1758 is the
basionym of *Panthera leo* (Linnaeus, 1758). Blixa stores it as a *basionym* relation on the
**Relations** tab, and uses it to group homotypic synonyms. See
[Group synonyms](/help/synonyms#group-synonyms). Contradicting or duplicate relations can be
reviewed under [Clean name relations](/help/relation-cleanup).

## ChecklistBank

[ChecklistBank](https://www.checklistbank.org) (CLB) is the Catalogue of Life's open repository of
taxonomic checklists. Blixa uses it to compare your taxa with other datasets, import names, and
match identifiers. See [Use ChecklistBank & COL](/help/checklistbank).

## COL

The [Catalogue of Life](https://www.catalogueoflife.org), a global index of the world's known
species, compiled from many checklists and published through ChecklistBank. The Blixa playground
dataset is taken from COL. See [Use ChecklistBank & COL](/help/checklistbank).

## ColDP

The [Catalogue of Life Data Package](https://github.com/CatalogueOfLife/coldp): a zip of tables
(names, references, distributions, vernacular names…) plus a `metadata.yaml`. It's Blixa's native
import and export format. See [Import formats](/help/import-formats#coldp).

## Consolidation

Resolving a **homotypic conflict**: within a group of names that share a type, you pick one name
to stay accepted, and the other accepted names become its homotypic synonyms. You start it with
**Find homotypic conflicts** in a taxon's ⋮ menu. See [Homotypic conflicts](/help/synonyms#homotypic-conflicts).

## CSL

Citation Style Language, a standard way to describe and format bibliographic references. Blixa
stores references in CSL form (type, authors, title, container title, issued date…) and formats
citations in the project's **Citation style**. See [References](/help/references).

## DOI

Digital Object Identifier, a permanent identifier for a publication, such as
`10.1126/science.1122277`. Blixa can look up a DOI on Crossref or DataCite and fill in a
reference for you. See [From a DOI](/help/references#from-a-doi).

## DwC-A

Darwin Core Archive: a zip of Darwin Core tables described by a `meta.xml` file, widely used by
GBIF. Blixa can import archives with a Taxon core. See
[Import formats](/help/import-formats#darwin-core-archive).

## Heterotypic synonym

A synonym based on a **different type** from the accepted name. Also called a subjective or
taxonomic synonym. Whether it belongs to the same taxon is a matter of opinion. It's written with
`=` in synonymies and TextTree. See [Group synonyms](/help/synonyms#group-synonyms).

## Homotypic synonym

A synonym based on the **same type** as the accepted name, usually its basionym or another
combination of it. Also called an objective or nomenclatural synonym. *Felis leo* is a homotypic
synonym of *Panthera leo*. It's written with `≡`. See [Group synonyms](/help/synonyms#group-synonyms).

## Identifier scope

A short prefix that says where an identifier comes from, such as `col` or `ipni`. Blixa stores
external identifiers as `<scope>:<id>`, for example `col:4CGXP`. You set up a project's scopes on
**Project → Settings**. A scope with a ChecklistBank dataset key can be matched automatically.
See [Identifier scopes](/help/checklistbank#identifier-scopes).

## Misapplied name

A name that has been used wrongly for a taxon it doesn't belong to, for example in a regional
flora. Like a synonym, it's linked to an accepted name, but it's not a synonym in the
nomenclatural sense. **Misapplied** is one of Blixa's four statuses. See
[Misapplied names](/help/synonyms#misapplied-names).

## Nomenclatural code

The set of rules that governs names in a group of organisms: zoological (ICZN), botanical
(ICN), bacterial, virus, cultivated plants and so on. You set it on **Project → Settings**
(**Nomenclatural code**). It affects how Blixa parses and formats names.

## Nomenclatural status

The standing of a name under its code, for example established, conserved or rejected. Set it in
the **Nomenclatural status** field of a name. See [Nomenclatural status](/help/edit-names#nomenclatural-status).

## Objective

A piece of work you're doing, such as *Revise genus Lynx*. An objective is an open discussion.
You pick it from the objective menu in the header (it shows **No objective** until you choose one,
or create one with **New objective…**). While it's active, your changes and locks are tagged with
it, so the **History** and **Activity** pages can show what belongs together. See
[Work objectives](/help/collaborate#work-objectives).

## Pro parte

"In part": a synonym that applies to **more than one** accepted taxon, because the original
concept was split. In Blixa, a pro parte synonym is a single name linked to several accepted names
(use **Add accepted name…**). See [Pro parte synonyms](/help/synonyms#pro-parte-synonyms).

## Provisionally accepted / unassessed

**Unassessed** is Blixa's status for a name you haven't confirmed yet. Unassessed names sit in the
tree like accepted names and can hold taxon data, but the Tree hides them until you switch on
**Show unassessed**. ColDP calls this status *provisionally accepted*, and Blixa converts between
the two on import and export. Use **Accept subtree…** to accept many at once. See
[Unassessed names](/help/build-the-tree#unassessed-names).

## Release

A frozen, versioned ColDP snapshot of a project, published by an owner on **Project →
Releases** with a version number such as `1.0`. A release needs a license. Unlike an export, it's
kept as a permanent record. See [Releases](/help/publish#releases).

## Soft lock

A short-lived marker that says someone is editing a name. When you start editing, Blixa takes the
lock and keeps renewing it while you work. Others see *"… is editing this name"*, but they **can
still save**, which is why the lock is called soft. Unused locks expire after about five minutes.
**Activity** lists all current locks. See [Soft locks](/help/collaborate#soft-locks).

## Synonym

Another name for an accepted taxon. It's linked to its accepted name instead of having a place of
its own in the tree. A synonym can be homotypic or heterotypic. See
[Synonyms & homotypy](/help/synonyms).

## TextTree

A plain-text tree format from GBIF: one name per line, with two-space indentation for children and
`=` for synonyms. Blixa reads it for **Bulk add…** and imports, and writes it with **Download
subtree**. See [TextTree syntax](/help/texttree).

## Type

The specimen (or, for higher names, the lower-ranked name) that anchors a name. Names with the same
type are homotypic. You record type specimens on a name's **Types** tab.

## Validation issue

A problem Blixa's automatic checks found in your data, with a rule key such as `genus_mismatch` and
a severity (error, warning or info). Issues can be open, accepted, rejected or done. See
[Validation issues](/help/validation) and [Validation rules](/help/validation-rules).
