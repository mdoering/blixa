package org.catalogueoflife.editor.join.dto;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.join.JoinRequest;

public record JoinRequestResponse(int id, String orcid, String name, String message, OffsetDateTime createdAt) {

  public static JoinRequestResponse of(JoinRequest r) {
    return new JoinRequestResponse(r.getId(), r.getOrcid(), r.getName(), r.getMessage(), r.getCreatedAt());
  }
}
