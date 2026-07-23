package org.catalogueoflife.editor.child.dto;

// Body of PUT .../property-keys -- upserts a property_key row so a standard key (`key`) gets (or
// edits) a human `description`. A null/blank description keeps the key defined but clears the text.
// The key travels in the body, not the URL path: property keys are free-form text (spaces, slashes,
// dots) that Spring Security's StrictHttpFirewall would reject in a path segment.
public record PropertyKeyDefinitionRequest(String key, String description) {}
