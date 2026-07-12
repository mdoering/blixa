package org.catalogueoflife.editor.publicapi.dto;
import java.time.OffsetDateTime;
public record PublicProjectSummary(int id, String title, String alias, String description,
    String latestVersion, OffsetDateTime latestReleasedAt, Integer nameUsageCount) {}
