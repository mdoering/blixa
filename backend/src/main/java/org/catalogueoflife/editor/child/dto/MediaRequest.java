package org.catalogueoflife.editor.child.dto;

public record MediaRequest(
    String url,
    String type,
    String title,
    String creator,
    String license,
    String link,
    String remarks,
    Integer version) {}
