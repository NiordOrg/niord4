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
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.ScriptResourceHistory;
import org.niord.core.script.ScriptResourceImportService;
import org.niord.core.script.ScriptResourceService;
import org.niord.core.user.User;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists the resources to the database: adds a missing path unless the sysadmin unticked it, and overwrites an
 * existing row only when the sysadmin ticked it (docs/code-data-config-plan.md §6.4). The ticks are the job
 * properties {@value ScriptResourceImportService#ADD} and {@value ScriptResourceImportService#OVERWRITE}; without
 * them every missing path is added and no row is overwritten, which is what the bootstrap gets: on a fresh database
 * every file is added. A ticked overwrite that carries the fingerprint of the row the preview showed
 * ({@code basedOn}) is kept when the row has changed since.
 * Within one bootstrap run, a template that an earlier archive of the run created is replaced by a later archive's
 * different content ({@link BootstrapRun}), and counted as overwritten; outside a run that never happens.
 * <p>
 * Every write records the user who uploaded the file and the source {@value ScriptResourceHistory#SOURCE_IMPORT} in
 * the resource's history; a bootstrap's, the sysadmin who ran the bootstrap.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchScriptResourceImportWriter")
public class BatchScriptResourceImportWriter extends AbstractItemHandler {

    @Inject
    ScriptResourceService resourceService;

    @Inject
    BootstrapRun bootstrapRun;

    private BootstrapRun.Scope scope;

    private boolean choiceRead;
    private Set<String> add;
    private Map<String, String> overwrite;

    private int added;
    private int overwritten;
    private int kept;
    private int notAdded;
    private int unchanged;

    /** {@inheritDoc} **/
    @Override
    public void writeItems(List<Object> items) throws Exception {
        long t0 = System.currentTimeMillis();
        readChoice();

        // The job runs with no security context, so the history names the user who uploaded the file
        User importer = job != null ? job.getUser() : null;

        for (Object i : items) {
            ScriptResource template = (ScriptResource) i;
            String path = template.getPath();

            ScriptResource orig = resourceService.findByPath(path);

            if (orig == null) {
                if (add == null || add.contains(path)) {
                    getLog().info("Added " + path);
                    resourceService.createScriptResource(template, importer, ScriptResourceHistory.SOURCE_IMPORT);
                    scope.created(BootstrapRun.Kind.TEMPLATE, path);
                    added++;
                } else {
                    getLog().info("Not added (not ticked) " + path);
                    notAdded++;
                }
            } else if (ScriptResourceImportService.sameContent(orig.getContent(), template.getContent())) {
                getLog().info("Unchanged " + path);
                unchanged++;
            } else if (scope.createdEarlier(BootstrapRun.Kind.TEMPLATE, path)) {
                getLog().info("Replaced (an earlier archive of this bootstrap run created it) " + path);
                orig.setContent(template.getContent());
                resourceService.updateScriptResource(orig, importer, ScriptResourceHistory.SOURCE_IMPORT);
                overwritten++;
            } else if (overwrite != null && overwrite.containsKey(path) && overwrite.get(path) != null
                    && !overwrite.get(path).equals(ScriptResourceImportService.fingerprint(orig.getContent()))) {
                getLog().info("Kept (changed since the preview) " + path);
                kept++;
            } else if (overwrite != null && overwrite.containsKey(path)) {
                getLog().info("Overwritten " + path);
                orig.setContent(template.getContent());
                resourceService.updateScriptResource(orig, importer, ScriptResourceHistory.SOURCE_IMPORT);
                overwritten++;
            } else {
                getLog().info("Kept the deployment's row (not ticked) " + path);
                kept++;
            }
        }
        getLog().info(String.format("Wrote %d resources in %d ms", items.size(), System.currentTimeMillis() - t0));
    }

    /** {@inheritDoc} **/
    @Override
    public void close() throws Exception {
        if (jobContext != null && job != null) {
            getLog().info(String.format("Added %d, overwritten %d, kept %d, not added %d, unchanged %d",
                    added, overwritten, kept, notAdded, unchanged));
        }
    }

    /** Reads the sysadmin's ticks and the bootstrap run's scope from the job's properties, once */
    private void readChoice() {
        if (choiceRead) {
            return;
        }
        scope = BootstrapRun.scopeOf(bootstrapRun, job);
        if (job != null) {
            add = ScriptResourceImportService.paths(ScriptResourceImportService.ADD,
                    job.getProperties().get(ScriptResourceImportService.ADD));
            overwrite = ScriptResourceImportService.ticks(ScriptResourceImportService.OVERWRITE,
                    job.getProperties().get(ScriptResourceImportService.OVERWRITE));
        }
        choiceRead = true;
    }
}
