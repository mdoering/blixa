package org.catalogueoflife.editor.name.dto;

import java.util.List;

// A page of name-usage search results: `items` respects limit/offset, `total` is the count of ALL
// matches for the same filters (q/rank/status) ignoring limit/offset -- lets the frontend's
// mantine-react-table drive server-side pagination off a stable row count.
public record UsagePage(List<NameUsageResponse> items, long total) {}
