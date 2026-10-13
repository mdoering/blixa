# Blixa playground dataset — Felidae

A practice dataset for the user documentation (`/help/tutorial`) and the **Try it with demo data**
button on the Projects page. It is the extant cats (Felidae) from the latest Catalogue of Life
release in ChecklistBank (dataset `3LXR`), plus a handful of deliberate errors to find and fix.

| | |
|---|---|
| Names | ~526 usages (Animalia → Carnivora → Felidae, 2 subfamilies, 15 genera, ~47 species, ~68 subspecies, ~385 synonyms) |
| Also | ~230 references, vernacular names (en/de/fr/es), distributions |
| License | CC BY 4.0 (from COL; attribution in `metadata.yaml`) |

## Files

- `felidae/` — the ColDP folder (`metadata.yaml`, `NameUsage.tsv`, `Reference.tsv`,
  `VernacularName.tsv`, `Distribution.tsv`), kept unzipped so changes are reviewable.
- `fetch-felidae.py` — rebuilds `felidae/` from the ChecklistBank API (stdlib only):
  `python3 -I demo/fetch-felidae.py`
- `build.sh` — zips `felidae/` into `frontend/public/help/demo/blixa-playground-felidae.zip`,
  which is what the docs link to and the demo button imports. **Commit both.**

## Simplifications vs. COL

To keep the Issues page focused on the deliberate flaws, `fetch-felidae.py` drops synonyms whose
rank differs from their accepted name (mostly subspecies names sunk into a species) and clears the
authorship of autonyms (e.g. *Panthera leo leo*). Fossil taxa are excluded.

## Deliberate flaws

Added rows have IDs starting `PG`; every flawed name says so in its *Remarks*. Keep this table, the
`PLAYGROUND FLAWS` section of `fetch-felidae.py` and the help pages in sync.

| # | Flaw | Shows up as | Fixed in the docs by |
|---|---|---|---|
| F1 | *Lynx pardinus* has no authorship | — (spot it in the tree) | tutorial: edit a name |
| F2 | *Panthera tigirs* — misspelled duplicate of the tiger, accepted | — | tutorial: demote to a synonym of *Panthera tigris* |
| F3 | *Lynx rufus* entered twice | `duplicate_name` ×2, homotypic conflict | merge.md: merge selected names |
| F4 | *Felis concolor* Linnaeus, 1771 accepted under *Felis* — the cougar's basionym | homotypic conflict with *Puma concolor* | tutorial / synonyms.md: homotypic conflicts |
| F5 | *Leptailurus serval* filed under *Felis* | `genus_mismatch` ×4 (species + subspecies) | build-the-tree.md: move |
| F6 | *Panthera pardus orient**а**lis* contains a Cyrillic `а` (U+0430) | `suspicious_name_characters` | tutorial: fix a validation issue |
| F7 | *Leo leo* is a synonym of a synonym (*Felis leo*) | `synonym_of_non_accepted` (error) | synonyms.md: change accepted name |
| F8 | *Felis sp. "Bornean form"* — informal placeholder | `unparsable_name` | edit-names.md |
| F9 | *Leopardus garleppi*, *L. munoai*, *L. narinensis* provisionally accepted | unassessed (hidden from the tree by default) | build-the-tree.md: unassessed names |
| F10 | Reference *Johnson et al. 2006, Science* without its DOI | — | references.md: Find DOI |

Real (not planted) findings that also appear: ~330 `missing_published_in` and ~60
`genus_year_after_species` infos, and two
homotypic conflicts from COL itself (*Felis melli* under two accepted names; *Felis lybica* vs.
*Felis lybica lybica*).
