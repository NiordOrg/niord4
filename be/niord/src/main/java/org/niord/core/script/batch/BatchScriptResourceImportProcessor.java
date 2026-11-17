/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.script.batch;

import org.springframework.stereotype.Component;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.ScriptResourceImportService;
import org.niord.core.script.ScriptResourceService;
import org.niord.core.script.vo.ScriptResourceVo;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts the script resource value object into a script resource entity template.
 * <p>
 * A file the script check refuses ({@link org.niord.core.script.ScriptResourceCheck}), or whose path appears earlier
 * in the same import, is skipped, and the job log names it and its reasons (docs/code-data-config-plan.md §6.4).
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchScriptResourceImportProcessor")
public class BatchScriptResourceImportProcessor extends AbstractItemHandler {

    @Inject
    ScriptResourceService resourceService;

    /** The paths of this import seen so far */
    private final Set<String> seen = new HashSet<>();

    /** {@inheritDoc} **/
    @Override
    public Object processItem(Object item) throws Exception {

        ScriptResourceVo resourceVo = (ScriptResourceVo) item;

        List<String> reasons = ScriptResourceImportService.refusal(resourceVo, seen);
        if (!reasons.isEmpty()) {
            // A null item is skipped by the job
            logRefusal("Refused " + (resourceVo == null ? null : resourceVo.getPath()) + ": "
                    + String.join("; ", reasons));
            return null;
        }

        // Reset the ID of the resource, as we match on path, not ID
        resourceVo.setId(null);

        return new ScriptResource(resourceVo);
    }

    /** Writes the line to the job log, or to the system log when the processor runs outside a job */
    private void logRefusal(String line) {
        if (jobContext != null) {
            getLog().warning(line);
        } else {
            systemLog.warn(line);
        }
    }
}
