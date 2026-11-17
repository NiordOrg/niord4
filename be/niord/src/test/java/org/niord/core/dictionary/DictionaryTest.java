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
package org.niord.core.dictionary;

import org.junit.jupiter.api.Test;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.DictionaryVo;
import org.niord.model.DataFilter;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the {@link Dictionary} entity.
 *
 * <p>These tests exercise pure in-memory object behavior of {@link Dictionary}:
 * its {@link Dictionary#createEntry(String) createEntry} factory, the
 * {@code Dictionary(DictionaryVo)} construction-from-VO path, the
 * {@link Dictionary#toVo(DataFilter) toVo} conversion, and the
 * name/entries accessors. There is no database, no CDI and no Quarkus
 * involvement: a {@link Dictionary} is built directly and inspected. All VO
 * conversions use {@link DataFilter#get()}.
 */
public class DictionaryTest {

    /* ===================================================================== */
    /* HELPERS                                                               */
    /* ===================================================================== */

    /**
     * Builds a {@link DictionaryEntry} with the given key and a single English
     * ("en") description carrying {@code enValue}. The entry is created via the
     * entity's own {@code createDesc("en")} so the desc is wired back to the entry.
     */
    private DictionaryEntry entry(String key, String enValue) {
        DictionaryEntry e = new DictionaryEntry();
        e.setKey(key);
        DictionaryEntryDesc desc = e.createDesc("en");
        desc.setValue(enValue);
        return e;
    }

    /**
     * Builds a {@link DictionaryEntryVo} with the given key and a single English
     * ("en") description carrying {@code enValue}. The desc is created via the
     * VO's own {@code createDesc("en")} so the lang is set.
     */
    private DictionaryEntryVo entryVo(String key, String enValue) {
        DictionaryEntryVo vo = new DictionaryEntryVo();
        vo.setKey(key);
        vo.createDesc("en").setValue(enValue);
        return vo;
    }

    /* ===================================================================== */
    /* createEntry                                                           */
    /* ===================================================================== */

    /** 1. createEntry(key) stores the entry under that key with the key set on it. */
    @Test
    public void createEntry_putsEntryUnderKeyWithKeySet() {
        Dictionary dict = new Dictionary();

        DictionaryEntry created = dict.createEntry("k");

        // The returned entry and the stored entry are the same object, keyed by "k".
        assertTrue(dict.getEntries().containsKey("k"), "entries map must contain the new key");
        assertSame(created, dict.getEntries().get("k"), "the stored entry must be the returned entry");
        assertEquals("k", dict.getEntries().get("k").getKey());
    }

    /** 2. createEntry(key) wires the entry's dictionary back-reference to this Dictionary. */
    @Test
    public void createEntry_setsBackReferenceToOwningDictionary() {
        Dictionary dict = new Dictionary();

        DictionaryEntry created = dict.createEntry("k");

        assertSame(dict, dict.getEntries().get("k").getDictionary(),
                "the entry's dictionary must be the owning dictionary");
        assertSame(dict, created.getDictionary(),
                "the returned entry must also carry the back-reference");
    }

    /** 3. createEntry called twice with distinct keys stores both entries independently. */
    @Test
    public void createEntry_withTwoKeysStoresBothIndependently() {
        Dictionary dict = new Dictionary();

        DictionaryEntry a = dict.createEntry("a");
        DictionaryEntry b = dict.createEntry("b");

        assertEquals(2, dict.getEntries().size());
        assertSame(a, dict.getEntries().get("a"));
        assertSame(b, dict.getEntries().get("b"));
        assertEquals("a", a.getKey());
        assertEquals("b", b.getKey());
    }

    /* ===================================================================== */
    /* Constructor from DictionaryVo                                         */
    /* ===================================================================== */

    /** 4. new Dictionary(vo) copies the name and builds an entry per VO entry, keyed by key. */
    @Test
    public void constructorFromVo_copiesNameAndEntries() {
        DictionaryVo vo = new DictionaryVo();
        vo.setName("web");
        Map<String, DictionaryEntryVo> voEntries = new HashMap<>();
        voEntries.put("a", entryVo("a", "Alpha"));
        voEntries.put("b", entryVo("b", "Bravo"));
        vo.setEntries(voEntries);

        Dictionary dict = new Dictionary(vo);

        assertEquals("web", dict.getName());
        assertEquals(2, dict.getEntries().size());
        assertTrue(dict.getEntries().containsKey("a"));
        assertTrue(dict.getEntries().containsKey("b"));
    }

    /** 5. new Dictionary(vo) builds the localized descriptions via createEntry + updateDictionaryEntry. */
    @Test
    public void constructorFromVo_buildsLocalizedDescriptions() {
        DictionaryVo vo = new DictionaryVo();
        vo.setName("web");
        Map<String, DictionaryEntryVo> voEntries = new HashMap<>();
        voEntries.put("a", entryVo("a", "Alpha"));
        voEntries.put("b", entryVo("b", "Bravo"));
        vo.setEntries(voEntries);

        Dictionary dict = new Dictionary(vo);

        DictionaryEntry a = dict.getEntries().get("a");
        assertEquals("a", a.getKey());
        assertEquals("Alpha", a.getDesc("en").getValue());

        DictionaryEntry b = dict.getEntries().get("b");
        assertEquals("b", b.getKey());
        assertEquals("Bravo", b.getDesc("en").getValue());
    }

    /** 6. new Dictionary(vo) wires each built entry's dictionary back-reference to the new Dictionary. */
    @Test
    public void constructorFromVo_wiresEntryBackReferences() {
        DictionaryVo vo = new DictionaryVo();
        vo.setName("web");
        Map<String, DictionaryEntryVo> voEntries = new HashMap<>();
        voEntries.put("a", entryVo("a", "Alpha"));
        vo.setEntries(voEntries);

        Dictionary dict = new Dictionary(vo);

        assertSame(dict, dict.getEntries().get("a").getDictionary(),
                "entry created via the VO constructor must point back at the dictionary");
    }

    /* ===================================================================== */
    /* toVo                                                                  */
    /* ===================================================================== */

    /** 7. toVo() copies the name and emits one entry VO per entry, keyed by the entry key. */
    @Test
    public void toVo_copiesNameAndEntriesKeyedByKey() {
        Dictionary dict = new Dictionary();
        dict.setName("d");
        dict.getEntries().put("k", entry("k", "V"));

        DictionaryVo vo = dict.toVo(DataFilter.get());

        assertEquals("d", vo.getName());
        assertTrue(vo.getEntries().containsKey("k"), "the VO entry map must be keyed by the entry key");
        assertEquals(1, vo.getEntries().size());
        assertEquals("k", vo.getEntries().get("k").getKey());
    }

    /** 8. toVo() carries the localized "en" value through to the entry VO. */
    @Test
    public void toVo_carriesLocalizedValue() {
        Dictionary dict = new Dictionary();
        dict.setName("d");
        dict.getEntries().put("k", entry("k", "V"));

        DictionaryVo vo = dict.toVo(DataFilter.get());

        DictionaryEntryVo entryVo = vo.getEntries().get("k");
        assertEquals("V", entryVo.getDesc("en").getValue());
    }

    /** 9. toVo() on a dictionary with no entries yields an empty (non-null) entry map. */
    @Test
    public void toVo_withNoEntriesYieldsEmptyMap() {
        Dictionary dict = new Dictionary();
        dict.setName("empty");

        DictionaryVo vo = dict.toVo(DataFilter.get());

        assertEquals("empty", vo.getName());
        assertTrue(vo.getEntries().isEmpty(),
                "the VO entry map must be empty when there are no entries");
    }

    /** 10. Constructing from a VO and converting back yields a VO with the same name, keys and value. */
    @Test
    public void constructFromVoThenToVo_roundTripsNameKeysAndValue() {
        DictionaryVo source = new DictionaryVo();
        source.setName("web");
        Map<String, DictionaryEntryVo> voEntries = new HashMap<>();
        voEntries.put("a", entryVo("a", "Alpha"));
        voEntries.put("b", entryVo("b", "Bravo"));
        source.setEntries(voEntries);

        DictionaryVo result = new Dictionary(source).toVo(DataFilter.get());

        assertEquals("web", result.getName());
        assertEquals(2, result.getEntries().size());
        assertEquals("Alpha", result.getEntries().get("a").getDesc("en").getValue());
        assertEquals("Bravo", result.getEntries().get("b").getDesc("en").getValue());
    }

    /* ===================================================================== */
    /* Getters / Setters                                                     */
    /* ===================================================================== */

    /** 11. getName/setName round-trips the dictionary name. */
    @Test
    public void getName_setName_roundTrip() {
        Dictionary dict = new Dictionary();
        assertNull(dict.getName(), "a fresh dictionary has no name");

        dict.setName("messages");
        assertEquals("messages", dict.getName());
    }

    /** 12. getEntries/setEntries round-trips the entries map and replaces the default map. */
    @Test
    public void getEntries_setEntries_roundTrip() {
        Dictionary dict = new Dictionary();
        // A fresh dictionary starts with an empty (non-null) entries map.
        assertTrue(dict.getEntries().isEmpty(), "a fresh dictionary starts with an empty entries map");

        Map<String, DictionaryEntry> replacement = new HashMap<>();
        replacement.put("k", entry("k", "V"));
        dict.setEntries(replacement);

        assertSame(replacement, dict.getEntries(), "getEntries must return the map set via setEntries");
        assertEquals(1, dict.getEntries().size());
        assertEquals("k", dict.getEntries().get("k").getKey());
    }

    /** 13. createEntry mutates the live entries map returned by getEntries. */
    @Test
    public void createEntry_isVisibleThroughGetEntries() {
        Dictionary dict = new Dictionary();
        assertFalse(dict.getEntries().containsKey("k"));

        dict.createEntry("k");

        assertTrue(dict.getEntries().containsKey("k"),
                "createEntry must mutate the same map exposed by getEntries");
    }
}
