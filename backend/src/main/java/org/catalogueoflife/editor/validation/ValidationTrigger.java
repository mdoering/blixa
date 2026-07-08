package org.catalogueoflife.editor.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// The decoupling seam for Task 3: the write services (NameUsageService, ReferenceService,
// TreeService) publish a ValidationEvent from inside their existing @Transactional methods; this
// listener only fires AFTER that transaction commits (TransactionPhase.AFTER_COMMIT), and runs on
// the dedicated pool from ValidationAsyncConfig (@Async) rather than the request thread -- so a
// save is never slowed down by validation, and a rolled-back write triggers no validation at all
// (Spring simply never invokes an AFTER_COMMIT listener for a transaction that didn't commit).
//
// The try/catch is load-bearing: this method runs asynchronously with no caller left to propagate
// an exception to, so an uncaught rule exception would only be visible as a swallowed exception
// logged by the executor (or, worse, silently lost) -- and either way must never be able to affect
// the already-committed write it's reacting to. Catching (bare) Exception and logging is the
// explicit contract from the plan: "a rule exception is logged, never propagated to the user".
@Component
public class ValidationTrigger {

  private static final Logger log = LoggerFactory.getLogger(ValidationTrigger.class);

  private final ValidationService validationService;

  public ValidationTrigger(ValidationService validationService) {
    this.validationService = validationService;
  }

  @Async(ValidationAsyncConfig.EXECUTOR_BEAN)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onValidationEvent(ValidationEvent event) {
    try {
      validationService.revalidateUsage(event.projectId(), event.entityId());
    } catch (Exception e) {
      log.warn("auto-revalidate failed for project {} {} {}: {}", event.projectId(),
          event.entityType(), event.entityId(), e.getMessage(), e);
    }
  }
}
