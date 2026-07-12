package org.catalogueoflife.editor.publicapi.dto;
import java.util.List;
import tools.jackson.databind.JsonNode;
public record PublicProjectInfo(int id, String title, String alias, String description,
    String license, String nomCode, String geographicScope, String taxonomicScope,
    List<PublicContributor> contributors, JsonNode metrics,
    List<PublicRelease> releases) {

  public record PublicRelease(int id, String version, String notes, java.time.OffsetDateTime createdAt,
      String fileName, Long fileSize, Integer nameUsageCount, JsonNode metrics, String downloadUrl) {}
}
