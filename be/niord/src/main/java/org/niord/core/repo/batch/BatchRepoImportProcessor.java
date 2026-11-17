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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.repo.RepoImportService;
import org.niord.core.repo.RepoImportService.Done;
import org.niord.core.repo.RepoImportService.Item;
import org.niord.core.repo.RepoImportService.Kind;
import org.niord.core.repo.RepoImportService.Outcome;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Does one item of the repository import: a file imported or moved into the cache tree, or, last, the rewrite of
 * the audit entries' archived locations and the verification from the rows' side, whose report goes to the job's
 * log.
 */
@Component
@Scope("prototype")
@Named("batchRepoImportProcessor")
public class BatchRepoImportProcessor extends AbstractItemHandler {

    @Inject
    RepoImportService importService;

    /** {@inheritDoc} **/
    @Override
    public Object processItem(Object item) throws Exception {
        Item it = (Item) item;
        if (it.kind() == Kind.VERIFY) {
            // The root as the reader confined it at the job's start: one rule gives the folder the plan read from
            // and the folder the archived locations are matched against
            Object archiveDir = job.getProperties().get(RepoImportService.ARCHIVE_DIR);
            Path archiveRoot = importService.confine(archiveDir == null ? null : archiveDir.toString());
            if (archiveRoot != null) {
                List<String> rewritten = importService.rewriteArchiveEntries(archiveRoot);
                rewritten.forEach(line -> getLog().info(line));
            }
            List<String> dangling = importService.verifyReferences();
            dangling.forEach(line -> getLog().warning(line));
            getLog().info(dangling.isEmpty()
                    ? "Every reference the database holds resolves to a row"
                    : dangling.size() + " reference(s) resolve to no row (above)");
            return new Done(Outcome.VERIFIED, "");
        }

        boolean verify = Boolean.parseBoolean(String.valueOf(job.getProperties().get(RepoImportService.VERIFY)));
        Outcome outcome;
        try {
            outcome = importService.apply(it, verify);
        } catch (Exception e) {
            // One file (unreadable, too large for a row, a name the file system cannot represent) stops nothing:
            // reported here, and again by the verification when a reference names it; the next run tries it again
            getLog().warning("Failed: " + it.file() + " -> " + it.repoPath() + ": " + e);
            return new Done(Outcome.FAILED, it.repoPath());
        }
        if (outcome == Outcome.MISMATCH) {
            getLog().warning("Mismatch: " + it.repoPath() + " on disk differs from its row");
        }
        return new Done(outcome, it.repoPath());
    }
}
