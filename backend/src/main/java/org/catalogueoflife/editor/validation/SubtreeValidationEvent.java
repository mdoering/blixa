package org.catalogueoflife.editor.validation;

// Published when an objective-tagged name_usage lock is released (LockService.release) or swept
// (LockRetentionSweep) -- see ValidationTrigger.onSubtreeValidationEvent, which revalidates the whole
// subtree rooted at rootUsageId. Kept separate from ValidationEvent so the single-usage listener is
// untouched: the two events map to the two ValidationService entry points (revalidateUsage vs
// revalidateSubtree). Unlike ValidationEvent, this fires from BOTH a transactional context (release)
// and a non-transactional one (the scheduled sweep), which is why its listener uses
// fallbackExecution = true.
public record SubtreeValidationEvent(int projectId, int rootUsageId) {}
