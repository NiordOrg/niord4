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

package org.niord.core.publication.batch;

import org.springframework.stereotype.Component;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.publication.PathSegmentId;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.PublicationCategoryService;
import org.niord.model.publication.PublicationCategoryVo;

import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Filters publication categories that need to be a added or updated
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchPublicationCategoryImportProcessor")
public class BatchPublicationCategoryImportProcessor extends AbstractItemHandler {

    @Inject
    PublicationCategoryService publicationCategoryService;

    /** {@inheritDoc} **/
    @Override
    public Object processItem(Object item) throws Exception {

        PublicationCategoryVo publicationCategoryVo = (PublicationCategoryVo) item;

        PublicationCategory publicationCategory = new PublicationCategory(publicationCategoryVo);

        // Look for an existing publication with the same name
        PublicationCategory orig = publicationCategoryService.findByCategoryId(publicationCategory.getCategoryId());

        if (orig == null) {
            // A new category under the id the create endpoint refuses (PUBCAT2): every
            // later read, update and delete carries it as one path segment, and the
            // request firewall refuses that segment before any handler runs. The row is
            // skipped with a sentence, as the series import skips the rows it refuses;
            // the rest of the file is still imported. A row for a category already
            // stored under such an id is an update and goes through below: no address
            // reaches that category, so the import is the one way left to unpublish or
            // rename it.
            String unservable = PathSegmentId.refusal("categoryId", publicationCategoryVo.getCategoryId());
            if (unservable != null) {
                getLog().warning("Skipping a publication category: " + unservable);
                return null;
            }

            // Persist new publication
            getLog().info("Persisting new publication category " + publicationCategory);
            return publicationCategory;

        } else {
            // Update original publication
            getLog().info("Updating publication category " + orig.getId());
            orig.updatePublicationCategory(publicationCategory);
            return orig;
        }
    }
}
