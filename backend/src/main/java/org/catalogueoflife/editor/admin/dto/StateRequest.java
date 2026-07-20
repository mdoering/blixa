package org.catalogueoflife.editor.admin.dto;

// state is validated against UserState in the service (bad value -> 400).
public record StateRequest(String state) {}
