package org.catalogueoflife.editor.name;

// One synonym_accepted link row (SynonymAcceptedMapper.findAllLinks): every (synonym_id,
// accepted_id) pair in a project, fetched in one bulk SELECT so ColDP export's
// coldp/export/NameUsageColdpWriter can group links by synonymId to build NameUsage.tsv's
// pro-parte derived rows without an N+1 per-synonym findAcceptedFor call.
public record SynAccLink(int synonymId, int acceptedId) {}
