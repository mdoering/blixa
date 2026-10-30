# Clean name relations

Names can carry **relations** to other names (basionym, replacement name, spelling correction and so
on, see the **Relations** tab). Over time, especially after imports, these can contradict each other
or be recorded twice. **Clean name relations** finds such pairs of names and lets you decide, relation
by relation, what to delete.

**Nothing is deleted unless you tick it.** Every deletion is recorded in the history.

## Start a review

- For one part of the classification, open a taxon's ⋮ menu and choose **Clean name relations**. The
  pairs are those where at least one name lies in that taxon's subtree, synonyms included.
- For the whole checklist, choose **Clean name relations** in the project's **Metadata** page. The
  project-wide scan can take a moment on large checklists.

Each pair of names appears as a card with both names and all relations between them. Relations
without a flagged problem are shown greyed out and without a checkbox, for context.

## The four findings

- **Contradicts another relation of these names**: for example one relation says the first name is
  the basionym of the second while another says the opposite. The relation Blixa suggests removing is
  ticked.
- **Repeats what another relation says**: the same fact recorded again, for example the relation
  given in both directions. The suggested duplicate is ticked.
- **The names differ in more than their spelling**: a *spelling correction* between names that are not
  plausibly spelling variants. This is only flagged, not ticked. Often the relation type is wrong.
- **Exact duplicate**: the same type between the same two names recorded more than once. Blixa keeps
  the row with the most reference, page and remarks. A duplicate is ticked only if it carries nothing
  the kept row lacks; otherwise it is listed but left unticked.

## Delete

Tick or untick the relations you want gone, then press **Delete selected**. Blixa asks for
confirmation with the number of relations, and then deletes exactly the ticked ones. If someone else
changed one of them in the meantime, nothing is deleted and the list is reloaded.

Only owners and editors see the checkboxes. Viewers see the list.
