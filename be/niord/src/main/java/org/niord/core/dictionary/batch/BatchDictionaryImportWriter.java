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
package org.niord.core.dictionary.batch;

import org.springframework.stereotype.Component;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.dictionary.Dictionary;
import org.niord.core.dictionary.DictionaryService;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;

/**
 * Persists the dictionaries to the database: adds a missing key unless the sysadmin unticked it, and overwrites an
 * existing key only when the sysadmin ticked it (docs/code-data-config-plan.md §4.6). The ticks are the job
 * properties {@value DictionaryService#IMPORT_ADD} and {@value DictionaryService#IMPORT_OVERWRITE}, each a JSON array
 * of {@code {"dictionary": ..., "key": ...}}, an overwrite tick with the preview's {@code basedOn}; without them every
 * missing key the defaults ledger does not record as deleted is added and no key is overwritten, which is what the
 * bootstrap gets. A ticked key whose row changed since the preview is kept, and the log names it. Within the first
 * bootstrap's rule, a later archive's values replace those of a key an earlier archive of the run created, and its
 * new languages are added ({@link BootstrapRun}).
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchDictionaryImportWriter")
public class BatchDictionaryImportWriter extends AbstractItemHandler {

    @Inject
    DictionaryService dictionaryService;

    @Inject
    BootstrapRun bootstrapRun;

    private DictionaryService.ImportChoice choice;

    /** {@inheritDoc} **/
    @Override
    public void writeItems(List<Object> items) throws Exception {
        long t0 = System.currentTimeMillis();
        BootstrapRun.Scope scope = BootstrapRun.scopeOf(bootstrapRun, job);
        for (Object i : items) {
            Dictionary dictionary = (Dictionary) i;
            DictionaryService.ImportResult result = dictionaryService.importDictionary(dictionary, choice(), scope);
            result.changedSincePreview().forEach(key -> getLog().info(
                    "Kept (changed since the preview) " + dictionary.getName() + " " + key));
            result.sameRun().forEach(line -> getLog().info("Dictionary " + dictionary.getName() + ": " + line));
            // Within a run only: the keys an earlier archive of the run created, counted apart from "kept (not ticked)"
            String sameRun = result.languagesAdded() > 0 || result.replacedInRun() > 0
                    ? String.format(", replaced in %d, languages added to %d (an earlier archive of this bootstrap "
                            + "run created them)", result.replacedInRun(), result.languagesAdded())
                    : "";
            getLog().info(String.format("Dictionary %s: added %d, overwritten %d, kept %d (not ticked), "
                            + "not added %d (not ticked), unchanged %d%s", dictionary.getName(), result.added(),
                    result.overwritten(), result.kept(), result.notAdded(), result.unchanged(), sameRun));
        }
        getLog().info(String.format("Imported %d dictionaries in %d ms", items.size(), System.currentTimeMillis() - t0));
    }

    /** The sysadmin's ticks, read from the job's properties once */
    private DictionaryService.ImportChoice choice() {
        if (choice == null) {
            choice = job == null
                    ? DictionaryService.ImportChoice.DEFAULT
                    : new DictionaryService.ImportChoice(
                            DictionaryService.importKeys(DictionaryService.IMPORT_ADD,
                                    job.getProperties().get(DictionaryService.IMPORT_ADD)),
                            DictionaryService.importTicks(DictionaryService.IMPORT_OVERWRITE,
                                    job.getProperties().get(DictionaryService.IMPORT_OVERWRITE)));
        }
        return choice;
    }
}
