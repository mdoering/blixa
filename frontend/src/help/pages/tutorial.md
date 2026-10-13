# Tutorial: the Felidae playground

In about 20 minutes you will make your own copy of a practice checklist and fix real problems in
it: a missing author, a misspelt duplicate, a homotypic conflict and an invisible wrong-script
letter. Along the way you add a reference, start a discussion and export the result.

The playground is the extant cats (Felidae) from the Catalogue of Life — about 525 names and 230
references — with a handful of deliberate errors added. Every planted error says so in the
**Remarks** of the name. It is your private project, so nothing you do affects anyone else.

You need an approved Blixa account (see [Signing in & access](/help/access)).

## 1. Create your copy

**The quick way:**

1. In the sidebar, click **Projects**.
2. Click **Try it with demo data**.
3. Wait a few seconds. Blixa creates a private project called **Blixa playground – Felidae** (a
   second copy gets "(2)" added), makes you its owner, and opens its tree.

**Or import the file yourself** — useful to see how importing works:

1. Download [blixa-playground-felidae.zip](/help/demo/blixa-playground-felidae.zip).
2. On the **Projects** page, click **Import ColDP**.
3. Under **ColDP, DwC-A, or text-tree file**, select the downloaded zip. Leave **Title** empty to
   use the title from the archive (or type a different one if you already have a project with
   that name), and leave **Preserve source identifiers** off.
4. Click **Import**, wait for the summary (usages, references, authors), then click **Open
   imported project**.
5. In the sidebar, click **Tree**.

![The Import ColDP dialog](/help/img/import-modal.png)

## 2. Explore the tree

1. On the **Tree** page, expand the **Classification tree** by clicking the arrows: *Animalia* →
   *Chordata* → *Mammalia* → *Carnivora* → *Felidae*. Felidae has two subfamilies, *Felinae* (the
   small cats) and *Pantherinae* (the big cats).
2. Expand *Pantherinae* → *Panthera* and click *Panthera leo*. Its details open on the right:
   the name with authorship, rank and status, its parents, and tabs for its synonyms,
   vernacular names, distribution and more.
3. Click the **Synonyms** tab: *Felis leo* Linnaeus, 1758 is listed with **≡**, a homotypic
   synonym (the lion's basionym).
4. Switch on **Show unassessed** above the tree. Expand *Felinae* → *Leopardus*: three species
   appear in italics with an **Unassessed** badge — *Leopardus garleppi*, *L. munoai* and
   *L. narinensis*. They are provisionally accepted and await review; normally they are hidden
   from the tree. (One more unassessed name hides under *Felis*.) Leave them for now and switch
   **Show unassessed** off again.

![The tree with Panthera leo selected](/help/img/tree-overview.png)

Tip: the address bar now ends in `?usage=<number>` — a link straight to the selected name.

## 3. Add a missing authorship

The Iberian lynx has lost its author.

1. In the tree, expand *Felinae* → *Lynx* and click *Lynx pardinus*. Unlike its neighbours it has
   no author next to it, and the **Authorship** field on the **Details** tab is empty.
2. Click into **Authorship** and type `(Temminck, 1827)`. The parentheses are there because
   Temminck described it as *Felis pardina* — you can see that basionym on the **Synonyms** tab.
3. Click **Save**. "Saved" appears and the tree row now shows the author.

![Lynx pardinus without authorship](/help/img/taxon-details.png)

## 4. Demote a misspelt duplicate

Under *Panthera* there is an accepted *Panthera tigirs* — a typo for the tiger, *Panthera
tigris*. It should be a synonym, not a second tiger species.

1. In the tree, hover the *Panthera tigirs* row and click its **⋮** (or right-click the row).
2. Point at **Change status** and choose **Synonym**. The **Demote *Panthera tigirs* to a
   synonym** dialog opens, with **Synonym** selected.
3. In the small tree inside the dialog, expand *Animalia* → *Chordata* → *Mammalia* →
   *Carnivora* → *Felidae* → *Pantherinae* → *Panthera* and click ***Panthera tigris*** (not the
   greyed-out *tigirs*).
4. Click **Demote**. "Demoted to synonym" confirms it.
5. Click *Panthera tigris* in the tree and open its **Synonyms** tab: *Panthera tigirs* is now
   listed there.

![The Demote dialog](/help/img/demote-modal.png)

If the name you demote has children or synonyms of its own, the dialog also asks where they
should go. See [Synonyms & homotypy](/help/synonyms) for all options.

## 5. Resolve a homotypic conflict

*Felis concolor* Linnaeus, 1771 is the original name of the cougar, now *Puma concolor*
(Linnaeus, 1771). Both share one type, yet the playground has both as accepted species. Blixa can
find such conflicts for a whole family at once.

1. In the tree, hover the *Felidae* row, click its **⋮** and choose **Find homotypic
   conflicts**.
2. The **Homotypic conflicts** page lists every conflict in Felidae as a card. Find the card
   whose **Members** are *Felis concolor* Linnaeus, 1771 and *Puma concolor* (Linnaeus, 1771),
   both ACCEPTED.
3. Under **Keep as the single accepted name**, make sure *Puma concolor (Linnaeus, 1771)* is
   selected. Blixa pre-selects the name with the most descendants — here *Puma concolor* with
   its two subspecies.
4. Click **Consolidate**. *Felis concolor* becomes a homotypic synonym (≡) of *Puma concolor*
   and the card disappears.

![A homotypic conflict card](/help/img/homotypic-conflict-card.png)

Leave the other cards alone for now (**Skip** only hides a card until the next scan). One of them
is the duplicated *Lynx rufus*, a job for merging (see [What next](#what-next)); the others come
from the Catalogue of Life data itself and need a taxonomist's judgement.

## 6. Fix a validation issue

Blixa checks every name automatically. One of the findings is a name that looks perfect but
isn't.

1. In the sidebar, click **Issues**. The badges at the top summarise the findings; most are
   *info* notes about names without a publication reference.
2. In the **Any severity** filter, choose **warning**.
3. Find the row **suspicious_name_characters** — "the scientific name contains homoglyph
   (look-alike, wrong-script) characters" — and click its entity link (*name_usage #…*). The
   **Names** page opens with *Panthera pardus orientalis* selected.
4. The name looks fine, but its second "a" in *orientalis* is a Cyrillic letter that only looks
   like a Latin "a". Such characters break searching and matching. In the **Scientific name**
   field, select the whole name, type `Panthera pardus orientalis` afresh, and click **Save**.
5. Go back to **Issues** and click **Revalidate**. After "Revalidated", the
   suspicious_name_characters row is gone.

![The Issues page](/help/img/issues.png)

Blixa rechecks a name automatically whenever you save it; **Revalidate** rechecks the whole
project in one go. See [Validation issues](/help/validation).

## 7. Add a reference by DOI

Next, add a recent paper on tiger subspecies and attach it to the tiger.

1. In the sidebar, click **References**, then **Import DOI**.
2. In **DOI**, enter `10.1126/sciadv.1400175` and click **Fetch**. Blixa looks the DOI up at
   Crossref (or DataCite) and opens a **New reference** form filled in with the authors, year,
   title and journal of Wilting et al. (2015), *Planning tiger recovery: Understanding
   intraspecific variation for effective conservation*, Science Advances.
3. Check the fields — the **Citation** at the top is generated from them — and click **Create**.
4. Go to **Tree** (or **Names**) and select *Panthera tigris*. Open its **References** tab.
5. In **Add existing reference**, start typing `Planning tiger`, pick the reference and click
   **Add**. It is now listed as a reference of *Panthera tigris*.

To record where a name was originally published, use **Published in reference** on the
**Details** tab instead. See [References](/help/references).

## 8. Start a discussion

Discussions keep the reasoning behind changes next to the data. Let's note the cougar fix.

1. In the sidebar, click **Discussions**, then **New discussion**.
2. **Title:** `Cougar: Felis concolor consolidated`.
3. In **Body**, type `Kept ` and then `#Puma`. A list of matching names pops up — choose
   **Puma concolor**. Blixa inserts a stable reference like `#123` that will always point at that
   name, even if it is renamed later.
4. Continue typing, e.g. ` as accepted; please check, ` and then type `@`. Your project's members
   appear — choose your own username (in the playground you are the only member).
5. Click **Create**, then click the new discussion in the list to open it. The name and the
   person are shown as links.
6. Open *Puma concolor* on the **Tree** page and click its **Discussions** tab — your discussion
   is listed there.

When a colleague mentions you with `@` in a comment, or comments on a discussion you started or
follow, it shows up under **Needs attention** on your Dashboard. Open discussions can also be chosen as your work objective in the header, so that your changes
are tagged with them. See [Work together](/help/collaborate).

## 9. Export your checklist

1. In the sidebar, click **Project**, then the **Tools** tab.
2. Under **Export ColDP**, click the **Export ColDP** button.
3. After "Exporting…" the file name and size appear, with counts of usages and references. Click
   **Download** to save the ColDP zip.

![The Tools tab](/help/img/project-tools.png)

The archive contains your corrected checklist in the same ColDP format you started from. See
[Publish, release & export](/help/publish) for numbered releases and public projects.

## What next

The playground still has planted problems for you to fix, each covered by a how-to page:

| Problem | Where to find it | How to fix it |
|---|---|---|
| *Lynx rufus* is in the checklist twice | two `duplicate_name` warnings; a homotypic conflict | [Merge duplicates & projects](/help/merge) |
| *Leptailurus serval* is filed under *Felis* | four `genus_mismatch` warnings | Move it under *Leptailurus* — [Build the classification](/help/build-the-tree) |
| *Leo leo* is a synonym of a synonym (*Felis leo*) | a `synonym_of_non_accepted` error | Point it at *Panthera leo* — [Synonyms & homotypy](/help/synonyms) |
| *Felis sp. "Bornean form"* is an informal placeholder | an `unparsable_name` warning | [Edit names & taxon data](/help/edit-names) |
| Three *Leopardus* species are only provisionally accepted | **Show unassessed** in the tree | [Build the classification](/help/build-the-tree) |
| *Johnson et al. 2006, Science* has no DOI | the **DOI** column on **References** | Use **Find DOI…** — [References](/help/references) |

Beyond the planted errors, the data has real findings from the Catalogue of Life: hundreds of
names without a publication reference, and homotypic conflicts around *Felis melli* and *Felis
lybica*. Try [Use ChecklistBank & COL](/help/checklistbank) to compare names with the current
Catalogue of Life.

When you are done, you can delete the playground under **Project → Tools → Delete project**, or
simply create a fresh copy with **Try it with demo data**.
