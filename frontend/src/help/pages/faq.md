# FAQ & troubleshooting

Quick answers to common questions and problems. If your problem isn't listed here,
[report it](#how-do-i-report-a-bug).

## My account says "Awaiting approval"

New accounts need an administrator's approval before you can use Blixa.

- The first time you sign in with ORCID, you see **Apply for access**. Enter your **Email**, add
  an optional **Message**, and click **Submit application**.
- You then see **Awaiting approval** until an administrator approves you. To correct your email or
  message, click **Edit application**.
- If a project owner invited you by email, open the invitation link and accept it. This activates
  your account without waiting for approval.
- **Account disabled** means an administrator has switched your account off. Contact them if you
  think this is a mistake.

See [Apply for access](/help/access#apply-for-access).

## I can't edit anything

You're probably a **viewer** in this project. Viewers can browse, search, download a subtree and
export, but the editing controls are hidden from them: **New name**, the ⋮ menu on names,
**Revalidate**, the **Save** buttons, and so on. Ask a project **owner** to make you an editor.
Owners do this on the **Members** page: enter your username, choose `editor`, and click
**Add / update**. See [Add or change a member](/help/collaborate#add-or-change-a-member).

## A name doesn't show in the tree

- **It's a synonym or misapplied name.** The tree only shows accepted (and unassessed) names.
  Synonyms are listed on their accepted name's **Synonyms** tab. Use **Names** to find any name,
  whatever its status.
- **It's unassessed.** Switch on **Show unassessed** above the tree. To accept the name, use
  **Change status** → **Accepted** in its ⋮ menu, or **Accept subtree…** on its parent. In the
  playground, *Leopardus garleppi*, *L. munoai* and *L. narinensis* are unassessed. See
  [Accept unassessed names](/help/build-the-tree#accept-unassessed-names).
- **Its parent has many children.** Large groups load a page at a time. Click **Load more** at the
  end of the list.

## Download subtree is missing some names

**Download subtree** writes only accepted names, plus the synonyms and misapplied names of each
one. Unassessed names are left out. See [TextTree syntax](/help/texttree#download-subtree).

## "Changed by someone else"

Someone saved the same record after you opened it, and Blixa doesn't overwrite their change.
Blixa reloads the latest version, which replaces any unsaved edits in your form. Check what the
other person changed (the **History** page shows it), then make your change again. In a modal
(move, demote, promote) or a reference form, close it and start again.

## Someone is editing this name

The orange note *"… is editing this name — your changes may conflict"* means another person has a
[soft lock](/help/glossary#soft-lock) on the name. You can still save, but if they save first,
you'll get *Changed by someone else*. It's usually best to wait, or to agree in a
[discussion](/help/collaborate#discussions) who works on what. **Activity** lists who is editing what and when
each lock expires. Locks expire on their own after about five minutes without activity. You can
**Release** your own locks there. See [The Activity page](/help/collaborate#the-activity-page).

## My import failed

The message in the import modal tells you why. Common causes:

| Message | What to do |
|---|---|
| *you already have a project titled "…"* | Enter a different **Title** in the import modal. |
| *license must be CC0-1.0 or CC-BY-4.0* | Fix or delete the `license` line in the archive's `metadata.yaml`. |
| A path ending in `metadata.yaml` | The ColDP zip has no `metadata.yaml`. Add one, or enter a **Title**. |
| *archive has neither a NameUsage file nor Name+Taxon files* | The zip isn't a ColDP archive, or the files are named differently. |
| *DwC-A core must be a Taxon core* | Blixa only imports checklist (Taxon core) archives. |
| *file exceeds … bytes* | The file is larger than 100 MB. |
| *Tree is not indented properly on line …* | Fix the TextTree indentation. See [TextTree syntax](/help/texttree#indentation). |
| *import service busy* | Several imports are queued. Try again in a few minutes. |

A failed import doesn't create a project, so you can simply try again. See
[Import formats](/help/import-formats).

## Why so many missing published-in issues?

`missing_published_in` is an **info** hint, raised for every name without a **Published in
reference**, synonyms included. Many checklists don't cite the original publication for every
name, so an imported checklist often has hundreds of these. The playground has about 330.
Filter the Issues page by **severity** to focus on errors and warnings first. See
[Validation rules](/help/validation-rules).

## Search doesn't find a name

- The **Names** search finds names that start with your text, or are spelled similarly to it as a
  whole. A fragment from the middle of a long name (an epithet alone) may not be similar enough
  to match, so type the name from its start: genus and epithet. Authorship isn't searched.
- **Look-alike characters** break matching. A name typed or pasted with, for example, a Cyrillic
  `а` instead of a Latin `a` looks right but is a different string. In the playground,
  *Panthera pardus orientаlis* is such a name. Blixa flags these as
  `suspicious_name_characters` on the **Issues** page. Retype the name to fix it.

## How do I undo a change?

Blixa has **no undo button**. The **History** page lists every change with who made it and when.
Click **Show diff** to see the old and new values, then edit the record back by hand. Deleting a
name can't be undone, so Blixa asks you to confirm first. See [History](/help/collaborate#history).

## Where is my export?

Go to **Project** → **Tools** and click **Export ColDP**. When it's ready, a **Download** link
appears with the file name and size. Exports are kept for **7 days**, then deleted. After that,
just export again. To keep a permanent, versioned copy, publish a **release** instead. See
[Export ColDP](/help/publish#export-coldp).

## Bulk add rejects my list

- *Synonymy mode requires a flat list of names (no indentation)*: in **As synonyms of target**
  mode, remove all indentation.
- *This list is too large for a direct insert*: Bulk add takes up to 1,000 names. Split the list,
  or import it as a new project.
- *Tree is not indented properly…*: use exactly two spaces per level, with no tabs and no lines
  that contain only spaces.

See [TextTree syntax](/help/texttree).

## How do I report a bug?

Open an issue at [github.com/mdoering/blixa/issues](https://github.com/mdoering/blixa/issues).
Please include:

- what you did, what you expected, and what happened instead
- the project and the name (or a link to the page)
- the exact error message, and a screenshot if possible
