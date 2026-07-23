package org.catalogueoflife.editor.bhl;

// Whether BHL tooling is usable (a configured api key). Gates the frontend affordances; never a key.
public record BhlConfigResponse(boolean available) {}
