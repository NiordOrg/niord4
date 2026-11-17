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
package org.niord.core.dictionary.vo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link DictionaryEntryVo} and its localized
 * description {@link DictionaryEntryDescVo}.
 *
 * <p>These are pure in-memory value objects: no database, no CDI, no Quarkus.
 * The localization behaviour ({@code createDesc}, {@code getDesc},
 * {@code checkCreateDesc}, {@code checkCreateDescs}) comes from the
 * {@link org.niord.model.ILocalizable} / {@link org.niord.model.ILocalizedDesc}
 * default methods, exercised here through the concrete VO classes.
 */
public class DictionaryEntryVoTest {

    /* ===================================================================== */
    /* DictionaryEntryVo                                                     */
    /* ===================================================================== */

    /** 1. createDesc("en") lazily initialises the list and appends an "en" desc. */
    @Test
    public void createDesc_initializesListAndAddsDescWithLang() {
        DictionaryEntryVo entry = new DictionaryEntryVo();
        // The descs list starts out null until the first desc is created.
        assertNull(entry.getDescs(), "descs must be null before any desc is created");

        DictionaryEntryDescVo created = entry.createDesc("en");

        assertNotNullList(entry.getDescs());
        assertEquals(1, entry.getDescs().size(), "Exactly one desc must have been added");
        assertEquals("en", created.getLang());
        assertSame(created, entry.getDescs().get(0),
                "createDesc must return the same instance it added to the list");
        assertEquals("en", entry.getDesc("en").getLang());
    }

    /** 2. getDesc(lang) returns the matching desc, and null for a language that is absent. */
    @Test
    public void getDesc_returnsMatchForKnownLangAndNullForMissingLang() {
        DictionaryEntryVo entry = new DictionaryEntryVo();
        DictionaryEntryDescVo en = entry.createDesc("en");

        // Exact (case-insensitive) match returns that very desc.
        assertSame(en, entry.getDesc("en"));
        assertSame(en, entry.getDesc("EN"), "getDesc must be case-insensitive on the language code");

        // A language not present (and no null-lang desc to fall back on) returns null.
        assertNull(entry.getDesc("fr"),
                "getDesc must return null for a language with no matching desc");
    }

    /** 3. checkCreateDesc creates when missing, and returns the existing desc when present. */
    @Test
    public void checkCreateDesc_createsWhenMissingAndReusesWhenPresent() {
        DictionaryEntryVo entry = new DictionaryEntryVo();

        // "da" is missing -> a new desc is created.
        DictionaryEntryDescVo da = entry.checkCreateDesc("da");
        assertEquals("da", da.getLang());
        assertEquals(1, entry.getDescs().size());

        // First call for "en" creates it.
        DictionaryEntryDescVo en1 = entry.checkCreateDesc("en");
        assertEquals("en", en1.getLang());
        assertEquals(2, entry.getDescs().size());

        // Second call for "en" returns the same object, with no extra desc added.
        DictionaryEntryDescVo en2 = entry.checkCreateDesc("en");
        assertSame(en1, en2, "checkCreateDesc must reuse the existing desc for a present language");
        assertEquals(2, entry.getDescs().size(),
                "No duplicate desc must be created for an existing language");
    }

    /** 4. The scalar getters/setters and the descs list round-trip their values. */
    @Test
    public void gettersAndSetters_roundTrip() {
        DictionaryEntryVo entry = new DictionaryEntryVo();

        entry.setId(42);
        entry.setKey("port.name");
        entry.setAtonFilter("type='light'");

        assertEquals(Integer.valueOf(42), entry.getId());
        assertEquals("port.name", entry.getKey());
        assertEquals("type='light'", entry.getAtonFilter());

        List<DictionaryEntryDescVo> descs = new ArrayList<>();
        DictionaryEntryDescVo desc = new DictionaryEntryDescVo();
        desc.setLang("en");
        descs.add(desc);
        entry.setDescs(descs);

        assertSame(descs, entry.getDescs(), "getDescs must return the exact list that was set");
        assertEquals(1, entry.getDescs().size());
        assertSame(desc, entry.getDescs().get(0));
    }

    /* ===================================================================== */
    /* DictionaryEntryDescVo                                                 */
    /* ===================================================================== */

    /** 5. descDefined() is driven solely by the value field (non-blank => true). */
    @Test
    public void descDefined_isBasedOnValueOnly() {
        DictionaryEntryDescVo desc = new DictionaryEntryDescVo();

        // null value -> not defined
        assertNull(desc.getValue());
        assertFalse(desc.descDefined(), "A null value must not be considered defined");

        // empty value -> not defined
        desc.setValue("");
        assertFalse(desc.descDefined(), "An empty value must not be considered defined");

        // non-blank value -> defined
        desc.setValue("Harbour");
        assertTrue(desc.descDefined(), "A non-blank value must be considered defined");
    }

    /**
     * 6. copyDesc() copies ONLY the value, not the longValue.
     * This pins the current (value-only) copy behaviour of the VO.
     */
    @Test
    public void copyDesc_copiesValueButNotLongValue() {
        DictionaryEntryDescVo source = new DictionaryEntryDescVo();
        source.setLang("en");
        source.setValue("v");
        source.setLongValue("lv");

        DictionaryEntryDescVo target = new DictionaryEntryDescVo();
        target.copyDesc(source);

        assertEquals("v", target.getValue(), "copyDesc must copy the value");
        assertNull(target.getLongValue(), "copyDesc must NOT copy the longValue");
        // copyDesc also leaves the language untouched on the target.
        assertNull(target.getLang(), "copyDesc must not copy the language");
    }

    /** 7. The lang / value / longValue getters and setters round-trip their values. */
    @Test
    public void descGettersAndSetters_roundTrip() {
        DictionaryEntryDescVo desc = new DictionaryEntryDescVo();

        desc.setLang("da");
        desc.setValue("Havn");
        desc.setLongValue("En lang beskrivelse af havnen");

        assertEquals("da", desc.getLang());
        assertEquals("Havn", desc.getValue());
        assertEquals("En lang beskrivelse af havnen", desc.getLongValue());
    }

    /* ===================================================================== */
    /* Helpers                                                               */
    /* ===================================================================== */

    private static void assertNotNullList(List<?> list) {
        assertTrue(list != null, "checkCreateDescs must have initialised the descs list");
    }
}
