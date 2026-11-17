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
import org.niord.core.dictionary.Dictionary;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.ExportedDictionaryVo;

import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps a ExportedDictionaryVo to a Dictionary entity.
 * <p>
 * An entry the import refuses ({@link DictionaryService#importRefusal}) is left out, and the job log names its
 * dictionary, its key and the reasons (docs/code-data-config-plan.md §4.6).
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchDictionaryImportProcessor")
public class BatchDictionaryImportProcessor extends AbstractItemHandler {

    /** The keys of this import seen so far */
    private final Set<DictionaryService.ImportKey> seen = new HashSet<>();

    /** {@inheritDoc} **/
    @Override
    public Object processItem(Object item) throws Exception {

        ExportedDictionaryVo dictionaryVo = (ExportedDictionaryVo) item;
        if (dictionaryVo == null) {
            // As the preview reports it (DictionaryService.previewImport); a null result skips the item
            logRefusal("Refused: the entry is not a dictionary");
            return null;
        }

        if (dictionaryVo.getEntries() != null) {
            List<DictionaryEntryVo> accepted = new ArrayList<>();
            for (DictionaryEntryVo entry : dictionaryVo.getEntries()) {
                List<String> reasons = DictionaryService.importRefusal(dictionaryVo.getName(), entry, seen);
                if (reasons.isEmpty()) {
                    accepted.add(entry);
                } else {
                    logRefusal("Refused " + dictionaryVo.getName() + " " + (entry == null ? null : entry.getKey())
                            + ": " + String.join("; ", reasons));
                }
            }
            dictionaryVo.setEntries(accepted);
        }

        return new Dictionary(dictionaryVo.toDictionaryVo());
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
