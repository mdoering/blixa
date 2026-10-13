# Edit names & taxon data

Select a name on the **Tree** or **Names** page and its record opens on the right. At the top is
the name with its authorship, rank and status. Below that are the
[classification bar](/help/build-the-tree#browse-the-tree) and a row of tabs. The number after a
tab name, as in *Synonyms (1)*, counts its records.

Editing needs the **owner** or **editor** role. Viewers see the same record, but read-only.

![Lynx pardinus on the Details tab, with its authorship missing](/help/img/taxon-details.png)

Next to the name are a few actions:

- **Revalidate this group** (circular arrows): re-checks this taxon and everything below it. See
  [Validation issues](/help/validation#revalidate).
- **AI suggestions** (brain icon): only shown if your Blixa instance has an AI provider set up.
- **Compare with CLB…**: compare the name with ChecklistBank and copy values across. See
  [Compare a taxon with CLB](/help/checklistbank#compare-a-taxon-with-clb).

## The Details tab

Use this to correct a name or to record its nomenclature.

1. Change any of the fields described below.
2. Click **Save** at the bottom of the form. Fields you edited but didn't save are lost when you
   select another name.

| Field | What to enter |
|---|---|
| **Scientific name** | The name without authorship, e.g. `Lynx pardinus` |
| **Authorship** | e.g. `(Temminck, 1827)` |
| **Rank** | Type to search the full list of ranks |
| **Status** | See [Status](#status) |
| **Published in reference** | The reference where the name was published. Pick it from the project's [references](/help/references) |
| **Published in year** | Year of publication |
| **Published in page** | The page, e.g. `41` |
| **Published in page link** | A link to that page, e.g. on BHL |
| **Nomenclatural status** | See [Nomenclatural status](#nomenclatural-status) |
| **Gender** / **Nomenclatural genus** / **Gender agreement** | See [Genus and gender](#genus-and-gender) |
| Identifiers | See [Identifiers](#identifiers) |
| **Etymology** | The origin of the name |
| **Remarks** | Free-text notes |

**Example (F1):** in the playground, *Lynx pardinus* has no authorship. Select it, type
`(Temminck, 1827)` into **Authorship** and click **Save**.

### Status

The **Status** field only switches between related statuses: *Accepted* and *Unassessed*, or
*Synonym* and *Misapplied*. To turn an accepted name into a synonym or back, use
[Demote or Promote](/help/synonyms#demote-a-name), because that also has to decide what happens to
children and synonyms.

### Find the page on BHL

Use this to link a name to the exact page of its original description in the Biodiversity
Heritage Library.

1. Make sure the name's **Published in reference** is linked to a BHL item. You do that on the
   reference itself; see [Find on BHL](/help/references#find-on-bhl). The button below only shows
   once the reference has a BHL item.
2. On the Details tab, click **Find page on BHL…**.
3. Under **Where "…" appears**, Blixa lists the pages where BHL's text recognition found the name.
   These are the likely original descriptions. If none matched, browse **All pages in this item**.
   Click a page number to look at the page on BHL.
4. Click **Use this page**. Blixa fills in **Published in page** and **Published in page link**.
5. Click **Save**.

### Nomenclatural status

Pick the status from the list, for example a conserved or rejected name. The labels follow the
project's nomenclatural code: zoological terms in a zoological project, botanical terms otherwise
(bacterial projects use the botanical terms too). Set the code under **Project → Settings →
Nomenclatural code**.

### Genus and gender

The fields shown depend on the rank:

- **On a genus**, choose its grammatical **Gender**.
- **On a species or lower rank**:
  - **Nomenclatural genus** links the name to the genus its epithet agrees with. This is normally
    the genus in the name, but you can pick another genus from the project, or clear the link.
    This field **saves immediately**. You don't need to click **Save**.
  - **Gender** next to it shows the linked genus's gender and can't be edited here. It reads
    **Gender (unconfirmed)** while no genus is linked.
  - Tick **Gender agreement** if the epithet changes its ending to match the genus gender (e.g.
    *alba*/*albus*). Then click **Save**.

### Identifiers

The name's extra identifiers (for example a COL ID, or IDs kept from an import) show as chips
below the nomenclature fields. Where Blixa knows the source, the chip is a link to the record
there.

To edit them, the project needs **Identifier scopes** (set under **Project → Settings**):

1. Click the pencil (**Edit identifiers**) next to the chips.
2. Fill in the field for each scope, for example **IPNI**.
3. Click **Save**.

### Parsed name

Below the form, under **Parsed name**, Blixa shows how it broke the name into parts: **Name
type**, **Parse state**, **Uninomial**, **Genus**, **Specific epithet**, **Infraspecific
epithet** and **COL id**. These are worked out from the scientific name and can't be edited
directly. Correct the **Scientific name** instead.

If the name can't be broken down cleanly, a badge appears right under the name fields:

- An **orange** badge (**Partially parsed** or **Unparsed**) means it looks like a scientific name,
  but the parser couldn't break all of it into parts. This often points to a typo.
- A **red** badge means it isn't a regular scientific name at all: **Hybrid formula**, **Informal
  name**, **Placeholder**, **Identifier** or **Other / unparsable**.

**Example (F8):** the playground's *Felis sp. "Bornean form"* is an informal placeholder for an
undescribed cat. It gets a red badge and an `unparsable_name` issue. You can keep such a name on
purpose while the taxon is undescribed, or replace it with a proper name once one exists. The
name is unassessed, so switch on
[Show unassessed](/help/build-the-tree#unassessed-names) to find it under *Felis*.

### If someone else changed the name

Blixa protects you from silently overwriting a colleague's work:

- While someone else is editing a name, an orange banner says **"… is editing this name — your
  changes may conflict."**, and a lock icon shows on its row in the tree and on the Names page.
- If someone saved the name after you opened it, your **Save** is refused with **Changed by
  someone else — reloading**. The form then reloads with their version. Your unsaved edits are
  discarded, so enter them again if they are still needed.

See [Work together](/help/collaborate) for how locks work.

## The other tabs

Synonyms, misapplied and unassessed names show only **Details**, **Relations**, **Types**,
**Issues**, **References** and **Discussions**. The taxon tabs (Synonyms, Vernaculars,
Distribution, Media, Estimates and Biology) appear only on **accepted** names.

On most tabs, editors click **Add** to create a record, and use a record's **⋮** menu to **Edit**
or **Delete** it. Each form has a **Reference** field to cite the source of the record, and a
**Remarks** field.

### Synonyms

The accepted name's synonymy. See [Synonyms & homotypy](/help/synonyms#the-synonyms-tab).

### Relations

Nomenclatural relations between this name and another, such as *basionym*, *replacement name*,
*spelling correction* or *later homonym*. Pick the **Type** and the **Related name**, and
optionally a **Reference** and **Page**. Relations other names have to this one are listed below,
under **Relations from other names**.

### Types

Type material for the name: **Type status** (holotype, lectotype, …), **Citation**, **Institution
code**, **Catalog number**, **Locality**, **Country**, **Collector**, coordinates and more.

To copy type specimens from GBIF:

1. Click **Import from GBIF…**. Blixa looks up the name in the Catalogue of Life and lists GBIF
   occurrences recorded as type specimens. If the name has no COL match, nothing can be imported.
2. Tick the specimens you want. Ones you imported before are marked *imported* and can't be
   ticked again.
3. Click **Import N**.

### Vernaculars

Common names: **Name**, **Language**, **Country**, **Sex** and whether it is **Preferred**.

### Distribution

Where the taxon occurs: either an **Area (free text)**, or an **Area ID** with its **Gazetteer**
(iso, tdwg, fao, gadm), plus **Establishment means** and **Threat status**.

![The Distribution tab with its map](/help/img/distribution-map.png)

A map above the list draws every coded area, coloured by establishment means. Free-text areas
can't be mapped, and are listed under the map as *Not mappable*. Use the check boxes to show or
hide layers: **Distribution (focal)**, **Distribution (children)**, **Type specimens (focal)**,
**Type specimens (children)** and **GBIF occurrences**.

The GBIF occurrence layer needs the taxon to be linked to a Catalogue of Life ID. If it isn't, a
**Not matched to COL yet** box appears:

1. Click **Match to COL**.
2. In **Match to Catalogue of Life**, pick the right candidate.
3. Click **Use this**.

Whether the GBIF layer is on by default is set under **Project → Settings → Show GBIF occurrence
layer on maps**. See also [Match a taxon to COL](/help/checklistbank#match-a-taxon-to-col).

### Media

Images, video or audio: **URL**, **Type**, **Title**, **Creator**, **License** and **Link**.

### Estimates

Estimated numbers, for example of species in a genus: **Estimate** and **Type**.

### Biology

The top part records whether the taxon is **† Extinct**, its **Environment** (marine,
freshwater, …), and its **Temporal range start** and **Temporal range end**. Click **Save** to
store them.

Below, **Properties** holds any other facts as **Property** and **Value** pairs, for example
*body mass* = *50–90 kg*. The **Property** field suggests keys already used in the project. Owners and
editors can tidy those keys under **Project → Settings → Manage property keys…**.

### Issues

The validation issues found for this name. See [Validation issues](/help/validation#issues-on-a-taxon).

### References

References that support this name, beyond the one it was published in:

- Pick one under **Add existing reference** and click **Add**.
- Or paste a web address under **Add web URL** and click **Add**. Blixa creates a web reference
  for it.
- Click **Remove** to unlink a reference. The reference itself stays in the project.

### Discussions

Discussions that mention this name. See [Work together](/help/collaborate).

## Find names on the Names page

Use this to search all names in the project, including synonyms, which the tree doesn't show.

![The Names page searching for Lynx](/help/img/names-search.png)

1. Open **Names** in the sidebar.
2. Type into **Search scientific name…**. Names starting with your text come first, followed by
   similar spellings, so small spelling differences still find the name.
3. Narrow the results with the **Rank** and **Status** filters.
4. Click a row to open the name on the right. Right-click a row, or click its **⋮**, for the same
   [actions menu](/help/build-the-tree#the-row-actions-menu) as in the tree.

The **Status** column uses short badges: **Acc**, **Syn**, **Mis** and **Una**.

### Change many statuses at once

Use this to switch a batch of names between accepted and unassessed, or between synonym and
misapplied.

1. Tick the check boxes of the names. They must all have the **same** status.
2. Click **Mark N as …**. Accepted names become *Unassessed* and vice versa, synonyms become
   *Misapplied* and vice versa. If the selected names have different statuses, the button reads
   **Change status** and is greyed out.

To change every name that matches a search, not just the visible page:

1. Set the **Status** filter, plus any other filters you need.
2. Tick the box in the table header to select the whole page, then click **Select all N
   matching**.
3. Click **Mark N as …**. Click **Clear selection** to start over.

With two or more names ticked you can also click **Merge N selected…** to combine duplicates. See
[Merge duplicates & projects](/help/merge).

### Download the results

Click **Download TSV** to save the names that match the current search and filters as a
tab-separated file, ready for a spreadsheet.
