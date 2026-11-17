/*
 * Copyright 2024 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.core.conf;

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.context.JobContext;
import jakarta.transaction.TransactionManager;
import org.jberet.operations.JobOperatorImpl;
import org.jberet.repository.JobRepository;
import org.jberet.se.BatchSEEnvironment;
import org.jberet.spi.ArtifactFactory;
import org.jberet.spi.BatchEnvironment;
import org.jberet.spi.JobTask;
import org.jberet.spi.JobXmlResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * JBeret (JSR-352) configuration — the Spring Boot 4 replacement for {@code quarkus-jberet}.
 * Exposes a {@link JobOperator} bean backed by a JBeret SE batch environment whose artifact
 * factory resolves batch artifacts from the Spring context (see {@link SpringArtifactFactory}),
 * so {@code @Named @Component} readers/processors/writers keep their injected dependencies.
 *
 * <p>{@code BatchSEEnvironment} is {@code final}, so the environment is composed by delegation:
 * everything (job repository, executor, transaction manager) is forwarded to a real SE
 * environment except the artifact factory.
 */
@Configuration
public class BatchConfig {

    @Bean
    public JobOperator jobOperator(ApplicationContext context) {
        final BatchSEEnvironment delegate = new BatchSEEnvironment();
        // Note: we deliberately do NOT use delegate.getArtifactFactory() — JBeret's default
        // SE factory bootstraps a Weld CDI container. The Spring factory resolves artifacts.
        final ArtifactFactory springFactory = new SpringArtifactFactory(context);

        BatchEnvironment environment = new BatchEnvironment() {
            @Override public ClassLoader getClassLoader() { return delegate.getClassLoader(); }
            @Override public ArtifactFactory getArtifactFactory() { return springFactory; }
            // NOT covered by the MDC TaskDecorator (CorrelationConfig): JBeret submits batch work
            // onto the SE environment's own executor, which Spring never sees. Correlating batch log
            // lines means a JSR-352 JobListener putting a jobExecutionId in the MDC around each job
            // execution — a separate change. See org.niord.core.util.MdcContext.
            @Override public void submitTask(JobTask task) { delegate.submitTask(task); }
            @Override public TransactionManager getTransactionManager() { return delegate.getTransactionManager(); }
            @Override public JobRepository getJobRepository() { return delegate.getJobRepository(); }
            @Override public JobXmlResolver getJobXmlResolver() { return delegate.getJobXmlResolver(); }
            @Override public Properties getBatchConfigurationProperties() { return delegate.getBatchConfigurationProperties(); }
            @Override public String getApplicationName() { return delegate.getApplicationName(); }
        };
        return new JobOperatorImpl(environment);
    }

    /**
     * The JSR-352 {@link JobContext} that batch artifacts inject via {@code @Inject JobContext}.
     * Backed by JBeret's per-thread {@code ArtifactCreationContext}, so the single shared bean
     * resolves to whichever job is currently executing on the calling batch thread. This replaces
     * the per-job CDI-scoped context that Quarkus/JBeret produced.
     */
    @Bean
    public JobContext jobContext() {
        return new JobContextDelegate();
    }
}
