# Validation rules

Blixa checks every name against the rules below and lists the problems it finds on the **Issues**
page and on each taxon's **Issues** tab. For when checks run and how to review issues, see
[Validation issues](/help/validation).

![Issues page](/help/img/issues.png)

## Severities

| Severity | Meaning |
|---|---|
| **error** | The data is broken and should be fixed, for example a synonym with no accepted name. |
| **warning** | Something looks wrong and is worth checking, though it can be intentional. |
| **info** | A hint. Often fine to leave as it is. |

If a finding is intentional, **Reject** it on the Issues page instead of changing the data (see
[Review an issue](/help/validation#review-an-issue)).

## Rules

The **Rule** column shows the key as it appears on the Issues page.

| Rule | Severity | What it means | Typical fix |
|---|---|---|---|
| `synonym_without_accepted` | error | A synonym or misapplied name isn't linked to any accepted name. | Link it with **Add accepted name…**, or change its status. See [Synonyms & homotypy](/help/synonyms#change-accepted-name). |
| `synonym_of_non_accepted` | error | A synonym or misapplied name points to a name that is itself a synonym, misapplied or unassessed. Example: *Leo leo* → *Felis leo* in the playground. | Re-point it to the accepted name. See [Synonyms & homotypy](/help/synonyms#example-a-synonym-of-a-synonym-f7). |
| `dangling_reference` | error | One of the taxon's references no longer exists. | Remove it on the taxon's **References** tab. See [References](/help/references#link-references-to-names). |
| `duplicate_name` | warning | Another name in the project has the same scientific name and authorship. Example: *Lynx rufus* entered twice. | Merge the duplicates. See [Merge duplicates & projects](/help/merge#merge-selected-names). |
| `genus_mismatch` | warning | An accepted species or subspecies has a genus in its name that differs from the genus it sits under. Example: *Leptailurus serval* under *Felis*. | Move it to the right genus, or make a new combination. See [Build the classification](/help/build-the-tree#move-a-taxon). |
| `species_epithet_mismatch` | warning | An accepted subspecies (or variety, form…) has a specific epithet that differs from the species it sits under. | Move it under the right species. See [Build the classification](/help/build-the-tree#move-a-taxon). |
| `infraspecific_missing_species` | warning | An accepted subspecies (or lower) sits directly under a genus or higher rank, with no species in between. | Move it under its species. See [Build the classification](/help/build-the-tree#move-a-taxon). |
| `rank_vs_parent` | warning | A name's rank isn't below its parent's rank, for example a genus under a species. Unranked names are skipped. | Fix the rank, or move the name. See [Build the classification](/help/build-the-tree#move-a-taxon). |
| `binomial_above_genus` | warning | A name with genus rank or higher has a specific epithet, so the rank and the name disagree. | Correct the **Rank** or the name. See [Edit names & taxon data](/help/edit-names#the-details-tab). |
| `missing_genus` | warning | A name has a specific epithet but no genus. | Correct the name. See [Edit names & taxon data](/help/edit-names#the-details-tab). |
| `uppercase_epithet` | warning | A specific or infraspecific epithet contains a capital letter. Epithets are always lower case. Cultivar epithets are exempt. | Correct the name. See [Edit names & taxon data](/help/edit-names#the-details-tab). |
| `unparsable_name` | warning | The name parser couldn't fully parse the name (the message shows its state), so the name's parts may be missing. Informal names often trigger this. Example: *Felis sp. "Bornean form"*. | Correct the name. If it's intentional, reject the issue. See [Edit names & taxon data](/help/edit-names#parsed-name). |
| `suspicious_name_characters` | warning | The scientific name contains look-alike characters from another script (homoglyphs, such as a Cyrillic `а` instead of a Latin `a`), or stray diacritic marks such as ´ or ¨. Accented letters are not flagged. Example: *Panthera pardus orientаlis*. | Retype the name in **Scientific name**. See [Edit names & taxon data](/help/edit-names#the-details-tab). |
| `superfluous_authorship` | warning | An autonym (e.g. *Panthera leo leo*) has its own authorship. An autonym takes the species' author implicitly. | Clear **Authorship**. See [Edit names & taxon data](/help/edit-names#the-details-tab). |
| `synonym_rank_differs` | warning | A synonym's rank differs from the rank of its accepted name. | Check that the synonym is linked to the right name, and that its rank is right. See [Synonyms & homotypy](/help/synonyms#the-synonyms-tab). |
| `gender_not_applicable` | warning | **Gender** is set on a name that isn't a genus, or gender agreement is set on a name without a specific epithet. | Clear the field. See [Edit names & taxon data](/help/edit-names#genus-and-gender). |
| `accepted_genus_link_not_classification` | warning | An accepted species is linked (**Nomenclatural genus**) to a different genus from the one it sits under. This often happens after a move. | Re-link the genus, or move the species. See [Edit names & taxon data](/help/edit-names#genus-and-gender). |
| `genus_link_spelling_mismatch` | warning | The linked **Nomenclatural genus** is spelled differently from the genus in the name. | Link the right genus, or fix the spelling. See [Edit names & taxon data](/help/edit-names#genus-and-gender). |
| `duplicate_child_records` | warning | A taxon has duplicate supplementary records: two distributions for the same area, two vernacular names with the same name and language, two media with the same URL, two identical estimates, or two identical properties. | Delete the duplicate on the **Vernaculars**, **Distribution**, **Media**, **Estimates** or **Biology** tab. See [Edit names & taxon data](/help/edit-names#the-other-tabs). |
| `year_vs_reference` | warning | The **Published in year** differs by more than 2 years from the year of the **Published in reference**. | Correct the year or the reference. See [References](/help/references#link-references-to-names). |
| `genus_year_after_species` | info | An accepted name was published before the genus it sits under, which is impossible for a correct genus. Uses **Published in year**. | Check both years and the placement. See [Edit names & taxon data](/help/edit-names#the-details-tab). |
| `missing_published_in` | info | The name has no **Published in reference**. This applies to every name, synonyms included, so expect many of these in an imported checklist. | Add the reference when you have it, or leave it. See [References](/help/references#link-references-to-names). |

## Identifier matching flags

**Match all identifiers** (Project → **Tools**) adds three more kinds of issue, one set per
identifier scope. For example, the scope `col` gives `col_id_added`, `col_id_updated` and
`col_id_missing`:

| Rule | Severity | What it means |
|---|---|---|
| `<scope>_id_added` | info | The name had no identifier in this scope. Blixa found a match and added it. |
| `<scope>_id_updated` | info | The stored identifier differed from the match. Blixa replaced it, and the message shows the old and new values. |
| `<scope>_id_missing` | warning | No match was found in the scope's ChecklistBank dataset. |

These flags only change when you run the match again. **Revalidate** leaves them alone. See
[Match all identifiers](/help/checklistbank#match-all-identifiers).

## Checks shown elsewhere

- **Homotypic conflicts** are names that share a type but resolve to more than one accepted name.
  Example: *Felis concolor* vs. *Puma concolor*. They don't appear on the Issues page. Find them
  with **Find homotypic conflicts** in a taxon's ⋮ menu. See
  [Homotypic conflicts](/help/synonyms#homotypic-conflicts).
- **Import issues**, such as unresolved links or skipped rows, are shown only in the import report.
  See [Import formats](/help/import-formats#what-happens-during-an-import).
