/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.core.category.batch;

import org.springframework.stereotype.Component;

import org.niord.core.batch.AbstractItemHandler;

import jakarta.inject.Named;
import java.util.List;

/**
 * Ends a chunk of the category import. The processor has already persisted every category
 * ({@code CategoryService.importCategory} creates or updates it, and its parents, each call in its own
 * transaction), so there is nothing left to write.
 * <p>
 * The writer must not merge the items again: a chunk runs in no transaction of its own, so the items are detached
 * by now, and the parent a leaf carries is stale once a later leaf of the same parent was added. A merge, cascaded
 * to that parent, throws an {@code OptimisticLockException} as soon as a chunk holds two siblings.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchCategoryImportWriter")
public class BatchCategoryImportWriter extends AbstractItemHandler {

    /** {@inheritDoc} **/
    @Override
    public void writeItems(List<Object> items) throws Exception {
        getLog().info(String.format("Imported %d categories", items.size()));
    }
}
