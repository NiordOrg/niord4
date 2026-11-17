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

import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.context.JobContext;
import org.jberet.creation.ArtifactCreationContext;

import java.util.Properties;

/**
 * Spring-managed {@link JobContext} that forwards every call to the JSR-352 job context
 * JBeret has bound to the current batch thread.
 *
 * <p>Under Quarkus, {@code @Inject JobContext} on a batch artifact (reader/processor/writer/listener)
 * was satisfied by JBeret's CDI integration, which produces a per-job contextual instance. The Spring
 * port resolves batch artifacts as {@code @Component} beans through {@link SpringArtifactFactory}; Spring
 * autowiring therefore needs a {@code JobContext} bean to satisfy those {@code @Inject} points.
 *
 * <p>JBeret publishes the active job context on a thread-local immediately before it asks the artifact
 * factory to create an artifact (see {@code JobContextImpl.createArtifact} →
 * {@code ArtifactCreationContext.resetArtifactCreationContext}) and keeps it bound for the duration of the
 * job execution on that batch thread. This singleton delegate resolves that thread-local on every call, so
 * a single shared bean correctly serves whichever job/step is currently executing on the calling thread —
 * including transient user data, which is stored on the real per-job context and shared across the job's
 * artifacts exactly as the original CDI-scoped instance was.
 */
public class JobContextDelegate implements JobContext {

    private static JobContext current() {
        ArtifactCreationContext ctx = ArtifactCreationContext.getCurrentArtifactCreationContext();
        if (ctx == null || ctx.getJobContext() == null) {
            throw new IllegalStateException(
                    "No JBeret JobContext bound to the current thread — JobContext can only be used "
                            + "from within a running batch artifact");
        }
        return ctx.getJobContext();
    }

    @Override
    public String getJobName() {
        return current().getJobName();
    }

    @Override
    public Object getTransientUserData() {
        return current().getTransientUserData();
    }

    @Override
    public void setTransientUserData(Object data) {
        current().setTransientUserData(data);
    }

    @Override
    public long getInstanceId() {
        return current().getInstanceId();
    }

    @Override
    public long getExecutionId() {
        return current().getExecutionId();
    }

    @Override
    public Properties getProperties() {
        return current().getProperties();
    }

    @Override
    public BatchStatus getBatchStatus() {
        return current().getBatchStatus();
    }

    @Override
    public String getExitStatus() {
        return current().getExitStatus();
    }

    @Override
    public void setExitStatus(String status) {
        current().setExitStatus(status);
    }
}
