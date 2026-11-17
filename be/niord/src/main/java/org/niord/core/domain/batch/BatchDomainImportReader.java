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

import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.util.JsonUtils;

import tools.jackson.core.type.TypeReference;

import jakarta.inject.Named;

/**
 * Reads domains from a domains.json file. Only new domains are created, existing domains are ignored, except, within
 * the first bootstrap, a later archive's active flag for a domain an earlier archive created (BootstrapRun).
 * <p>
 * Please note, the actual domain-import.xml job file is not placed in the META-INF/batch-jobs of this project,
 * but rather, in the META-INF/batch-jobs folder of the niord-web project.<br>
 * This is because of a class-loading bug in the Wildfly implementation. See e.g.
 * https://issues.jboss.org/browse/WFLY-4988
 * <p>
 * Format of json file is defined by the DomainVo class. Example:
 * <pre>
 * TBD
 * </pre>
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchDomainImportReader")
public class BatchDomainImportReader extends AbstractItemHandler {

    List<DomainVo> domains;
    int domainNo = 0;

    /** {@inheritDoc} **/
    @Override
    // PORT: dropped CDI @ActivateRequestContext (jakarta.enterprise.context.control).
    // PORT: Spring has no drop-in for activating a request scope around a batch item;
    // PORT: re-wire scope handling (if needed) during the jBeret/batch IT stage.
    public void open(Serializable prevCheckpointInfo) throws Exception {

        // Get hold of the data file
        Path path = batchService.getBatchJobDataFile(jobContext.getInstanceId());

        // Load the domains from the file
        domains = JsonUtils.readJson(
                new TypeReference<List<DomainVo>>(){},
                path);

        if (prevCheckpointInfo != null) {
            domainNo = (Integer) prevCheckpointInfo;
        }

        getLog().info("Start processing " + domains.size() + " domains from index " + domainNo);
    }

    /** {@inheritDoc} **/
    @Override
    // PORT: dropped CDI @ActivateRequestContext (jakarta.enterprise.context.control).
    public Object readItem() throws Exception {
        if (domainNo < domains.size()) {
            getLog().info("Reading domain no " + domainNo);
            return domains.get(domainNo++);
        }
        return null;
    }

    /** {@inheritDoc} **/
    @Override
    public Serializable checkpointInfo() throws Exception {
        return domainNo;
    }
}
