package org.catalogueoflife.editor.validation;

import java.util.List;

// Published by writes that touch many usages at once (NameUsageService.bulkChangeStatus) in place
// of one ValidationEvent per usage: ValidationTrigger.onBulkValidationEvent revalidates them all in
// ONE async task, so a large batch can't overflow the bounded validation queue (see
// ValidationAsyncConfig) and silently lose events. Same AFTER_COMMIT contract as ValidationEvent.
public record BulkValidationEvent(int projectId, List<Integer> usageIds) {}
