package org.catalogueoflife.editor.task.dto;

/** title is required (TaskService rejects blank with 400); description is optional. */
public record CreateTaskRequest(String title, String description) {}
