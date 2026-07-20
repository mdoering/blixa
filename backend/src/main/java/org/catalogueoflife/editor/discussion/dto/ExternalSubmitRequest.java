package org.catalogueoflife.editor.discussion.dto;

// Body of a token-gated external submission (e.g. a COL user comment). authorOrcid is optional.
public record ExternalSubmitRequest(String title, String body, String authorOrcid) {}
