package org.catalogueoflife.editor.release;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Backs ReleaseService's @Async build(): a dedicated single-thread pool, the same shape as
// ExportAsyncConfig -- a release build walks/zips one project's files sequentially. No
// @EnableAsync/@EnableScheduling here: ExportAsyncConfig already enables both app-wide, and
// re-declaring either would create a duplicate-bean/config problem.
@Configuration
public class ReleaseAsyncConfig {

  public static final String EXECUTOR_BEAN = "releaseTaskExecutor";

  @Bean(EXECUTOR_BEAN)
  public Executor releaseTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("coldp-release-");
    executor.initialize();
    return executor;
  }
}
