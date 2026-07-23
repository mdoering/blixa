package org.catalogueoflife.editor.bhl;

// A BHL item (a digitised volume/book) candidate from a publication search -- what a curator picks
// to link a reference to. `url` is the public BHL item page. Fields are best-effort (BHL metadata is
// uneven), so any may be null except itemId when present.
public record BhlItem(Integer itemId, String title, String authors, String year, String url) {}
