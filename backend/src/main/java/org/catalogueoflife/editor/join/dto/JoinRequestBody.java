package org.catalogueoflife.editor.join.dto;

// Unauthenticated: submitted by a visitor on a public project page, identified only by ORCID.
public record JoinRequestBody(String orcid, String name, String message) {}
