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

import java.util.List;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the domains to the database. Within the first bootstrap, it records each domain for the run
 * ({@link BootstrapRun}), so that a later archive's active flag for it replaces the row's
 * ({@link BatchDomainImportProcessor}); a domain already recorded keeps the archive that created it first.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchDomainImportWriter")
public class BatchDomainImportWriter extends AbstractItemHandler {

    @Inject
    DomainService domainService;

    @Inject
    BootstrapRun bootstrapRun;

    /** {@inheritDoc} **/
    @Transactional
    @Override
    // PORT: dropped CDI @ActivateRequestContext (jakarta.enterprise.context.control).
    // PORT: Spring has no drop-in for activating a request scope around a batch item;
    // PORT: re-wire scope handling (if needed) during the jBeret/batch IT stage.
    public void writeItems(List<Object> items) throws Exception {
        long t0 = System.currentTimeMillis();
        BootstrapRun.Scope scope = BootstrapRun.scopeOf(bootstrapRun, job);
        for (Object i : items) {
            Domain domain = (Domain) i;

            domainService.saveEntity(domain);
            scope.created(BootstrapRun.Kind.DOMAIN, domain.getDomainId());
        }
        getLog().info(String.format("Persisted %d domains in %d ms", items.size(), System.currentTimeMillis() - t0));
    }
}
