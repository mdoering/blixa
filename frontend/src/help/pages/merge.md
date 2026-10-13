# Merge duplicates & projects

Blixa has two kinds of merge:

- **Merge selected records** joins two or more duplicate names, or duplicate references, *within*
  a project into one record.
- **Merge project** brings all the names and references of *another* project into this one. Blixa
  matches them against what you already have and lets you review every match first.

Both need the **owner** or **editor** role.

## Merge selected names

Use this when the same name was entered more than once and you want a single record.

You pick one record as the **survivor**. Everything attached to the others moves to it: children,
synonyms, basionym links, name relations, vernacular names, distributions, type material, media,
properties and estimates. The other records are then deleted. The survivor keeps its own name,
authorship, status, published-in reference and other fields. Nothing is copied over field by field.

1. Open **Names** and search for the duplicates, e.g. type `Lynx rufus` and filter by rank.
2. Tick the checkbox of each duplicate row (at least two).
3. Click **Merge 2 selected…** (the number matches your selection).
4. The dialog lists each record with its id, any source identifiers (e.g. `col:…`), its rank and
   status, and badges counting what is attached to it (children, synonyms, vernacular names …).
5. Under **Keep this record as the survivor**, pick the record to keep. Blixa pre-selects the one
   with the most attached data.
6. Click **Merge 2 records into …**.

The table refreshes and the merged duplicates are gone. The survivor is revalidated, and the merge
is recorded in [History](/help/collaborate#history).

If any of the records has accepted children, the survivor must be an **accepted** name. Accepted
children can only sit under an accepted name.

### Example: the duplicate bobcat

The playground has *Lynx rufus* (Schreber, 1777) twice (flaw F3). The [Issues](/help/validation)
page shows two `duplicate_name` warnings, and it also appears as a homotypic conflict.

![Merging the two Lynx rufus records, picking the survivor](/help/img/merge-records-modal.png)

1. On **Names**, search `Lynx rufus` and set the rank to species. Two identical rows appear.
2. Tick both and click **Merge 2 selected…**.
3. One record has the subspecies *L. r. fasciatus* and *L. r. rufus* as children (badge
   **children 2**). The other was added later and has nothing attached. Blixa pre-selects the first.
   Keep it.
4. Click **Merge 2 records into Lynx rufus (Schreber, 1777)**.

Both `duplicate_name` warnings disappear shortly afterwards.

If the duplicates are not exact, check them side by side first. If one is really a synonym of the
other, [demote it](/help/synonyms) instead of merging.

## Merge selected references

Use this when the same publication was entered twice, e.g. once with a DOI and once without.

1. Open **References** and search for the duplicates.
2. Tick the checkbox of each duplicate (at least two).
3. Click **Merge 2 selected…**.
4. Pick the survivor. The badges show how many names and other records cite each reference.
5. Click **Merge 2 records into …**.

Every name, synonym link, vernacular name, distribution and other record that cited a merged
reference now cites the survivor.

## Merge another project

Use this when you want to bring a second dataset into an existing checklist: an updated version of
a source, an extra regional list, or a group exported from ChecklistBank.

Blixa always imports a ColDP or other file into a **new** project. To combine it with your existing
checklist:

1. **Import the file as a new project** (see [Start a checklist](/help/start-a-checklist)). This
   project is only a staging area. Check its [Issues](/help/validation) and fix anything obvious
   there first.
2. **Open your main project** (the target) and go to **Project** → **Tools**.
3. Under **Merge project**, click **Merge…**.
4. In **Source project**, pick the staging project. You can only pick projects you are a member of.
5. Click **Start merge**. Blixa works out a plan ("Computing merge plan…"). Nothing in your project
   changes yet.

![The Tools tab](/help/img/project-tools.png)

### Read the plan

When the plan is ready, **Impact** sums up what would happen:

- **Names**: **new** (no match in your project), **matched** (same name and rank, compatible
  authorship), **possible homonym** (same name but conflicting authorship, possibly a different
  name) and **possible fuzzy** (a similar spelling).
- **References**: **new**, **matched** (same DOI or same citation) and **possible** (a similar
  citation).
- **new accepted** and **new synonyms**: how many accepted names and synonyms would be added.
- **unanchored**: new names with no matched higher taxon in your tree. They would be added as new
  top-level taxa, so review them before you apply.

Only **matched** records are joined automatically. **Possible** matches are added as **new**
unless you confirm them. A yellow **Unreviewed possible matches** box warns you when any are left.

### Review the mapping

Use this to check and correct the matches before anything is applied.

1. Click **Review mapping**. Two tabs, **Names** and **References**, list every source record with
   its **Source**, **Category**, matched **Target** and **Score**.
2. Filter by category with the chips (**All**, **Matched**, **Possible homonym**, **Possible fuzzy**,
   **New**, or **Possible** for references).
3. Correct rows with the **Actions** buttons:
   - **Confirm**: accept a possible match as a real match.
   - **Reject**: a matched record is really a different name, so add it as new instead.
   - **Re-point**: match the record to a different name or reference in your project. Search for it
     in **Pick a target …**.
   - **Undo**: take back a correction you haven't saved yet.
4. Click **Save overrides** (or **Discard**). The impact counts update.

### Apply the merge

1. Choose a **Mode**. It decides what happens to **matched** records:

   | Mode | Matched records | New records |
   |---|---|---|
   | **Overwrite** | The source wins: its values replace yours, and its vernacular names, distributions etc. replace your sets for that name. | Added |
   | **Fill gaps** (default) | Your values are kept. Only empty fields are filled, and missing synonyms, relations and other records are added. | Added |
   | **New only** | Left untouched. | Added |

2. Decide on **Run in one transaction**:
   - **On** (default): all or nothing. If something fails, your project is left exactly as it was.
   - **Off**: the merge is saved in batches. Use this for very large merges. If it fails halfway,
     what was applied stays, and the result is still consistent. Start a new merge from the same
     source to finish: records that were already added now count as matched instead of being
     duplicated.

   Blixa switches this off for you for plans with more than 5,000 records ("large plan") and when
   every record is new ("full import — no transaction needed").

3. Click **Apply merge**. When it says **Merge complete.**, a badge shows how many records had
   problems. Click **Open target project**, **Start a new merge**, or **Close**.

### What a merge keeps

- **Matched names and references keep your ids**, so links and identifiers stay stable. The source
  record's id is recorded as an extra identifier on them.
- **New accepted names** are placed under their nearest matched higher taxon. For example, a new
  species goes under the matching genus in your tree.
- **New synonyms** of a matched accepted name are added to it.
- References are merged first, so new names point to the right (merged) references.
- Your project is revalidated afterwards. Check [Issues](/help/validation) and the
  [homotypic conflicts](/help/synonyms) for anything the merge brought in.

After a successful merge you can delete the staging project (**Project** → **Tools** →
**Delete project** in the staging project, owner only).

For small additions, a single genus or one species' synonymy from ChecklistBank,
[Import from ChecklistBank](/help/checklistbank#import-from-checklistbank) is quicker. It adds
records directly without matching.
