# Publish, release & export

When your checklist is ready to share, Blixa can:

- **describe** it with project metadata,
- **show** it on a public project page,
- **release** versioned, downloadable snapshots,
- **export** the current data at any time as ColDP, TSV or TextTree.

Everything below lives under **Project** in the sidebar (tabs **Metadata**, **Settings**, **Releases**,
**Tools**), except the TSV and TextTree downloads.

## Project metadata

Use this to describe your checklist. The metadata goes into exports and releases and onto the
public page.

1. Go to **Project** → **Metadata**.
2. Fill in:
   - **Title**: the checklist's name (required).
   - **Alias**: a short name. It also gives the public page a readable address, e.g. `/p/felidae`,
     and names your export files. Use up to 64 letters, digits, `.`, `_` or `-`, starting with a
     letter or digit, and not just digits. Each alias can belong to one project only, ignoring
     upper and lower case.
   - **License**: **CC0-1.0** or **CC-BY-4.0**. A license is required to make the project public
     and to publish releases.
   - **Description**: what the checklist covers and how it was made.
   - **Geographic scope**, e.g. "Global" or "Andes".
   - **Taxonomic scope**, e.g. "Felidae (extant)".
3. Click **Save**.

Owners and editors can edit the metadata. Viewers see it read-only. Your
[Dashboard](/help/collaborate#your-dashboard) reminds owners when a project lacks a license,
description or scope.

![The Metadata tab of the Felidae playground](/help/img/project-metadata.png)

## Settings for publishing

Use this to make a project public and to control how it is presented.

Go to **Project** → **Settings**.

![The Settings tab](/help/img/project-settings.png)

- **Public** (owner only): lists the project on the public landing page and makes its releases
  downloadable by anyone. The switch is disabled until a license is set ("Set a license first to
  make this project public."). The switch takes effect immediately. There is no need to click
  **Save**.
- **Nomenclatural code**: shown on the public page and included in exports.
- **Citation style**: see [below](#citation-style).
- **Show GBIF occurrence layer on maps**: whether taxon distribution maps show GBIF occurrences by
  default.

Except for **Public**, click **Save** after changing settings. Owners and editors can change them.

### Citation style

Use this to choose how reference citations are formatted throughout the project.

1. In **Settings**, pick a **Citation style**: APA (the default), Chicago, Harvard, IEEE, MLA, CSE,
   EJT or Taxon.
2. Click **Save**.

Changing the style regenerates every generated citation in the project. The new format shows up in
**References**, in published-in fields, and in exports and releases. Citations you typed in by hand
(**Enter citation manually** on a reference) are left as they are. See
[References](/help/references).

## The public project page

Every public project has a page anyone can open without signing in: `/p/<alias>`, or `/p/<id>` if it
has no alias. For example, `https://blixa.dev.catalogueoflife.org/p/felidae`. It shows:

- the title, alias and description,
- license, nomenclatural code, and geographic and taxonomic scope,
- **Contributors**: the project's owners and editors, with their ORCID. Viewers and email
  addresses are never shown,
- **Metrics** from the latest release (or basic current counts if there is no release yet):
  number of names, accepted names and synonyms by rank, and more,
- **Releases**, each with a **Download** link,
- **Join this project**, where visitors can **Request to join** (see
  [Join requests](/help/collaborate#join-requests)).

Public projects are also listed under **Public projects** on Blixa's landing page (the start page
for signed-out visitors), with their latest release version.

If a project isn't public, its page says "This project is not public or does not exist."

## Releases

A **release** is a frozen, versioned snapshot of the whole checklist as a ColDP archive. Unlike an
export, it is kept, numbered, and offered for download on the public page.

Go to **Project** → **Releases**.

![The Releases tab](/help/img/project-releases.png)

### Metrics

The top of the tab shows the project's live numbers: **Accepted**, **Synonyms**, **References** and
**Type material**, a **By rank** table, and **Changes since last release**. All members can see
it.

### Publish a release

Use this when the checklist has reached a state you want to cite or share.

You need to be an **owner**, and the project needs a license ("Set a license first to publish a
release.").

1. Check the [Issues](/help/validation) page and fix any errors first.
2. On **Releases**, enter a **Version**, e.g. `1.0` or `2026.1`.
3. Click **Publish release**.
4. The release appears in the list with status **BUILDING**, then **READY** with its date and size.
   If something goes wrong it shows **FAILED**.

Releases of a public project can be downloaded from its public page right away. A private project
keeps its releases internal until you make it public.

### Delete a release

Owners can delete a release with the bin icon at the end of its row, then **Delete release** to
confirm. The release and its archive are removed for good, and its download disappears from the
public page.

## Export ColDP

Use this when you need the current data as a file, e.g. to load it into ChecklistBank, back it up,
or work on it in another tool.

1. Go to **Project** → **Tools**.
2. Under **Export ColDP**, click **Export ColDP**. It shows "Exporting…" while the file is built.
3. When it's done, the file name and size appear, with counts of usages and references. Click
   **Download**.

Any project member, including viewers, can export. The archive contains the metadata, all names
(accepted, synonyms and unassessed names), references and supplementary data, in the
[ColDP](https://github.com/CatalogueOfLife/coldp) format. You can import it again into Blixa or
upload it to ChecklistBank. Export files are only kept for a while: if the download link no longer
works, export again.

## Download TSV

Use this to get a table of names or references in a spreadsheet, filtered the way you see them.

1. Open **Names** or **References**.
2. Optionally search or filter (scientific name, rank and status on **Names**, text and year on
   **References**).
3. Click **Download TSV**.

The file holds *all* rows matching the current filters, not just the page on screen, as
tab-separated text.

## Download a subtree as TextTree

Use this to get a quick, readable outline of part of the classification, e.g. for a manuscript,
a review, or to edit offline and import again.

1. Open the **Tree** and select a taxon, e.g. *Panthera*.
2. Click **Download subtree** (top right of the details panel).

You get a [TextTree](/help/texttree) file of that taxon and all accepted taxa below it, with their
synonyms nested under them.
