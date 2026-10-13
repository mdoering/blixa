# Synonyms & homotypy

In Blixa only **accepted** names sit in the tree. A **synonym** points to one or more accepted
names and has no place in the tree of its own. A **misapplied** name works the same way, but
records that a name was wrongly used for the taxon. Synonyms can't point to other synonyms, and
can't have children or synonyms of their own.

All changes on this page need the **owner** or **editor** role.

## The Synonyms tab

Use this to see an accepted name's full synonymy.

Select an accepted name and open its **Synonyms** tab. The synonymy is sorted by type, like in a
printed revision:

- **≡** names at the top are *homotypic* with the accepted name. They share its type, for example
  its basionym and other recombinations.
- Each **=** name starts a *heterotypic* group, a name with a different type. Its own
  recombinations are indented below it with **≡**.
- Misapplied names come last, marked **=**.

![The Synonyms tab of Panthera leo, with its basionym Felis leo](/help/img/synonyms-tab.png)

Click a synonym to open it on the **Names** page, where you can edit its details, type material
and relations. On a synonym's own record, the line under the name reads **Synonym of** (or
**Misapplied for**) followed by its accepted name. Click that name to go back.

Blixa only nests names it knows to be homotypic, either because you grouped them (see
[Group synonyms](#group-synonyms)) or because a **basionym** or **homotypic** relation exists on
the [Relations](/help/edit-names#relations) tab. Until then, every synonym shows as its own **=**
group.

## Add synonyms

Use this when you need to record new synonyms for an accepted name.

**One synonym:**

1. Open the accepted name's row menu (right-click or **⋮**) in the tree and choose **Add synonym**.
2. In **Synonym of …**, enter the **Scientific name**, **Authorship** and **Rank**.
3. Click **Create**.

**Several at once:**

1. On the accepted name's **Synonyms** tab, click the **⋯** menu (*Synonym actions*) and choose
   **Add synonyms…**.
2. Enter one name per line, with its authorship. For example, on *Lynx pardinus*:

   ```
   Lynx pardellus Miller, 1907
   ```
3. Click **Preview**, check the list, and click **Insert N names**.

**Bulk add…** in the tree's row menu, with **As synonyms of target** selected, does the same. See
[Bulk add names](/help/build-the-tree#bulk-add-names).

## Demote a name

Use this when an accepted name turns out to be a synonym of another accepted name, for example a
misspelling or a junior synonym.

![Demote Panthera tigirs to a synonym](/help/img/demote-modal.png)

1. Open the name's row menu and choose **Change status → Synonym**, or **→ Misapplied**.
2. In **Demote … to a synonym**, check the **Synonym** / **Misapplied** switch at the top.
3. In the small tree, pick the accepted name it becomes a synonym of. The name you are demoting
   and everything below it are greyed out.
4. If the name has accepted children, choose where they go under **Its N accepted children —
   move to:**
   - **The new accepted name**: they move under the name you picked.
   - **Its former parent**: they move up one level.
5. If the name has synonyms of its own, choose what happens to them:
   - **Re-point to the new accepted**: they become synonyms of the name you picked.
   - **Set unassessed**: they are unlinked and become *unassessed*. A synonym that is still linked
     to another accepted name keeps its status.
6. Click **Demote**.

The name leaves the tree and shows up on the new accepted name's **Synonyms** tab. Its taxon data
(vernacular names, distributions, media, estimates and biology) is removed, because a synonym
can't carry taxon data. Copy anything you want to keep to the accepted name first.

**Example (F2):** the playground has *Panthera tigirs*, a misspelt accepted duplicate of the
tiger. Demote it with **Change status → Synonym**, picking *Panthera tigris* in *Felidae >
Pantherinae > Panthera*.

## Promote a synonym

Use this when a synonym deserves to be an accepted taxon again.

1. Find the synonym, for example on the **Names** page or via the **Synonyms** tab of its accepted
   name.
2. Open its row menu and choose **Change status → Accepted**.
3. In **Promote … to accepted**, choose **Under a parent** and pick its parent in the tree, or
   choose **Make it a root**.
4. If it is a [pro parte](#pro-parte-synonyms) synonym, Blixa lists its accepted names under
   **Keep any as separate synonyms**. Tick the ones where the name should stay a synonym. For each
   ticked name Blixa creates a separate synonym copy of the name. The promoted name itself is no
   longer a synonym anywhere.
5. Click **Promote**.

## Misapplied names

Use this to record that a name was wrongly applied to a taxon in the literature.

- To add one, [add a synonym](#add-synonyms) and then switch its **Status** on the Details tab
  from *Synonym* to *Misapplied* and click **Save**.
- To turn an existing synonym into a misapplied name, or back, use **Change status** in its row
  menu or the **Status** field. This is a plain switch, and the name stays attached to the same
  accepted name.
- To turn an accepted name into a misapplied name, [demote](#demote-a-name) it and choose
  **Misapplied**.

## Pro parte synonyms

Use this when a name is a synonym of more than one accepted taxon, for example after a species
was split.

1. Open the synonym's row menu (on the **Names** page) and choose **Add accepted name…**.
2. Pick the additional accepted name in the tree.
3. Click **Link**.

The name now shows **Synonym of** with several accepted names, and appears on each of their
**Synonyms** tabs.

## Change accepted name

Use this when a synonym is attached to the wrong accepted name.

1. Open the synonym, for example from the **Names** page.
2. In the **Synonym of** line under the name, click the **⇄** icon (*Change accepted name*).
3. Pick the correct accepted name in the tree.
4. Click **Change**.

For a pro parte synonym, this replaces its first accepted name. Use
[Add accepted name…](#pro-parte-synonyms) to add another one instead.

### Example: a synonym of a synonym (F7)

In the playground, *Leo leo* points to *Felis leo*, which is itself a synonym. Blixa flags this as
a `synonym_of_non_accepted` error, and the **Synonym of** line shows *Felis leo* with a red
**Synonym** badge.

1. On the **Names** page, search for `Leo leo` and click it.
2. Click the **⇄** icon in the **Synonym of** line.
3. In the tree, expand *Felidae > Pantherinae > Panthera* and click *Panthera leo*.
4. Click **Change**.

*Leo leo* now appears on the **Synonyms** tab of *Panthera leo*, and the error goes away once
validation has rerun.

## Group synonyms

Use this to let Blixa find which synonyms of an accepted name share a type, so the synonymy
nests correctly.

1. On the accepted name's **Synonyms** tab, click **⋯** and choose **Group synonyms**.
2. **Group synonyms homotypically** lists the relations Blixa proposes, such as *Panthera leo
   basionym Felis leo*. Blixa finds them by matching the final epithet and the authors: a
   recombination's bracketed authors, as in *(Linnaeus, 1758)*, point to its basionym.
3. Untick any proposal you disagree with. Relations that already exist are shown as **(already
   linked)**.
4. Click **Apply**.

The relations are saved on the names' [Relations](/help/edit-names#relations) tabs, where you can
also add or correct them by hand.

## Homotypic conflicts

Names that share a type must end up under a single accepted name. If they don't, for example a
basionym is still accepted next to its recombination, that is a **homotypic conflict**. Use this
check to scan a whole family or order at once.

![The Homotypic conflicts page for Felidae](/help/img/homotypic-conflicts.png)

1. In the tree, open the row menu of the group to check (for example *Felidae*) and choose **Find
   homotypic conflicts**.
2. The **Homotypic conflicts** page shows one card per conflict. **Members** lists the names that
   share a type, with their status. A yellow **pro parte / dual status — review** badge means the
   card involves a pro parte synonym, or the same name both as accepted and as a synonym. Both can
   be legitimate, so look closely.
3. Under **Keep as the single accepted name**, choose the name to keep. Blixa preselects the one
   with the most descendants, so the fewest names have to move.
4. Click **Consolidate**. Every other accepted member is demoted to a synonym of the name you
   kept, with its children and synonyms moved over. Synonym members are re-pointed to it, and the
   homotypic relations are saved. The card then disappears.

   Or click **Skip** to leave the conflict alone for now. It comes back the next time you run the
   scan.

If the scan finds nothing, the page says *No homotypic conflicts found in this subtree.*

![A homotypic conflict between Felis concolor and Puma concolor](/help/img/homotypic-conflict-card.png)

**Example (F4):** in the playground, *Felis concolor* Linnaeus, 1771 is accepted under *Felis*. But
it is the basionym of the cougar, *Puma concolor* (Linnaeus, 1771). Run **Find homotypic
conflicts** on *Felidae*, keep *Puma concolor* on its card and click **Consolidate**. *Felis
concolor* becomes a homotypic synonym (**≡**) of *Puma concolor*.

The same scan also shows two conflicts that come from the Catalogue of Life data itself: *Felis
melli* listed under two accepted names, and *Felis lybica* next to *Felis lybica lybica*. Decide
those on their merits, or skip them.
