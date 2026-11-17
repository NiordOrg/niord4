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
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationCategoryService;
import org.niord.core.publication.series.criteria.LegacyFilterTranslator;
import org.niord.core.publication.vo.SystemPublicationVo;

import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Filters publications that need to be a added or updated
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchPublicationImportProcessor")
public class BatchPublicationImportProcessor extends AbstractItemHandler {

    @Inject
    PublicationCategoryService publicationCategoryService;

    /** {@inheritDoc} **/
    @Override
    public Object processItem(Object item) throws Exception {

        SystemPublicationVo publicationVo = (SystemPublicationVo) item;

        // The message tag filter is run as script, and setting another filter than the known ones is for a
        // system administrator in the publication editor. An import cannot tell who uploaded the file, so it
        // takes the known filters only.
        if (!LegacyFilterTranslator.isKnown(publicationVo.getMessageTagFilter())) {
            throw new IllegalArgumentException("Publication " + publicationVo.getPublicationId()
                    + ": the message tag filter is not one of the known filters; only a system administrator"
                    + " may set another one, in the publication editor");
        }

        Publication publication = new Publication(publicationVo);

        // Create any missing category
        publication.setCategory(publicationCategoryService.findOrCreatePublicationCategory(publication.getCategory()));

        return publication;
    }
}
