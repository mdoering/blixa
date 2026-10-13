# Build the classification

The **Tree** page shows your project's classification. Only accepted names, and optionally
unassessed ones, appear as tree nodes. Synonyms hang off their accepted name and are listed on its
[Synonyms tab](/help/synonyms#the-synonyms-tab).

To change the tree you need the **owner** or **editor** role. Viewers can browse it, but they
don't get the **New name** button or the row actions menu.

![The Tree page with Panthera leo selected](/help/img/tree-overview.png)

## Browse the tree

Use this to find your way around the classification.

- Click the arrow next to a name to expand it. Blixa loads children only when you expand a node,
  so even very large checklists open quickly.
- A node with many children shows its first 50. Click **Load N more** at the bottom of the list
  to fetch the next batch. Top-level names work the same way with **Load more**.
- Click a name to select it. Its details open on the right, and the tree expands to show it. The
  selection is part of the page address, so you can bookmark or share a link to a taxon. The
  selection is also kept when you switch to the **Names** page and back.
- The classification bar above the detail tabs (for example *… > Carnivora > Felidae >
  Pantherinae > Panthera*) shows the selected taxon's closest ancestors. Click any of them to jump
  there.
- An orange lock icon on a row means someone else is editing that name right now. See
  [Work together](/help/collaborate).
- Use the **<** button at the top of the detail pane to collapse the tree and give the form more
  room.

## The row actions menu

Most tree edits start from a row's actions menu. **Right-click** a row, or hover over it and click
the **⋮** button at its right end.

![The row actions menu on Panthera](/help/img/tree-row-menu.png)

What the menu offers depends on the name's status:

| Item | Shown for | What it does |
|---|---|---|
| **Add child** | accepted | [Add a single name](#add-a-name) below this one |
| **Add synonym** | accepted | [Add a synonym](/help/synonyms#add-synonyms) of this name |
| **Move…** | accepted | [Move it](#move-a-taxon) under another parent |
| **Import from ChecklistBank…** | accepted | [Copy a subtree from ChecklistBank](/help/checklistbank#import-from-checklistbank) |
| **Bulk add…** | accepted | [Paste many names at once](#bulk-add-names) |
| **Find homotypic conflicts** | accepted | [Scan the subtree for homotypic conflicts](/help/synonyms#homotypic-conflicts) |
| **Add accepted name…** | synonym, misapplied | [Make it pro parte](/help/synonyms#pro-parte-synonyms) |
| **Change status** | all | Switch status. Accepted to synonym opens [Demote](/help/synonyms#demote-a-name), synonym to accepted opens [Promote](/help/synonyms#promote-a-synonym). An unassessed name must be accepted before it can be demoted, and a synonym promoted before it can be made unassessed |
| **Accept subtree…** | accepted, unassessed | [Accept every unassessed name below](#accept-unassessed-names) |
| **Delete** | all | [Delete the name](#delete-a-name) |

The same menu appears on each row of the **Names** page, so you can also act on synonyms there.

## Add a name

Use this when you need to add one taxon to the tree.

1. Choose where the new name goes:
   - To add it **under an existing taxon**, open that taxon's row menu and choose **Add child**,
     or select the taxon and click **New name** at the top right.
   - To add it **at the top level** (for example a new kingdom), make sure nothing is selected
     and click **New name**. The dialog is then titled *New root name*.
2. In the dialog (titled *Child of …* when you add a child), enter the **Scientific name** without
   its authorship, for example `Panthera spelaea`.
3. Enter the **Authorship**, for example `(Goldfuss, 1810)`.
4. Pick the **Rank**.
5. Click **Create**. The new name is created as *accepted* and selected so you can fill in its
   [details](/help/edit-names).

**New name** on the **Names** page always creates a top-level name. Use **Move…** afterwards, or
start from the tree.

## Bulk add names

Use this to add many names at once, such as a genus with all its species and their synonyms.

![Bulk add under Panthera](/help/img/bulk-add-modal.png)

1. Open the row menu of the parent taxon (for example *Panthera*) and choose **Bulk add…**.
2. Choose **As accepted children** to add the names below the taxon, or **As synonyms of target**
   to add them all as synonyms of it.
3. Type or paste the names into **Names**, one per line, in [TextTree](/help/texttree) format:
   - `[species]` at the end of a line sets the rank,
   - indenting by two spaces nests a name under the line above,
   - a leading `=` marks a synonym of the name above it,
   - `†` marks an extinct taxon.

   You can also load a `.txt`, `.tsv`, `.txtree` or `.tree` file with **Upload file…**.
4. Click **Preview**. Blixa parses the text and shows the resulting tree with counts of
   **accepted** names and **synonyms**. Names that already exist in the project are marked
   **exists** and counted under **already exist**. They are still inserted, so remove them from
   the text if you don't want duplicates. If the text can't be parsed, a **Cannot parse** box
   explains why.
5. Click **Insert N names**. Blixa inserts all of them in one go: either every name is added, or
   none are.

For example, this adds the cave lion and its basionym under *Panthera*:

```
Panthera spelaea (Goldfuss, 1810) [species]
  = Felis spelaea Goldfuss, 1810
```

## Move a taxon

Use this when a taxon sits under the wrong parent. A moved taxon takes all its children and
synonyms with it.

1. Select the taxon and open its row menu, then choose **Move…**. Or click the **⇄** icon
   (*Move to another parent*) at the end of the classification bar above the detail tabs.
2. Choose **Under a parent** and pick the new parent in the small tree. The taxon you are moving
   and everything below it are greyed out, so you can't create a loop. The chosen parent appears
   below the tree as **New parent:**.

   Or choose **Make it a root** to take the taxon out of its parent and put it at the top level.
3. Click **Move**.

If the move isn't allowed, for example because the new parent isn't accepted, the reason shows in
red and the dialog stays open so you can pick another parent.

### Example: put the serval in its own genus

In the playground, *Leptailurus serval* (flaw F5) is filed under *Felis*. That's why it and its
subspecies get four `genus_mismatch` issues.

1. In the tree, expand *Felidae > Felinae > Felis* and select *Leptailurus serval*.
2. Open its row menu and choose **Move…**.
3. Under **Under a parent**, expand down to *Felinae* and click the genus *Leptailurus*.
4. Click **Move**.

The species and its three subspecies now sit under *Leptailurus*. The `genus_mismatch` issues go
away the next time validation runs. If they don't, click **Revalidate this group** (the circular
arrows) on the taxon. See [Validation issues](/help/validation#revalidate).

## Unassessed names

An **unassessed** name is provisionally accepted. It sits in the tree but still needs a curator's
decision. Importing a file with *provisionally accepted* names creates them, and you can set a name to
unassessed yourself.

![The tree with Show unassessed switched on](/help/img/unassessed-tree.png)

By default the tree shows only the accepted backbone. To see unassessed names, switch on **Show
unassessed** at the top of the page. They then appear in italics with a blue **Unassessed** badge.
When you open an unassessed name from elsewhere, for example from the Issues page, the switch turns
on by itself.

Some rules:

- An unassessed name can't have accepted children. It can have other unassessed names below it.
- To change a name between accepted and unassessed, use **Change status**, or the **Status** field
  on its [Details](/help/edit-names#the-details-tab) tab.
- An unassessed name can't be turned into a synonym directly. Accept it first, then
  [demote](/help/synonyms#demote-a-name) it.

### Accept unassessed names

Use this when you have reviewed a provisional subtree and want to accept it.

1. Switch on **Show unassessed**.
2. Open the row menu of the topmost name you want to accept (an unassessed name), or of an accepted
   name that has unassessed names below it.
3. Choose **Accept subtree…** and confirm with **Accept**. Blixa accepts the name and every
   unassessed name below it, parents before children, and reports how many names were accepted.

To accept a handful of scattered names, you can also go to the **Names** page, set the **Status**
filter to *Unassessed*, tick the names and click **Mark N as Accepted**. See
[Edit names](/help/edit-names#change-many-statuses-at-once).

**Example (F9):** the playground has three provisionally accepted species of *Leopardus*:
*L. garleppi*, *L. munoai* and *L. narinensis*. Switch on **Show unassessed**, expand
*Leopardus*, and decide for each: accept it with **Change status → Accepted**, or
[delete](#delete-a-name) it.

## Delete a name

Use this to remove a name, and optionally everything that depends on it. Deleting can't be undone.

1. Open the name's row menu and choose **Delete**.
2. In **Delete name**, choose how much to delete:
   - **This taxon only**: removes just this name. Its synonyms stay in the project but are no
     longer attached to any accepted name, and they will show up as validation issues.
   - **This taxon and its synonyms**: also removes the names listed on its Synonyms tab.
   - **Entire subtree (this taxon, its descendants, and their synonyms)**: removes the whole branch.

   For a synonym, only **This taxon only** is offered.
3. If the taxon has accepted children and you didn't choose the entire subtree, choose where the
   children go:
   - **Its parent (default)**: the children move up one level.
   - **Another taxon…**: search for a new parent with **Search for a new parent…**.
4. Click **Delete**.

Removed names stay visible on the [History](/help/collaborate) page.

## Download a subtree

Use this to get a branch of the tree as a text file, for example to share it, or to edit it
offline and bulk-add it elsewhere.

1. Select the taxon at the top of the branch.
2. Click **Download subtree** above the detail pane.

You get a [TextTree](/help/texttree) file of the accepted taxa below the selected one, with each
taxon's synonyms nested under it. Any project member, including viewers, can download. To export
the whole checklist in ColDP format, see [Publish, release & export](/help/publish).
