package org.catalogueoflife.editor.bhl;

// A BHL page within an item -- what a curator picks to fill a name's publishedInPageLink. `url` is
// the public BHL page (used as publishedInPageLink); pageNumber becomes publishedInPage.
public record BhlPage(Integer pageId, String pageNumber, String url, String thumbnailUrl) {}
