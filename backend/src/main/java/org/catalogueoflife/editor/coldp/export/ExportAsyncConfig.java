package org.catalogueoflife.editor.coldp.export;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Backs ExportRunService's @Async run(): a dedicated SINGLE-thread pool, the same shape as
// ColMatchAsyncConfig -- an export walks/zips one project's files sequentially, so a project-wide
// export should never run two-at-a-time against the same project's data (also backstopped by
// export_run_active_idx), and a second "Export" click while one is already RUNNING is rejected by
// ExportRunService.start's 409 guard rather than reaching the executor at all. The bean is named
// (EXECUTOR_BEAN) for the same reason ColMatchAsyncConfig's is: @Async(EXECUTOR_BEAN) is unambiguous
// about which pool it uses.
//
// @EnableScheduling is declared here (no other @Configuration class in the app enables it yet) so
// ExportRetentionSweep's @Scheduled sweep actually runs.
@Configuration
@EnableAsync
@EnableScheduling
public class ExportAsyncConfig {

  public static final String EXECUTOR_BEAN = "exportTaskExecutor";

  @Bean(EXECUTOR_BEAN)
  public Executor exportTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("coldp-export-");
    executor.initialize();
    return executor;
  }
}
