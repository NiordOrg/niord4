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
package org.niord.core.domain.batch;

import org.springframework.stereotype.Component;

import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.domain.DomainService;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Filters domains that need to be a added or updated. A new domain is created and an existing one is ignored, except
 * within the first bootstrap's rule ({@link BootstrapRun}): for a domain an earlier archive of the run created, the
 * later archive's active flag replaces the row's, and the job log says so. Every other job ignores a domain it finds.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchDomainImportProcessor")
public class BatchDomainImportProcessor extends AbstractItemHandler {

    @Inject
    DomainLookupService domainLookupService;

    @Inject
    AreaService areaService;

    @Inject
    CategoryService categoryService;

    @Inject
    MessageSeriesService messageSeriesService;

    @Inject
    BootstrapRun bootstrapRun;

    /** {@inheritDoc} **/
    @Override
    // PORT: dropped CDI @ActivateRequestContext (jakarta.enterprise.context.control).
    // PORT: Spring has no drop-in for activating a request scope around a batch item;
    // PORT: re-wire scope handling (if needed) during the jBeret/batch IT stage.
    public Object processItem(Object item) throws Exception {

        DomainVo domainVo = (DomainVo) item;
        Domain domain = new Domain(domainVo);

        // Look up any existing domain with the same domain ID
        Domain orig = domainLookupService.findByDomainId(domain.getDomainId());

        if (orig == null) {
            // A zone no ZoneId reads would print every time of the domain in GMT: skip the row, not the chunk.
            // Checked before the areas and categories are resolved, which may create them
            if (!DomainService.isAcceptableTimeZone(domain.getTimeZone())) {
                getLog().warning("Skipping domain " + domain.getDomainId() + ": unknown time zone '"
                        + domain.getTimeZone() + "'");
                return null;
            }

            // Make sure areas are resolved and/or created
            List<Area> areas = domain.getAreas().stream()
                    .map(a -> areaService.importArea(a, true, false))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            domain.setAreas(areas);

            // Make sure categories are resolved and/or created
            List<Category> categories = domain.getCategories().stream()
                    .map(c -> categoryService.importCategory(c, true, false))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            domain.setCategories(categories);

            // Make sure message series are resolved and/or created
            List<MessageSeries> series = domain.getMessageSeries().stream()
                    .map(s -> messageSeriesService.findOrCreateMessageSeries(s))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            domain.setMessageSeries(series);

            // Persist new domain
            getLog().info("Persisting new domain " + domain);
            return domain;

        } else if (BootstrapRun.scopeOf(bootstrapRun, job).createdEarlier(BootstrapRun.Kind.DOMAIN,
                orig.getDomainId())) {

            // The claim admits such a row only with its domainId and a boolean active
            // (BootstrapArchive.activeOnlyDomainIds), so the primitive is the value the archive gives
            boolean changed = domainVo.isActive() != orig.isActive();
            getLog().info(new BootstrapRun.Additions("Domain " + orig.getDomainId(), List.of(),
                    changed ? List.of(BootstrapRun.Additions.change("the active flag", orig.isActive(),
                            domainVo.isActive())) : List.of(), List.of()).line());
            if (!changed) {
                return null;
            }
            orig.setActive(domainVo.isActive());
            return orig;

        } else {

            // Update original
            getLog().info("Ignoring existing domain " + orig.getDomainId());
            return null;
        }
    }
}
