package org.catalogueoflife.editor.validation;

// Published by the write services (name/NameUsageService, name/ReferenceService, tree/TreeService)
// from INSIDE their existing @Transactional methods -- see ValidationTrigger, which listens for
// this AFTER the enclosing transaction commits. entityType is carried along even though the only
// consumer today (ValidationTrigger -> ValidationService.revalidateUsage) always treats entityId as
// a name_usage id: it documents what's actually being validated and leaves room for the rule
// catalogue to grow beyond name_usage without changing this record's shape.
public record ValidationEvent(int projectId, String entityType, int entityId) {

  public static final String ENTITY_NAME_USAGE = "name_usage";

  public static ValidationEvent forUsage(int projectId, int usageId) {
    return new ValidationEvent(projectId, ENTITY_NAME_USAGE, usageId);
  }
}
