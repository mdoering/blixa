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

The relations Blixa suggests removing come ticked. Some findings are only flagged and come
unticked: nothing tells which relation is wrong, so the decision is yours.

- **Contradicts another relation of these names**: the relations of the pair cannot all be right.
  Where the names tell which one is wrong, that relation is ticked. For example a basionym or
  homotypic relation next to a *later homonym*, or a basionym given in both directions where only
  one of the names cites the other's authors in brackets. Where the names do not tell, the relations
  are only flagged and none is ticked. For example a basionym given in both directions between two
  names that cite no authors in brackets and have the same or no year, or a basionym next to a type
  relation where both or neither fit the names.
- **Repeats what another relation says**: a *homotypic* relation next to a more specific one, such as
  a basionym, or a homotypic relation recorded again, for example in the other direction. The
  repetition is ticked.
- **The names differ in more than their spelling**: a *spelling correction* between names that are
  not plausibly spelling variants. It is ticked. Often only the relation type is wrong: untick it and
  correct the type in the **Relations** tab instead.
- **Exact duplicate**: the same type between the same two names, in the same direction, recorded
  more than once. Blixa keeps the row with the most reference, page and remarks. If the relation
  itself is ticked for removal, all its duplicates are ticked too. If the relation stays, a duplicate
  is ticked only if it carries nothing the kept row lacks; otherwise it is listed but left unticked.

## Delete

Tick or untick the relations you want gone, then press **Delete selected**. Blixa asks for
confirmation with the number of relations, and then deletes exactly the ticked ones. If someone else
changed one of them in the meantime, nothing is deleted and the list is reloaded.

Only owners and editors see the checkboxes. Viewers see the list.
