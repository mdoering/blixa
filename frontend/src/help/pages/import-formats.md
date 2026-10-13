# Import formats

**Import ColDP** on the Projects page creates a **new project** from a file. Blixa recognises
three formats automatically. Any signed-in user can import, and you become the new project's
owner. For a walkthrough, see [Start a checklist](/help/start-a-checklist#import-a-file).

![Import ColDP modal](/help/img/import-modal.png)

## Choose a file

| Format | File | How Blixa recognises it |
|---|---|---|
| [ColDP](#coldp) | `.zip` | A zip without a `meta.xml` |
| [Darwin Core Archive](#darwin-core-archive) | `.zip` | A zip that contains a `meta.xml` |
| [TextTree](#texttree) | `.txtree`, `.tree`, `.txt`, `.tsv` | The file extension |

A `.tsv` file is always read as TextTree, never as a ColDP table. To import ColDP tables, zip
them together with a `metadata.yaml`.

## Import options

### Title

The **Title** field sets the new project's title.

- **ColDP:** if you enter a title, it **replaces the title in the archive's `metadata.yaml`**.
  If you leave it empty, Blixa uses the title from `metadata.yaml`.
- **Darwin Core Archive and TextTree:** if you leave it empty, Blixa uses the file name without
  its extension.

Each of your projects needs a different title. If you already have a project with that title,
the import fails with *you already have a project titled "…"*. Enter a different title and try
again.

### Preserve source identifiers

Use this when you want to keep the IDs from a ColDP archive, so you can trace each record back to
its source. This option is available for ColDP only, and is hidden for TextTree files.

1. Switch on **Preserve source identifiers**.
2. Enter an **Identifier scope**, for example `col`. Blixa suggests scopes from its vocabulary,
   and you can also type your own.

Blixa always gives every record its own new numeric ID. With this option on, it also stores each
record's original `ID` as an alternative identifier, `<scope>:<ID>`. For example, row `4CGXP` in a
COL download is kept as `col:4CGXP`. This applies to name usages, references and authors. See
[Identifier scopes](/help/checklistbank#identifier-scopes).

### Size limits

- An upload can be up to **100 MB** (104,857,600 bytes). The unzipped contents of a zip have the
  same limit.
- A zip can contain up to 10,000 files.

For bigger checklists, ask the Blixa administrators.

## What happens during an import

1. Click **Import**. Blixa unpacks and checks the file. A broken zip or an unreadable TextTree is
   rejected straight away, with a message.
2. While the import runs, the modal shows **Importing…**.
3. When it finishes, you see how many **usages**, **references** and **authors** were imported,
   any import issues, and an **Open imported project** link.
4. Blixa then validates the whole project in the background. Results appear on the
   [Issues](/help/validation#the-issues-page) page.

**Import issues** are rows Blixa skipped or links it couldn't resolve. For example, a `parentID`
that points to a missing row, a duplicate `ID`, or a vernacular name attached to a synonym. The
rest of the import still goes ahead.

If the import fails, **no project is created**: the modal shows **Import failed** and the reason.
Fix the file and import it again.

## ColDP

[ColDP](https://github.com/CatalogueOfLife/coldp) (Catalogue of Life Data Package) is a zip of
tab- or comma-separated tables plus a `metadata.yaml`. It's Blixa's own format: an export from
Blixa can be imported again, and so can a download from
[ChecklistBank](https://www.checklistbank.org). The files can sit at the top of the zip or inside a
single folder.

### Required files

- **Names:** either `NameUsage.tsv`, or `Name.tsv` + `Taxon.tsv` (with an optional `Synonym.tsv`).
- **`metadata.yaml`:** required, unless you enter a **Title** in the modal.

### Name usages

Blixa reads these `NameUsage` columns:

| Group | Columns |
|---|---|
| Identity and tree | `ID`, `parentID`, `status`, `ordinal`, `alternativeID` |
| Name | `scientificName` (required), `authorship`, `rank`, `notho`, `namePhrase`, `code` (first row only, sets the project's nomenclatural code) |
| Parsed parts | `uninomial`, `genericName`, `infragenericEpithet`, `specificEpithet`, `infraspecificEpithet`, `cultivarEpithet`, `combinationAuthorship`, `combinationExAuthorship`, `combinationAuthorshipYear`, `basionymAuthorship`, `basionymExAuthorship`, `basionymAuthorshipYear` |
| Publication | `nameReferenceID`, `namePublishedInYear`, `namePublishedInPage`, `namePublishedInPageLink`, `referenceID` |
| Nomenclature | `nameStatus`, `basionymID`, `gender`, `genderAgreement`, `etymology` |
| Taxon (accepted and unassessed only) | `extinct`, `environment`, `temporalRangeStart`, `temporalRangeEnd` |
| Other | `remarks` |

Good to know:

- Blixa re-parses every name on import, so the parsed parts are refreshed from `scientificName`,
  `authorship` and `rank`. Rows without a `scientificName` are skipped.
- The classification is built from `parentID` only. The flat rank columns (`kingdom` … `genus`)
  are ignored.
- For a synonym or misapplied name, `parentID` is its **accepted name**. A synonym that is linked
  to several accepted names (pro parte) comes as extra rows with IDs like `12-34`. Blixa
  recognises this pattern, which is what its own exports use.
- `basionymID` becomes a *basionym* relation on the **Relations** tab.
- `nameStatus` and the other vocabulary columns keep only values from the ColDP vocabularies. Blixa
  drops any other text.

### Taxonomic status

| ColDP `status` | Blixa status |
|---|---|
| `accepted` | Accepted |
| `provisionally accepted` | Unassessed |
| `synonym`, `ambiguous synonym` | Synonym |
| `misapplied` | Misapplied |
| `bare name` | Unassessed |
| empty or anything else | Unassessed |

Unassessed names stay in the tree at their `parentID`, but the Tree hides them until you switch on
**Show unassessed**. See [Unassessed names](/help/build-the-tree#unassessed-names).

### Name, Taxon and Synonym files

If the archive has separate `Name.tsv`, `Taxon.tsv` and `Synonym.tsv` files instead of
`NameUsage.tsv`, Blixa combines them into one name usage per taxon or synonym row:

- A taxon with `provisional` = `true` becomes **Unassessed**. Every other taxon becomes Accepted.
- A synonym takes its accepted name from `taxonID`. If it has no `status`, it is read as
  `synonym`.

### References and authors

- **`Reference.tsv`**: `ID`, `alternativeID`, `citation`, `type`, `author`, `editor`, `title`,
  `titleShort`, `containerTitle`, `containerTitleShort`, `issued`, `volume`, `issue`, `page`,
  `publisher`, `doi`, `isbn`, `issn`, `link`, `accessed`, `remarks`. Authors and editors are split
  into separate people at `;`. A `type` that isn't a CSL type is dropped. See
  [References](/help/references).
- **`Author.tsv`** (optional): `ID`, `alternativeID`, `given`, `family`, `suffix`,
  `abbreviationBotany`, `affiliation`, `country`, `birth`, `birthPlace`, `death`, `link`,
  `remarks`.

### Supplementary records

Blixa imports these files if they're present. Records that belong to a *taxon* are only kept for
accepted or unassessed names. When one is attached to a synonym, Blixa skips it and lists it as an
import issue.

| File | Attached by | Columns read |
|---|---|---|
| `TypeMaterial.tsv` | `nameID` (any name) | `citation`, `status`, `institutionCode`, `catalogNumber`, `locality`, `country`, `collector`, `date`, `sex`, `latitude`, `longitude`, `referenceID`, `link`, `remarks` |
| `NameRelation.tsv` | `nameID` → `relatedNameID` | `type`, `referenceID`, `page`, `remarks` |
| `Distribution.tsv` | `taxonID` | `area`, `areaID`, `gazetteer`, `establishmentMeans`, `threatStatus`, `referenceID`, `remarks` |
| `VernacularName.tsv` | `taxonID` | `name`, `language`, `country`, `sex`, `preferred`, `referenceID`, `remarks` |
| `Media.tsv` | `taxonID` | `url`, `type`, `title`, `creator`, `license`, `link`, `remarks` |
| `SpeciesEstimate.tsv` | `taxonID` | `estimate`, `type`, `referenceID`, `remarks` |
| `TaxonProperty.tsv` | `taxonID` | `property`, `value`, `page`, `referenceID`, `remarks` |

Blixa ignores all other ColDP files, such as species interactions, taxon concept relations and
treatments.

### metadata.yaml

Blixa reads six keys and ignores the rest:

| Key | Project field |
|---|---|
| `title` | Title (unless you enter one in the modal) |
| `alias` | Alias, unless it breaks the [alias rules](/help/publish) or another project already has it; then the project gets no alias |
| `description` | Description |
| `license` | License |
| `geographicScope` | Geographic scope |
| `taxonomicScope` | Taxonomic scope |

Blixa only allows the licenses **CC0-1.0** and **CC-BY-4.0**. If `license` holds any other value,
the import fails with *license must be CC0-1.0 or CC-BY-4.0*. Correct the value or delete the
line, then import again.

## Darwin Core Archive

A [Darwin Core Archive](https://dwc.tdwg.org/text/) (DwC-A) is a zip with a `meta.xml`
descriptor. GBIF and many checklist tools produce them. Blixa imports archives with a **Taxon
core**. Other core types are rejected.

### Taxon core terms

| Darwin Core term | Used as |
|---|---|
| `taxonID` (or the core id) | Row identity. Rows without one are skipped |
| `scientificName` | Scientific name |
| `scientificNameAuthorship` | Authorship |
| `taxonRank` | Rank |
| `taxonomicStatus` | Status (see below) |
| `parentNameUsageID` | Parent of an accepted name |
| `acceptedNameUsageID` | Accepted name of a synonym or misapplied name |
| `nomenclaturalStatus` | Nomenclatural status (ColDP vocabulary values only) |
| `namePublishedInYear` | Published in year |
| `taxonRemarks` | Remarks |
| `kingdom`, `phylum`, `class`, `order`, `family`, `subfamily`, `genus`, `subgenus` | Only for flat archives (see below) |

Blixa ignores the archive's EML metadata. Fill in the project metadata after the import.

### DwC taxonomic status

| `taxonomicStatus` contains | Blixa status |
|---|---|
| `misapplied` | Misapplied |
| `synonym` (e.g. `homotypicSynonym`, `proparte synonym`) | Synonym |
| exactly `accepted` or `valid` | Accepted |
| `provisional`, `doubtful`, `unassessed` or `interim` | Unassessed |
| empty or anything else | Unassessed |

If an archive has no `taxonomicStatus` column, **every name is imported as Unassessed**, so the
Tree looks empty until you switch on **Show unassessed**. To accept them in one go, use **Accept
subtree…** on the top taxon (see [Accept unassessed names](/help/build-the-tree#accept-unassessed-names)).

### Extensions

| Extension | Terms read |
|---|---|
| Vernacular names | `vernacularName`, `language`, `countryCode`, `sex`, `isPreferredName` |
| Distribution | `locality` (or `countryCode`) as the area, `locationID`, `establishmentMeans`, `threatStatus`, `occurrenceStatus` (stored in Remarks) |
| Species profile | `isExtinct`, `isMarine`, `isFreshwater`, `isTerrestrial` (first record per taxon) |

Blixa ignores all other extensions, such as references, types, multimedia and descriptions.

### Flat archives

Some archives have no `parentNameUsageID` and give the classification only in columns like
`family` and `genus`. For an accepted row without a parent, Blixa builds the higher taxa from the
`kingdom` … `subgenus` columns and places the row under the lowest one. Higher taxa with the same
path are created only once.

## TextTree

A TextTree file (`.txtree`, `.tree`, `.txt` or `.tsv`) is an indented list of names. The full
syntax is described in [TextTree syntax](/help/texttree). On import:

- Every name is added as **Accepted** (or as a synonym if marked with `=` or `≡`). An authorship
  after the name goes into the **Authorship** field, `≡` synonyms are recorded as homotypic with
  their accepted name, and lines marked `†` are flagged as extinct.
- The project has no metadata and no nomenclatural code. Set them on the **Project** page
  afterwards.
- **Preserve source identifiers** isn't available.

## What a ColDP export contains

**Export ColDP** (Project → **Tools**) builds a ColDP zip of the whole project. Any project member
can export. See [Export ColDP](/help/publish#export-coldp). The zip contains:

- `metadata.yaml`, with the project's title, alias, description, license, geographic scope and
  taxonomic scope
- `NameUsage.tsv`, with one row per accepted or unassessed name and one row per synonym or
  misapplied name for each accepted name it's linked to. Unassessed names are written as
  `provisionally accepted`. IDs are your project's own numeric IDs, so they stay stable from one
  export to the next.
- `Reference.tsv`
- `Author.tsv`, if the project has authors
- `TypeMaterial.tsv`, `NameRelation.tsv`, `Distribution.tsv`, `VernacularName.tsv`, `Media.tsv`,
  `SpeciesEstimate.tsv` and `TaxonProperty.tsv`, each only if the project has such records

Basionyms are written as *basionym* rows in `NameRelation.tsv`. You can import an export straight
back into Blixa.
