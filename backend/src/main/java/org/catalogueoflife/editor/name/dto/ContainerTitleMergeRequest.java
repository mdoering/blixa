package org.catalogueoflife.editor.name.dto;

import java.util.List;

// Body of POST .../references/facets/container-title/merge -- ReferenceService.mergeContainerTitle
// rewrites every reference whose container_title is one of `variants` to `canonical` (a plain
// field normalization, not a record merge).
public record ContainerTitleMergeRequest(String canonical, List<String> variants) {}
