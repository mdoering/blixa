# Validation issues

Blixa checks your names against a set of rules: misspellings, broken synonymy, names that don't fit
their classification, duplicates, missing references and more. Each finding is an **issue** with a
**severity**:

- **error**: the data is inconsistent and should be fixed, e.g. a synonym pointing at another
  synonym.
- **warning**: probably wrong, worth a look, e.g. a species whose genus part doesn't match the genus
  it sits in.
- **info**: a gap or hint, e.g. a name without a published-in reference.

The full list of rules, with what triggers each, is in [Validation rules](/help/validation-rules).

Everyone in a project can see issues. Reviewing them and starting a revalidation needs the
**owner** or **editor** role.

## When validation runs

- **After every edit.** When you save a name, Blixa revalidates it in the background a moment after
  the save. Its issues update shortly afterwards without blocking your work.
- **After an import or merge**, the imported names are validated.
- **When you stop editing a name.** When you release your edit lock on a name (see
  [Work together](/help/collaborate#soft-locks)), or it expires, that name and everything below it
  are revalidated.
- **On demand**, with **Revalidate** or **Revalidate this group** (below).

Checking one name at a time can miss knock-on effects on its neighbours. For example, renaming a
species can create or clear a `duplicate_name` on *another* name, and changing a genus's year
affects its species. Revalidating a group or the whole project settles these.

## Revalidate

Use this when the issue counts look out of date, for example after many edits or a big
restructuring.

**The whole project:**

1. Open **Issues** in the sidebar.
2. Click **Revalidate** (top right). On a large project this can take a while.
3. A "Revalidated" notification appears and the table refreshes.

**One group (a taxon and everything below it):**

1. Select the taxon in the **Tree**.
2. In the taxon header, click the refresh icon (**Revalidate this group**).
3. A notification sums up the group, e.g. "Revalidated this group: 12 issues (0 errors, 4
   warnings)".

Use the group version on large projects. It is much faster than revalidating everything.

## The Issues page

Use this to see and work through all the problems in a project.

![The Issues page of the Felidae playground](/help/img/issues.png)

- **Badges** at the top count the issues: the **total**, then by severity (**error**, **warning**,
  **info**) and by status (**open**, **accepted**, **rejected**, **done**).
- **Filters**: the first box filters by status (it starts on **open**). Clear it to see **Any
  status**. The second box filters by severity (**Any severity** by default).
- **Table**: each row shows the **Rule**, **Severity**, **Message**, the **Entity** it is about and
  its **Status**. Click the entity link (e.g. `name_usage #400`) to open that name.
- Use **Previous** and **Next** at the bottom to page through, 25 issues at a time.

### Issue statuses

| Status | Meaning | What happens when the problem goes away |
|---|---|---|
| **open** | New finding, not yet reviewed. | It disappears. |
| **accepted** | You agree it is a real problem that still needs work. | It becomes **done** and stays as a record. |
| **rejected** | Not a problem, e.g. an intentional spelling or a known exception. Rejected issues stay rejected when the name is revalidated. | It disappears. |
| **done** | An accepted issue that has since been fixed. | If the problem comes back, the issue reopens as **open**. |

### Review an issue

Use this to triage: mark real problems as accepted, or dismiss false alarms.

1. On the **Issues** page, click the **⋮** menu at the end of the row.
2. Choose one of:
   - **Accept**: confirm it is a real problem to fix.
   - **Reject**: dismiss it. It won't come back as open while the name stays the same.
   - **Reopen**: set it back to open.

You don't need to mark an issue as fixed by hand. Fix the data and the issue clears (or becomes
**done**) on the next validation.

## Issues on a taxon

Use this to see what is wrong with the name you are editing.

1. Select a name in the **Tree** or **Names**.
2. Open its **Issues** tab. The tab label shows how many issues the name has.
3. Each issue is listed with its severity and message.

After you fix the name and save, the list updates a moment later. Reviewing (accept, reject,
reopen) is done on the **Issues** page.

## Worked example: the Felidae playground

The [playground dataset](/help/tutorial) has flaws planted on purpose. Right after you import it, the
**Issues** page shows 1 error, 8 warnings and about 390 infos:

| Rule | Severity | Name | How to fix it |
|---|---|---|---|
| `synonym_of_non_accepted` | error | *Leo leo* is a synonym of *Felis leo*, which is itself a synonym (flaw F7). | Change its accepted name to *Panthera leo*. See [Synonyms & homotypy](/help/synonyms). |
| `genus_mismatch` ×4 | warning | *Leptailurus serval* and its subspecies sit under *Felis* (F5): "genus 'Leptailurus' differs from the classification genus 'Felis'". | Move *Leptailurus serval* under the genus *Leptailurus*, using **Move…** ([Build the classification](/help/build-the-tree)) or **Wire into tree** from [Compare with CLB](/help/checklistbank#wire-a-taxon-into-the-tree). One move clears all four. |
| `suspicious_name_characters` | warning | *Panthera pardus orientаlis* contains a Cyrillic "а" that looks like a Latin "a" (F6). | Open the name, retype the scientific name with a Latin "a" and save. See [Edit names & taxon data](/help/edit-names). |
| `unparsable_name` | warning | *Felis sp. "Bornean form"* is an informal placeholder (F8). | Edit or remove it, or **Reject** the issue if you want to keep the placeholder. See [Edit names & taxon data](/help/edit-names). |
| `duplicate_name` ×2 | warning | *Lynx rufus* was entered twice (F3). | Merge the two records. See [Merge selected names](/help/merge#merge-selected-names). |
| `missing_published_in` ×~330 | info | Names with no published-in reference. | Not planted: real gaps in the source data. Add references over time, or filter by **error** and **warning** to focus on the rest. |
| `genus_year_after_species` ×~60 | info | A species is dated earlier than its genus. | Not planted, and usually correct: many cats were described in *Felis* before their current genus existed. Mark them **Accepted**. |

Try this:

1. Open **Issues** and set the severity filter to **warning**.
2. Click the entity link of the `suspicious_name_characters` row to open *Panthera pardus orientаlis*.
3. Retype the name and click **Save**.
4. Go back to **Issues**. After a moment the row is gone and the **warning** badge shows one fewer.

Some flaws don't produce an issue. A missing authorship (*Lynx pardinus*, F1) and a misspelled
duplicate with a different spelling (*Panthera tigirs*, F2) are not caught by any rule, so you have
to spot them yourself in the tree. See [the tutorial](/help/tutorial).
