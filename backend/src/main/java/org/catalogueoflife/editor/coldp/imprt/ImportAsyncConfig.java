package org.catalogueoflife.editor.coldp.imprt;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Backs the (later-task) import job's @Async run(): a dedicated SINGLE-thread pool, the same shape
// as ExportAsyncConfig -- an import walks one archive sequentially and creates one project, so two
// imports should never run concurrently against the executor. The bean is named (EXECUTOR_BEAN) so
// @Async(EXECUTOR_BEAN) is unambiguous about which pool it uses.
//
// @EnableAsync/@EnableScheduling are deliberately NOT declared here: ExportAsyncConfig already
// declares both, application-wide, and Spring's @EnableAsync/@EnableScheduling are singleton,
// app-context-level switches -- redeclaring them on a second @Configuration class would be
// redundant (and @EnableScheduling isn't needed by this class at all; only @EnableAsync's proxying
// is relevant here, and it's already on).
@Configuration
public class ImportAsyncConfig {

  public static final String EXECUTOR_BEAN = "importTaskExecutor";

  @Bean(EXECUTOR_BEAN)
  public Executor importTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("coldp-import-");
    executor.initialize();
    return executor;
  }
}
