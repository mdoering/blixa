package org.catalogueoflife.editor.col;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Backs ColMatchJobService's @Async run(): a dedicated SINGLE-thread pool, deliberately not the
// bigger ValidationAsyncConfig pool it mirrors -- runSync makes one sequential CLB /match/nameusage
// call per usage (ClbMatchClient), so this is a polite, rate-limit-friendly client of an external
// service; a project-wide bulk-match run should never run two-at-a-time against COL, and a second
// "Match all to COL" click while one is already RUNNING simply queues behind it rather than
// hammering CLB concurrently. The bean is named (EXECUTOR_BEAN) for the same reason
// ValidationAsyncConfig's is: @Async(EXECUTOR_BEAN) is unambiguous about which pool it uses.
@Configuration
@EnableAsync
public class ColMatchAsyncConfig {

  public static final String EXECUTOR_BEAN = "colMatchTaskExecutor";

  @Bean(EXECUTOR_BEAN)
  public Executor colMatchTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("col-match-");
    executor.initialize();
    return executor;
  }
}
