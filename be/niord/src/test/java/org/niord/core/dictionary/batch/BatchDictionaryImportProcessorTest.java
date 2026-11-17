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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.dictionary.Dictionary;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.dictionary.DictionaryEntryDesc;
import org.niord.core.dictionary.vo.DictionaryEntryDescVo;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.ExportedDictionaryVo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Framework-neutral unit tests for {@link BatchDictionaryImportProcessor#processItem(Object)}.
 *
 * <p>The processor is the conversion step of the dictionary import batch job: it casts each batch item to an
 * {@link ExportedDictionaryVo} and constructs the corresponding {@link Dictionary} entity via
 * {@code new Dictionary(vo.toDictionaryVo())}. No service, broker, database or Quarkus container is touched by
 * {@code processItem}, so the bean is exercised here as a plain object via
 * {@code new BatchDictionaryImportProcessor()} — mirroring {@code BatchPromulgationTypeImportProcessorTest}.
 *
 * <p>These tests pin (1) the value-object-to-entity field mapping performed by the processor (name plus the
 * list-of-entries-to-keyed-map conversion, including each entry's key and localized value) and (2) the
 * documented unchecked cast — a non-{@code ExportedDictionaryVo} item surfaces as a
 * {@link ClassCastException} rather than being silently skipped, and (3) the refusal: an entry the import refuses
 * ({@code DictionaryService.importRefusal}) is left out of the dictionary; outside a job the refusal goes to the
 * system log.
 */
public class BatchDictionaryImportProcessorTest {

    private BatchDictionaryImportProcessor processor;

    @BeforeEach
    public void setUp() {
        processor = new BatchDictionaryImportProcessor();
    }

    /**
     * An {@link ExportedDictionaryVo} carrying a name and two localized entries is converted into a
     * {@link Dictionary} entity whose name matches and whose entry map is keyed by entry key, with each
     * entry's localized value preserved across both languages.
     */
    @Test
    public void processItemWithExportedDictionaryVo_mapsNameAndLocalizedEntries() throws Exception {
        ExportedDictionaryVo vo = new ExportedDictionaryVo();
        vo.setName("template");

        List<DictionaryEntryVo> entries = new ArrayList<>();
        entries.add(entry("warning", desc("en", "Warning"), desc("da", "Advarsel")));
        entries.add(entry("cancelled", desc("en", "Cancelled")));
        vo.setEntries(entries);

        Object result = processor.processItem(vo);

        assertTrue(result instanceof Dictionary, "processItem must return a Dictionary entity");
        Dictionary dictionary = (Dictionary) result;

        assertEquals("template", dictionary.getName(), "name must be copied from the VO");

        // The exported list is converted into a map keyed by entry key.
        assertEquals(2, dictionary.getEntries().size(), "both entries must be carried over");
        assertTrue(dictionary.getEntries().containsKey("warning"), "entry map must be keyed by 'warning'");
        assertTrue(dictionary.getEntries().containsKey("cancelled"), "entry map must be keyed by 'cancelled'");

        DictionaryEntry warning = dictionary.getEntries().get("warning");
        assertEquals("warning", warning.getKey(), "entry key must be preserved");
        assertEquals(2, warning.getDescs().size(), "the warning entry retains both localized descs");
        assertEquals("Warning", localizedValue(warning, "en"), "English value must be mapped");
        assertEquals("Advarsel", localizedValue(warning, "da"), "Danish value must be mapped");

        DictionaryEntry cancelled = dictionary.getEntries().get("cancelled");
        assertEquals("cancelled", cancelled.getKey(), "entry key must be preserved");
        assertEquals(1, cancelled.getDescs().size(), "the cancelled entry has a single localized desc");
        assertEquals("Cancelled", localizedValue(cancelled, "en"), "English value must be mapped");
    }

    /**
     * The processor performs an unchecked cast to {@link ExportedDictionaryVo}. Feeding it an item of an
     * unrelated type fails fast with a {@link ClassCastException} rather than producing a malformed entity.
     */
    @Test
    public void processItemWithWrongType_throwsClassCastException() throws Exception {
        try {
            processor.processItem("not a dictionary vo");
            fail("Expected ClassCastException when item is not an ExportedDictionaryVo");
        } catch (ClassCastException expected) {
            // Expected: documents the unchecked (ExportedDictionaryVo) cast in processItem.
        }
    }

    /** A null item (a null entry of the file) is refused and skipped, as the preview reports it, not a failure. */
    @Test
    public void processItemWithNull_skipsTheItem() throws Exception {
        assertNull(processor.processItem(null), "a null result skips the item");
    }

    /**
     * An entry the import refuses (a value longer than its column, a blank key, a key twice) is left out; the others
     * are kept.
     */
    @Test
    public void processItemWithRefusedEntries_leavesThemOut() throws Exception {
        ExportedDictionaryVo vo = new ExportedDictionaryVo();
        vo.setName("template");

        List<DictionaryEntryVo> entries = new ArrayList<>();
        entries.add(entry("ok", desc("en", "Fine")));
        entries.add(entry("too-long", desc("en", "x".repeat(1001))));
        entries.add(entry(" ", desc("en", "Blank key")));
        entries.add(entry("ok", desc("en", "Twice")));
        entries.add(entry("control", desc("en", "a\u0007b")));
        vo.setEntries(entries);

        Dictionary dictionary = (Dictionary) processor.processItem(vo);

        assertEquals(List.of("ok"), List.copyOf(dictionary.getEntries().keySet()), "only the accepted entry is kept");
        assertEquals("Fine", localizedValue(dictionary.getEntries().get("ok"), "en"), "the first 'ok' is kept");
    }

    /*************************/
    /** Test helpers        **/
    /*************************/

    /** Builds an in-memory dictionary entry value object with a key and the given localized descriptors. */
    private static DictionaryEntryVo entry(String key, DictionaryEntryDescVo... descs) {
        DictionaryEntryVo entry = new DictionaryEntryVo();
        entry.setKey(key);
        List<DictionaryEntryDescVo> descList = new ArrayList<>();
        for (DictionaryEntryDescVo desc : descs) {
            descList.add(desc);
        }
        entry.setDescs(descList);
        return entry;
    }

    /** Builds an in-memory localized descriptor value object. */
    private static DictionaryEntryDescVo desc(String lang, String value) {
        DictionaryEntryDescVo desc = new DictionaryEntryDescVo();
        desc.setLang(lang);
        desc.setValue(value);
        return desc;
    }

    /** Returns the value of the descriptor of the entity entry for the given language. */
    private static String localizedValue(DictionaryEntry entry, String lang) {
        for (DictionaryEntryDesc desc : entry.getDescs()) {
            if (lang.equals(desc.getLang())) {
                return desc.getValue();
            }
        }
        return null;
    }
}
