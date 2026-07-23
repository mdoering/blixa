package org.catalogueoflife.editor.child.dto;

import java.util.List;

// Body of POST .../property-keys/merge -- PropertyKeyService.mergeKeys rewrites every property row
// whose key is one of `variants` to `canonical` (a plain field normalization, not a record merge),
// and folds the variants' property_key definitions into the canonical. Mirrors
// ContainerTitleMergeRequest for journal names.
public record PropertyKeyMergeRequest(String canonical, List<String> variants) {}
