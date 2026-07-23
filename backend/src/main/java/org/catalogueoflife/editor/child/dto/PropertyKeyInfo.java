package org.catalogueoflife.editor.child.dto;

// One property key in a project's overview: the key itself, how many property rows use it
// (0 for a defined-but-unused key), and its optional description (null for a used-but-undefined
// key). The union of used keys (property.property) and defined keys (property_key) that
// PropertyKeyMapper.keyFacet returns, powering the autocomplete + PropertyKeysModal reconcile UI
// (mirrors ContainerTitleFacet for journal names).
public record PropertyKeyInfo(String key, int count, String description) {}
