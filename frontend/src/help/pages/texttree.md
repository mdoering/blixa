# TextTree syntax

TextTree is a plain-text format for taxonomic trees, defined by GBIF
([gbif/text-tree](https://github.com/gbif/text-tree)). Each line holds one name, and indentation
shows the hierarchy. It is easy to type, easy to read, and easy to paste from a spreadsheet or an
email.

Blixa uses TextTree in three places:

- **Bulk add…** pastes or uploads a list of names under an accepted taxon, up to 1,000 names at
  a time. See [Bulk add names](/help/build-the-tree#bulk-add-names).
- **Import ColDP** on the Projects page accepts a TextTree file (`.txtree`, `.tree`, `.txt` or
  `.tsv`) and turns it into a new project. See [Import formats](/help/import-formats#texttree).
- **Download subtree** on the Tree page saves the selected taxon and everything below it as a
  `.txtree` file.

Blixa reads a subset of the full TextTree format. This page explains which parts it reads and
which it ignores.

## A first example

```
Panthera Oken, 1816 [genus]
  Panthera leo (Linnaeus, 1758) [species]
    = Felis leo Linnaeus, 1758
  Panthera tigris (Linnaeus, 1758) [species]
    = Felis tigris Linnaeus, 1758
    Panthera tigris tigris [subspecies]
```

This adds the genus *Panthera* with two species. Each species has one synonym, and the tiger also
has one subspecies.

![Bulk add modal with a TextTree](/help/img/bulk-add-modal.png)

## Lines and names

- Put **one name per line**. Save files as UTF-8.
- You can write the **authorship** after the name, as in the examples on this page. Blixa parses
  the authors and year, but it stores the whole text in the **Scientific name** field and leaves
  **Authorship** empty. If you need the authorship in its own field, move it there afterwards (see
  [The Details tab](/help/edit-names#the-details-tab)), or leave authorship out of the TextTree.
- **Empty lines** are skipped. A line that contains **only spaces** is rejected as an error, so
  delete such lines.
- A name must be at least two characters long.

## Indentation

- Indent with **exactly two spaces** per level. Tabs are not allowed.
- To make a line a child of the line above, indent it one level (two spaces) deeper. To go back
  up, indent it less. You can go back up several levels at once.
- You can't skip a level. A line indented four spaces deeper than the line above is rejected.
- Lines with no indentation are the top level. In **Bulk add…** they become direct children of
  the taxon you opened the modal on. On import, they become the top-level taxa of the new
  project.

If the indentation is wrong, Blixa names the line it couldn't read, for example *Tree is not
indented properly on line 4. Use 2 spaces only* or *Use 2 spaces for children*.

## Ranks

- Write the rank in square brackets after the name, for example `Lynx Kerr, 1792 [genus]`.
- Write ranks in **lower case**. A space, `-` or `_` inside the brackets is fine, for example
  `[infraspecific name]`. Upper-case letters stop the brackets from being read as a rank, so
  `[Genus]` ends up as part of the name.
- Use the usual English rank names: `kingdom`, `phylum`, `class`, `order`, `family`, `subfamily`,
  `tribe`, `genus`, `subgenus`, `species`, `subspecies`, `variety`, `form` and so on. Blixa
  ignores a rank word it doesn't know.
- If there's no rank, Blixa lets the name parser infer one. A binomial becomes a species and a
  trinomial a subspecies. If the parser can't decide, the name is stored as *unranked*. **Always
  give the rank for names above species level.**

## Synonyms

- Put `=` before a synonym and indent it one level under its accepted name. The space after `=`
  is optional.
- `≡` (homotypic synonym) and the older `*` also mark a synonym. Blixa reads `≡` as an ordinary
  synonym and doesn't record that it is homotypic. To record homotypy, use **Group synonyms**
  afterwards (see [Group synonyms](/help/synonyms#group-synonyms)).
- A synonym can't have children or synonyms of its own. Blixa **ignores anything indented under
  a synonym**.
- A `=` line at the top level isn't treated as a synonym: it is added as an accepted name.
- TextTree has no marker for misapplied names. Add them as synonyms, then change their status in
  Blixa.

In **Bulk add…** with **As synonyms of target** (or **Add synonyms…** on the Synonyms tab), paste a
flat list of names with no indentation and no `=`. Every line becomes a synonym of the target
taxon. If you indent a line, Blixa rejects the list: *Synonymy mode requires a flat list of names
(no indentation)*.

## Other markers

Markers go right before the name. You can combine them, for example `=†`.

| Marker | Meaning in TextTree | What Blixa does |
|---|---|---|
| `†` | extinct | Sets **Extinct** on the taxon |
| `$` | basionym | Ignored (the line is still read) |
| `?` | provisional | Ignored. The name is added as **accepted**, not unassessed |
| `{KEY=value}` after the name | extra information | Ignored |
| `# text` after the name | comment | Ignored |

**Comments** must come after a name, with at least one space before the `#`. **A line that
starts with `#` is not a comment**: Blixa adds it as a name. Put notes at the end of a name line
instead.

## Download subtree

Use this when you want a quick, readable copy of part of your tree, or want to move a branch into
another project.

1. Open **Tree** and select an accepted taxon, for example *Panthera*.
2. Click **Download subtree** above the taxon details.

Any project member can download, including viewers. The file is named
`project-<project id>-subtree-<taxon id>.txtree` and contains:

- the selected taxon and all **accepted** taxa below it, each with its authorship and `[rank]`.
  Unassessed names are left out.
- the synonyms **and misapplied names** of each accepted taxon, written as `=` lines before its
  children. Blixa doesn't write `≡`, `†` or any other marker yet.
- the children of each taxon, in tree order.

You can paste the file straight into **Bulk add…** or import it with **Import ColDP**.

## Complete example: Felidae

This tree adds a family with three subfamilies. It includes synonyms, an extinct subfamily and
extinct species, and comments. Paste it into **Bulk add…** on an order such as *Carnivora*, or save
it as `felidae.txtree` and import it.

```
Felidae Fischer de Waldheim, 1817 [family]
  Felinae Fischer de Waldheim, 1817 [subfamily]
    Lynx Kerr, 1792 [genus]
      Lynx lynx (Linnaeus, 1758) [species]
        = Felis lynx Linnaeus, 1758
      Lynx pardinus (Temminck, 1827) [species]   # Iberian lynx
        = Felis pardina Temminck, 1827
  Pantherinae Pocock, 1917 [subfamily]
    Neofelis Gray, 1867 [genus]
      Neofelis nebulosa (Griffith, 1821) [species]
        = Felis nebulosa Griffith, 1821
    Panthera Oken, 1816 [genus]
      = Uncia Gray, 1854
      Panthera leo (Linnaeus, 1758) [species]
        = Felis leo Linnaeus, 1758
      †Panthera spelaea (Goldfuss, 1810) [species]   # cave lion
        = Felis spelaea Goldfuss, 1810
      Panthera tigris (Linnaeus, 1758) [species]
        = Felis tigris Linnaeus, 1758
        = Tigris regalis Gray, 1867
        Panthera tigris tigris [subspecies]
      Panthera uncia (Schreber, 1775) [species]
        = Felis uncia Schreber, 1775
  †Machairodontinae Gill, 1872 [subfamily]
    †Smilodon Lund, 1842 [genus]
      †Smilodon fatalis (Leidy, 1868) [species]
```

The autonym *Panthera tigris tigris* has no authorship on purpose. An autonym takes the species'
author, and giving it its own author triggers the `superfluous_authorship` warning (see
[Validation rules](/help/validation-rules)).
