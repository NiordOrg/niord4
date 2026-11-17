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
import org.niord.core.repo.RepoImportService.Item;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the items of the repository import ({@link RepoImportService}): the plan of the folders the job's
 * properties name, and the verification last. The checkpoint is the last item read, so a restarted job plans
 * again and continues after it.
 */
@Component
@Scope("prototype")
@Named("batchRepoImportReader")
public class BatchRepoImportReader extends AbstractItemHandler {

    @Inject
    RepoImportService importService;

    private List<Item> items;
    private int index;

    /** {@inheritDoc} **/
    @Override
    public void open(Serializable prevCheckpointInfo) throws Exception {
        Path dir = importService.confine(property(RepoImportService.DIR));
        Path archiveDir = importService.confine(property(RepoImportService.ARCHIVE_DIR));
        if (dir == null) {
            throw new IllegalArgumentException("The repository import needs the folder to import, " + RepoImportService.DIR);
        }
        getLog().info("Importing the repository files of " + dir
                + (archiveDir != null ? " and the archived files of " + archiveDir : ""));

        items = new ArrayList<>(importService.plan(dir, archiveDir, getLog()::info));
        items.add(Item.verify());
        index = prevCheckpointInfo instanceof Item last ? RepoImportService.resumeIndex(items, last) : 0;
        if (index > 0) {
            getLog().info("Resuming with item " + index + " of " + items.size());
        }
    }

    /** {@inheritDoc} **/
    @Override
    public Object readItem() throws Exception {
        if (index >= items.size()) {
            return null;
        }
        Item item = items.get(index++);
        updateProgress((int) (index * 100L / items.size()));
        return item;
    }

    /** {@inheritDoc} **/
    @Override
    public Serializable checkpointInfo() throws Exception {
        return index == 0 ? null : items.get(index - 1);
    }

    /** A job property as text, or null */
    private String property(String key) {
        Object value = job.getProperties().get(key);
        return value == null ? null : value.toString();
    }
}
