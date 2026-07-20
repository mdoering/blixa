package org.catalogueoflife.editor.discussion.dto;

import java.util.List;

// A page of discussions: `items` respects limit/offset, `total` is the count of ALL matches for the
// same filters -- lets the frontend table drive server-side pagination off a stable row count.
// Mirrors name.dto.UsagePage.
public record DiscussionPage(List<DiscussionResponse> items, long total) {}
