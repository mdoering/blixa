package org.catalogueoflife.editor.name.dto;

public record CreateReferenceRequest(
    String citation,
    String type,
    String author,
    String editor,
    String title,
    String containerTitle,
    String issued,
    String volume,
    String issue,
    String page,
    String publisher,
    String doi,
    String isbn,
    String issn,
    String link,
    String accessed,
    String remarks) {}
