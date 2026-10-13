# Start a checklist

Every checklist in Blixa lives in its own **project**. There are four ways to get one: start
empty, import a file, try the demo data, or bring in a dataset from ChecklistBank. Whichever you
pick, you become the project's **owner**.

All of these start from the **Projects** page (left sidebar), titled *My projects*.

![The Projects page with its three buttons](/help/img/projects-list.png)

> **Imports always create a new project.** Blixa cannot import a file into an existing project. To
> combine data with a checklist you already have, import the file as a second project and then
> [merge it in](/help/merge), or add single subtrees
> [from ChecklistBank](/help/checklistbank#import-from-checklistbank).

## New empty project

Use this when you want to build a checklist from scratch.

1. On the Projects page, click **New project**.
2. Enter a **Title**. It is required, and no two of your projects can have the same title.
3. Optionally pick a **Nomenclatural code**: *zoological*, *botanical* or *bacterial*. The code
   decides whether nomenclatural statuses on the Details form use zoological or botanical
   terminology. Bacterial projects use the botanical terms. You can set or change the code later on the project's **Settings**
   tab.
4. Click **Create**. Blixa opens the new project's **Project** page so you can fill in its
   [metadata](#first-steps-after-creating).

To add your first name, open **Tree** and click **New name**. See
[Build the classification](/help/build-the-tree).

## Import a file

Use this when your checklist already exists as a ColDP archive, a Darwin Core Archive or a
TextTree file.

![The Import ColDP dialog](/help/img/import-modal.png)

1. On the Projects page, click **Import ColDP**. The button accepts all three formats, not just
   ColDP.
2. Under **ColDP, DwC-A, or text-tree file**, choose your file:
   - a **ColDP** `.zip`,
   - a **Darwin Core Archive** `.zip` (Blixa recognises it by the `meta.xml` inside), or
   - a **TextTree** file ending in `.txtree`, `.tree`, `.txt` or `.tsv`.

   Blixa works out the format by itself. See [Import formats](/help/import-formats) for what each
   format can carry.
3. **Title**: the project's title. For a ColDP archive you can leave it empty and Blixa uses the
   title from its `metadata.yaml`. If there isn't one either, Blixa uses the file name.
4. Optionally switch on **Preserve source identifiers**. See
   [Keep the original identifiers](#keep-the-original-identifiers).
5. Click **Import**. *Importing…* shows while the file is read. Large files can take a while.

### The import report

When the import finishes, the dialog shows a short report:

- badges with the numbers of **usages** (names), **references** and **authors** that were
  imported,
- an **Open imported project** link,
- a list of **issues**, if Blixa had to skip or change anything. Each line names the kind of
  record and what went wrong.

If the import fails, a red **Import failed** box explains why and no project is created. Click
**Done** to close the dialog. The new project is now on your Projects page.

The import report only covers problems with reading the file. Checks on the data itself, such as
duplicate names or missing authorship, show up later on the project's
[Issues page](/help/validation).

### Keep the original identifiers

Blixa gives every name and reference its own numeric ID. Switch on **Preserve source
identifiers** to also keep each record's original ID from the file, stored as an extra identifier
in the form `scope:id`.

1. Switch on **Preserve source identifiers**.
2. Under **Identifier scope**, enter a short label for where the IDs come from. Pick one of the
   suggestions (for example `col` for a Catalogue of Life extract) or type your own. This field is
   required while the switch is on.

This option only works for **ColDP** archives. The switch is hidden for TextTree files, and Blixa
ignores it for Darwin Core Archives. Kept IDs show up as identifier chips on each name's
[Details](/help/edit-names#identifiers) tab, and they make it easier to match your names against
the source later.

## Try the demo data

Use this to explore Blixa without risking real data.

1. On the Projects page, click **Try it with demo data**.
2. Blixa imports a small Felidae (cat family) checklist into a new private project called
   *Blixa playground – Felidae* and opens its tree. If you already have one, the new copy is
   numbered, for example *(2)*.

The playground is the extant cats from the Catalogue of Life (about 520 names, 230 references,
vernacular names and distributions), with ten deliberate mistakes for you to find and fix. Work
through them in the [tutorial](/help/tutorial). The playground is your own project, so you can
edit it however you like, or delete it and start a fresh copy.

You can also [download the playground archive](/help/demo/blixa-playground-felidae.zip) and
import it yourself with **Import ColDP**.

## Import a ChecklistBank dataset

Use this to start from a whole dataset published in [ChecklistBank](https://www.checklistbank.org),
for example a Catalogue of Life extract or a regional checklist.

1. On checklistbank.org, open the dataset and use its download option to request an export in
   **ColDP** format. ChecklistBank prepares the archive and gives you a download link. You may
   need to sign in to ChecklistBank first.
2. Download the `.zip`.
3. In Blixa, click **Import ColDP** on the Projects page and choose the file.
4. Switch on **Preserve source identifiers** and enter a scope (for example `col` for Catalogue of
   Life data), so every name keeps its ChecklistBank ID.
5. Click **Import**.

To add one subtree from ChecklistBank to a project you **already have**, don't import a file.
Use **Import from ChecklistBank…** in the tree's row menu instead. See
[Import from ChecklistBank](/help/checklistbank#import-from-checklistbank). That page also explains
[when a full ColDP import is the better choice](/help/checklistbank#limits-and-when-to-use-a-coldp-import-instead).

## First steps after creating

Use this right after you create or import a project.

1. **Describe the project.** Open **Project** in the sidebar. On the **Metadata** tab, fill in
   **Title**, **Alias**, **License**, **Description**, **Geographic scope** and **Taxonomic
   scope**, then click **Save**. This metadata is included when you
   [export or release](/help/publish) the checklist.
2. **Check the settings.** On the **Settings** tab, check the **Nomenclatural code** and the
   **Citation style** used for generated reference citations. You can also add **Identifier
   scopes** (for example `ipni`) to get an identifier field for each scope on every name's
   Details form. Click **Save**.
3. **Invite collaborators.** Open **Members** and click **Invite by email**. Enter their
   **Email**, pick a **Role** (owner, editor or viewer) and optionally add a **Message**. See
   [Work together](/help/collaborate).
4. **Look at the data.** Open **Tree** to browse the classification, and **Issues** to see what
   validation found.

![The project's Metadata tab](/help/img/project-metadata.png)
