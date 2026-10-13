# References

The **References** page holds every bibliographic reference in the project: original
descriptions, revisions, checklists and web pages. Names, vernaculars, distributions, type
material and other records all cite references from this list.

Every project member can search the list and download it. Adding, editing, importing and merging
references needs the **owner** or **editor** role.

![The References page](/help/img/references.png)

## Search the references

Use this to find a reference or check whether it is already in the project.

1. Open **References** in the sidebar. The title shows how many references the project has.
2. Type into **Search citations…**. The search covers the citation text and is forgiving about
   small spelling differences.
3. To filter by year, type into the year box: `1941` for one year, `1941-1944` for a range,
   `1941-` for that year and later, or `-1944` for that year and earlier.
4. Use **Previous** and **Next** to page through the results.

Editors click a row to open it, or use its **⋮** menu to **Edit** or **Delete** it.

To save the matching references as a tab-separated file, click **Download TSV**. It respects the
search and year filter.

## Add a reference

Use this to type in a reference by hand.

1. Click **New reference**.
2. Pick the **Type**, for example `article-journal` or `book`. The type decides which fields the
   form shows: a journal article gets **Container title** (the journal), **Volume**, **Issue** and
   **Page**, a book gets **Publisher** and **ISBN**, and so on.
3. Add the **Author**s: click **Add author** and fill in **Family** and **Given** for each person.
   For an organisation as author, turn on the switch at the end of the row and type the
   **Institution name**. Add **Editor**s the same way. Remove a row with the bin icon.
4. Fill in **Title**, and optionally a **Title (short)** such as *Sp. Pl.*
5. Fill in the remaining fields: **Year**, **DOI**, **Link**, **Accessed** (for web pages,
   `YYYY-MM-DD`) and **Remarks**.
6. Click **Create**.

### The citation

The **Citation** field at the top is filled in automatically from the other fields, in the
project's citation style. You can choose APA, Chicago, Harvard, IEEE, MLA, CSE, EJT or Taxon under
**Project → Settings → Citation style**. Changing the style there updates every generated citation
in the project.

To write a citation yourself, for example to keep a historical abbreviation like *Syst. Nat., 10th
ed. vol.1 p.41*, tick **Enter citation manually** and type it in. A manual citation is left alone
when the style changes.

## Import references

### From a DOI

Use this when you know the DOI. Blixa fetches the details from Crossref, or from DataCite if
Crossref doesn't know the DOI.

1. Click **Import DOI**.
2. Enter the **DOI**. You can write it as `10.xxxx/…`, `doi:10.xxxx/…` or as a full
   `https://doi.org/…` link.
3. Click **Fetch**. Blixa opens a **New reference** form filled with the fetched details.
4. Check the details and click **Create**. Nothing is saved until you do.

### From BibTeX, RIS or CSL-JSON

Use this to bring in many references at once from a reference manager such as Zotero, Mendeley or
EndNote.

1. Export the references from your reference manager as BibTeX, RIS or CSL-JSON.
2. In Blixa, click **Import**.
3. Choose the **Format**.
4. Upload the file under **BibTeX file** (or **RIS file** or **CSL-JSON file**), or paste its
   contents into the text box below.
5. Click **Import**. Blixa reports how many references it imported.

References that come with a whole checklist are imported together with it. See
[Start a checklist](/help/start-a-checklist#import-a-file).

## Edit a reference

Click a row, or choose **Edit** from its **⋮** menu. The **Edit reference** form has the same
fields as a new reference, plus a few extras described below. Click **Save** when you're done.

If someone else saved the reference while you had it open, Blixa says **Changed by someone else —
reopen to retry**. Close the form, open it again, and repeat your change.

### Find DOI

Use this to add the missing DOI to a reference that's already in the project.

1. Open the reference with **Edit**. **Find DOI…** only appears on saved references.
2. Click **Find DOI…** under the **DOI** field. Blixa searches Crossref using the reference's
   saved details and lists candidates with title, authors, year, journal and a match score.
3. Click a DOI to check the article, then click **Use** on the right one. The DOI field is filled
   in.
4. Click **Save**.

The best match is not always right, so always check the title, year and journal.

**Example (F10):** the playground reference *Johnson, W. E., Eizirik, E., Pecon-Slattery, J., et
al. (2006). The late Miocene radiation of modern Felidae: a genetic assessment. Science 311:
73-77* has no DOI. Search for `Miocene radiation`, open it, click **Find DOI…** and use the Science
article (`10.1126/science.1122277`). Then click **Save**.

### Attach a PDF

Use this to keep a copy of the publication with the reference.

1. Open a saved reference with **Edit**.
2. Under **PDF**, choose the file with **Select a PDF**.
3. Click **Attach**.

Once a PDF is attached, **View PDF** opens it and **Remove** deletes it.

### Find on BHL

Use this to link a reference to its scanned volume in the
[Biodiversity Heritage Library](https://www.biodiversitylibrary.org). Names published in it can
then [jump to the exact page](/help/edit-names#find-the-page-on-bhl).

This option only appears if BHL is enabled on your Blixa instance.

1. Open a saved reference with **Edit**.
2. Under **BHL item**, click **Find on BHL…**.
3. The search box is filled in with the citation. Adjust it to a title, author or year if needed,
   then click **Search**.
4. Click **Link** next to the right item.

The form then shows a link to the BHL item, and an **Unlink** button.

## Reconcile journals

Use this when the same journal is spelt in different ways across your references, for example
*Proc. Zool. Soc. Lond.* and *Proceedings of the Zoological Society of London*.

1. Click **Reconcile journals…**. **Reconcile journal names** lists every journal name in the
   project, with the number of references that use it.
2. Tick two or more spellings of the same journal.
3. Choose the one to keep, or type a new one under **Or type a custom canonical value**.
4. Click **Merge N journal names into "…"**.

All references with a ticked spelling now use the chosen one. The references themselves stay
separate. To combine duplicate references, see below.

## Merge duplicate references

Use this when the same publication was entered more than once.

1. Tick the check boxes of the duplicates in the list.
2. Click **Merge N selected…**.

See [Merge duplicates & projects](/help/merge) for how to pick the surviving record and what
happens to the names that cite the duplicates.

## Link references to names

A name can cite references in two places:

- **Where it was published.** On the name's [Details](/help/edit-names#the-details-tab) tab, pick
  the reference under **Published in reference**, add the **Published in page** and optionally a
  **Published in page link**, then click **Save**. Names without one get a `missing_published_in`
  hint on the [Issues](/help/validation) page.
- **Further sources.** On the name's [References](/help/edit-names#references) tab, pick a
  reference under **Add existing reference** and click **Add**. To cite a web page that isn't in
  the list yet, paste its address under **Add web URL** and click **Add**. Blixa creates a web
  reference for it, marked with a **web** badge.

Vernacular names, distributions, type material, relations, estimates and properties each have
their own **Reference** field in their forms.
