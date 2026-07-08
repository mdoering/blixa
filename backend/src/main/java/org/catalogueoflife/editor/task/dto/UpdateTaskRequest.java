package org.catalogueoflife.editor.task.dto;

/**
 * Every field is nullable -- an absent field leaves the current value unchanged. Sending
 * {@code status:"closed"} is how a task is closed (stamping closed_at); see TaskService.update.
 */
public record UpdateTaskRequest(String title, String description, String status) {}
