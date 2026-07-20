package org.catalogueoflife.editor.name.homotypy.dto;

import java.util.List;

public record ApplyHomotypicRequest(List<ApplyRelation> relations) {
  public record ApplyRelation(int usageId, int relatedUsageId, String type) {}
}
