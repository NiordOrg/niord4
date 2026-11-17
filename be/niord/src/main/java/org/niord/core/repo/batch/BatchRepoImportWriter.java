/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.core.repo.batch;

import jakarta.inject.Named;
import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.repo.RepoImportService.Done;
import org.niord.core.repo.RepoImportService.Outcome;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Counts the outcomes of the repository import and writes the counts to the job's log: the files inserted,
 * skipped, verified or mismatched, and the derived files moved into the cache tree or left there.
 */
@Component
@Scope("prototype")
@Named("batchRepoImportWriter")
public class BatchRepoImportWriter extends AbstractItemHandler {

    private final Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);

    /** {@inheritDoc} **/
    @Override
    public void writeItems(List<Object> items) throws Exception {
        for (Object item : items) {
            if (item instanceof Done done && !done.repoPath().isEmpty()) {
                counts.merge(done.outcome(), 1, Integer::sum);
                if (done.outcome() == Outcome.INSERTED || done.outcome() == Outcome.MOVED) {
                    getLog().fine(done.outcome() + " " + done.repoPath());
                }
            }
        }
    }

    /** {@inheritDoc} **/
    @Override
    public void close() throws Exception {
        getLog().info("Repository import: " + counts);
    }
}
