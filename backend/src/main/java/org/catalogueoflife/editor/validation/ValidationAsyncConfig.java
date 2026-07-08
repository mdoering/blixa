package org.catalogueoflife.editor.validation;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Backs ValidationTrigger's @Async: a small, bounded, dedicated pool so a burst of writes can't
// starve (or be starved by) the app's other async work, and so auto-revalidation never runs
// unbounded. The bean is named (EXECUTOR_BEAN) rather than left as the Spring default -- both so
// @Async(EXECUTOR_BEAN) on the listener is unambiguous about which pool it uses, and so tests can
// reason about/await on this specific pool rather than "whatever Spring picked".
@Configuration
@EnableAsync
public class ValidationAsyncConfig {

  public static final String EXECUTOR_BEAN = "validationTaskExecutor";

  @Bean(EXECUTOR_BEAN)
  public Executor validationTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("validation-");
    executor.initialize();
    return executor;
  }
}
