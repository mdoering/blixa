package org.catalogueoflife.editor.merge;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Backs the (later-task) MergeService.computePlan / MergeApplyService.apply's @Async methods: a
// dedicated SINGLE-thread pool, the same shape as ImportAsyncConfig -- a merge walks one
// source/target project pair sequentially (compute-plan reads both fully, apply writes the
// target), so two merge phases should never run concurrently against the executor (also
// backstopped by merge_run_active_idx's one-active-run-per-target guard). The bean is named
// (EXECUTOR_BEAN) so @Async(EXECUTOR_BEAN) is unambiguous about which pool it uses.
//
// @EnableAsync/@EnableScheduling are deliberately NOT declared here: ExportAsyncConfig already
// declares both, application-wide, and Spring's @EnableAsync/@EnableScheduling are singleton,
// app-context-level switches -- redeclaring them on another @Configuration class would be
// redundant (and this class needs neither annotation itself; only @EnableAsync's proxying is
// relevant here, and it's already on).
@Configuration
public class MergeAsyncConfig {

  public static final String EXECUTOR_BEAN = "mergeTaskExecutor";

  @Bean(EXECUTOR_BEAN)
  public Executor mergeTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("coldp-merge-");
    executor.initialize();
    return executor;
  }
}
