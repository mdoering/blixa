# Use ChecklistBank & COL

[ChecklistBank](https://www.checklistbank.org) (CLB) hosts thousands of published checklists,
including the [Catalogue of Life](https://www.catalogueoflife.org) (COL). Blixa uses it in four ways:

- **Compare with CLB** puts one of your taxa next to the same taxon in any CLB dataset and lets you
  copy across what is missing.
- **Import from ChecklistBank** pulls a CLB taxon, its subtree or its supplementary data straight
  into your tree.
- **Match all identifiers** looks up every name in a CLB dataset and stores the matching
  identifier, for example a COL id.
- **Match to COL** links one taxon to COL so its distribution map can show GBIF occurrences.

Everyone in a project can compare. Copying, importing and matching need the **owner** or
**editor** role.

## Compare a taxon with CLB

Use this when you want to check a name, its authorship, classification or synonymy against
COL or another published checklist.

1. Select the taxon in the **Tree** (or open it from **Names**).
2. In the taxon header, click **Compare with CLB…**.
3. Pick the target in ChecklistBank:
   - **All datasets**: the search box is already filled with your name. Each hit shows its rank,
     status and dataset.
   - **By dataset**: click one of your **Favorites**, or type in **Search CLB datasets…** and pick a
     dataset. Then search for the name inside it. Use **change dataset** to switch datasets.
4. Click a hit to compare it. To go back to the list, click **← Pick another target**.

![Panthera leo compared with COL](/help/img/clb-compare.png)

### Reading the comparison

The table puts **Ours** (your project) on the left and **CLB** on the right. The dataset name in the
CLB column header links to the taxon on checklistbank.org.

- Rows show name, authorship, rank, status, accepted name (for synonyms), gender, etymology,
  published in, page, classification, synonyms, name relations, vernacular names and type material.
  Rows that are empty on both sides are hidden.
- Values that **differ** are shown in **orange bold**. For the classification, each differing rank is
  highlighted.
- In the list rows (synonyms, name relations, vernacular names, type material), records found on
  only one side carry an **only here** badge.

In the example above, the playground's *Panthera leo* has a *Syst. Nat.* published-in reference and
a subfamily (Pantherinae) that COL does not show here. It also has extra synonyms and vernacular
names, each marked **only here**.

### Copy values into your taxon

Use this when CLB has a value you want, such as a missing authorship.

1. Open the taxon and click **Compare with CLB…**, then pick a target.
2. Click the **«** button next to a differing CLB value. Single values can be copied for
   **Name**, **Authorship**, **Rank**, **Gender**, **Etymology** and **Page**.
3. The value goes into the taxon's edit form as an **unsaved change** ("Copied into the form — save
   to keep it"). Close the comparison, check the form and click **Save**.

For **Published in**, the **«** button creates the CLB citation as a new reference in your project.
It then puts that reference into the form ("Published-in reference created — save the form to keep
it"). Save the form to keep the link. The reference itself is already created.

For example, the playground's *Lynx pardinus* has no authorship. Compare it with COL and click **«**
on the **Authorship** row to fill in "(Temminck, 1827)", then save.

**Status** and **Accepted name** cannot be copied. To change them, use the status actions described
in [Synonyms & homotypy](/help/synonyms).

### Copy records

Use this when CLB lists synonyms, vernacular names, type material or name relations that your taxon
lacks.

1. In the comparison, find a CLB record marked **only here**.
2. Click its **«** button (**Copy to ours**). When more than one record is missing, click **« all**
   in the middle column to copy all of them at once.

Unlike single values, records are **saved straight away**. Each copied record is the full CLB record
(parsed name, authorship, references and a CLB identifier), as if it had been imported. Records can
only be copied onto an **accepted** taxon.

**Name relations** (for example *basionym*) need the related name to exist in your project. Blixa
looks for a name with the same spelling in your project, preferring one of the taxon's synonyms.
If the related name is one of the CLB taxon's synonyms (the usual case for a basionym), it is copied
together with the relation. Otherwise the relation is skipped, and the notification says how many
were skipped.

### Wire a taxon into the tree

Use this when a taxon sits in the wrong place, or at the top level, and you want to place it under
the higher classification CLB gives it. A typical case is right after a bulk add.

1. In the comparison, click the **«** next to **Classification** (tooltip "Wire into our tree along
   this classification…").
2. A preview lists the CLB classification, one rank per line:
   - **in tree**: this higher taxon is already your taxon's ancestor.
   - **exists elsewhere**: a taxon with this name and rank is somewhere else in your tree. It is used
     as it is and is not moved. **ambiguous** means there are several, and the first one is used.
   - **create**: not in your project. Tick it to create it. Main ranks (kingdom, phylum, class, order,
     family, genus) are pre-ticked. Intermediate ranks such as superfamily are not.
   - **not in tree**: missing, but above a taxon that already exists, so it cannot be created
     because it would be an empty branch.
3. Read the summary line. For the playground's *Leptailurus serval* (wrongly filed under *Felis*, flaw F5), compared with COL, it reads "*Leptailurus serval* moves under **Leptailurus** (now under Felis)", because the genus *Leptailurus* already exists elsewhere in the tree.
4. Click **Wire into tree**, or **Back** to return to the comparison.

The missing higher taxa are created and your taxon is moved, all in one step. This only works for
accepted or unassessed taxa compared against an accepted CLB taxon. If you copied values into the
form first, save them before wiring, because moving the taxon reloads the form.

## Import from ChecklistBank

Use this when you want to bring a CLB taxon into your project, either a whole genus or the
synonymy and data of one species, without importing a file.

1. In the **Tree** or **Names**, open the row actions menu (**⋮**) of an **accepted** taxon. This is
   the taxon you import into.
2. Click **Import from ChecklistBank…**.
3. Choose the source taxon:
   - **Paste URL**: paste a taxon link from checklistbank.org
     (e.g. `https://www.checklistbank.org/dataset/3LXR/taxon/6W3C4`) or catalogueoflife.org. The
     resolved name and dataset are shown below the field.
   - **Search**: pick a **Dataset**, then a **Taxon** within it. Optionally narrow the taxon search
     by **Rank**.
4. Under **What to import**, choose a mode (see below).
5. Click **Import**. A summary shows how many name usages, synonyms, references and other records
   were imported, plus any records that were skipped. Click **Close**.

![Import from ChecklistBank into Panthera](/help/img/clb-import-modal.png)

### Import modes

| Mode | What happens |
|---|---|
| **Taxon + its subtree (as children of …)** | The CLB taxon and everything below it are added as new children of your taxon, with their synonyms and data. |
| **Only its children** | The CLB taxon's children (and their subtrees) become new children of your taxon. The CLB taxon itself is skipped. |
| **Update … (import synonymy + info)** | Nothing new goes into the tree. The CLB taxon's synonyms and data are added to *your* taxon. Under **Include**, choose which: Synonyms, Vernacular names, Distributions, Type material, Media, Estimates, Properties, Name relations. |

For example, to fill in the cheetah's data, open the menu on *Acinonyx jubatus*. Then paste its COL
link and choose **Update *Acinonyx jubatus***.

### Limits and when to use a ColDP import instead

- The two subtree modes import at most **500 name usages** per run. A bigger subtree is refused
  with "subtree too large … pick a smaller root or use ColDP import".
- The import does **no matching**: it just adds records. Importing the same taxon twice creates
  duplicates, and references are not merged with ones you already have. Use
  [Merge selected names](/help/merge#merge-selected-names) to clean up afterwards.
- Each record is saved as it goes. If an import fails halfway, what was already imported stays.

For whole checklists or large groups, export a ColDP archive from ChecklistBank and import it as a
new project ([Start a checklist](/help/start-a-checklist)). Then bring it into your project with a
[supervised merge](/help/merge#merge-another-project), which matches names and lets you review every
change.

## Favourite CLB datasets

Use this when you compare against the same few datasets again and again.

1. Go to **Project** → **Settings**.
2. Under **Favorite CLB datasets**, type in **Search CLB datasets to add…** and pick a dataset.
   To remove one, click the bin icon next to it.
3. Click **Save**.

Favourites appear as quick picks under **By dataset** in **Compare with CLB…**. Owners and editors
can change them.

## Identifier scopes

Use this when your names should carry identifiers from another system, such as COL or IPNI ids.

1. Go to **Project** → **Settings** → **Identifier scopes (form fields)**.
2. Click **Add scope** and type or pick a scope (e.g. `ipni`, `col`).
3. Optionally enter the **CLB dataset key** whose taxon ids *are* that scope's identifiers. If you
   choose `col`, the key `3LXR` (the latest COL release) is filled in for you.
4. Click **Save**.

Each scope adds its own identifier field to the taxon **Details** form. A scope with a dataset key
can also be matched automatically (see below). A scope without one is for manual entry only.

## Match all identifiers

Use this when you want to fill in or check identifiers, for example COL ids, for every name in the
project at once.

1. Set up at least one identifier scope **with a dataset key** (see above). Without one, the button
   stays disabled.
2. Go to **Project** → **Tools** and click **Match all identifiers**.
3. A progress bar shows "Matched … of …". You can leave the page and come back.
4. When it finishes, badges summarise the result:
   - **verified**: the stored identifier was confirmed.
   - **added**: a missing identifier was added.
   - **updated**: a different identifier was found and replaced the stored one.
   - **unmatched**: no match was found.

Added, updated and missing identifiers are flagged on the [Issues](/help/validation) page (rules
such as `col_id_added`, `col_id_updated` and `col_id_missing`), so you can review them.

## Match a taxon to COL

Use this when a taxon's distribution map says **Not matched to COL yet** and you want to see GBIF
occurrences on it.

1. Open the taxon's **Distribution** tab. Above the map, the alert **Not matched to COL yet** appears.
2. Click **Match to COL**.
3. In **Match to Catalogue of Life**, pick the right candidate. Each one shows its name, authorship,
   rank, status, match type and classification.
4. Click **Use this**.

The COL id is stored on the taxon, and the GBIF occurrence layer appears if GBIF has records for it.
Owners and editors can turn that layer off for the whole project with **Show GBIF occurrence layer on maps** under
**Project** → **Settings**.
