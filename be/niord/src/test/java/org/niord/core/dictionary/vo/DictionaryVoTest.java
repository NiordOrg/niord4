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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link DictionaryVo} and {@link ExportedDictionaryVo}.
 *
 * <p>These VOs are pure in-memory data holders: no database, no CDI, no Quarkus. The
 * tests exercise the localized-lookup logic ({@code value}), the language-fallback
 * {@code Properties} flattening ({@code toProperties}), and the bidirectional
 * conversion between the map-keyed {@link DictionaryVo} and the list-based
 * {@link ExportedDictionaryVo} export form.
 */
public class DictionaryVoTest {

    /**
     * DICT4: a text left empty on purpose in one language is that language's text, so it prints nothing; only a
     * language without a description, or one whose value is null, falls back (to the default language, then to the
     * first non-blank value). The English {@code navwarn.date.time_interval_prefix} is empty on purpose and must not
     * print the Danish "kl.".
     */
    @Test
    void anEmptyTranslationIsKept_aMissingOneFallsBack() {
        // An empty English value beside a Danish one: English keeps its empty text.
        DictionaryVo dict = dictionary("template",
                entry("prefix", desc("en", ""), desc("da", "kl.")),
                entry("title", desc("en", "English"), desc("da", "Dansk")));
        Properties en = dict.toProperties("en", "da");
        assertTrue(en.containsKey("prefix"), "the empty English text is present, not left out");
        assertEquals("", en.getProperty("prefix"));
        assertEquals("", dict.value("en", "prefix", "da"));
        // The Danish text is still the Danish one.
        assertEquals("kl.", dict.value("da", "prefix", "da"));

        // A language without a description falls back to the default language, then to the first non-blank value.
        assertEquals("Dansk", dict.toProperties("kl", "da").getProperty("title"));
        assertEquals("Dansk", dict.value("kl", "title", "da"));
        assertEquals("English", dict.value("kl", "title", "fo"));
        assertEquals("kl.", dict.value("kl", "prefix", "da"));
        assertNull(dict.value("kl", "absent", "da"));

        // A value of blanks is kept as typed in its own language.
        DictionaryVo blank = dictionary("test",
                entry("title", desc("en", "English"), desc("kl", " "), desc("da", "Dansk")));
        assertEquals(" ", blank.value("kl", "title", "da"));

        // Only an empty English text, in an English-only dictionary: the key is present and empty.
        DictionaryVo englishOnly = dictionary("template", entry("prefix", desc("en", "")));
        Properties onlyEn = englishOnly.toProperties("en", "en");
        assertTrue(onlyEn.containsKey("prefix"), "the empty English text is present, not left out");
        assertEquals("", onlyEn.getProperty("prefix"));
        assertEquals("", englishOnly.value("en", "prefix", "en"));

        // A null value falls back as before: to the default language, then to the first non-blank value.
        DictionaryVo nullValue = dictionary("test",
                entry("title", desc("en", "English"), desc("kl", null), desc("da", "Dansk")));
        assertEquals("Dansk", nullValue.value("kl", "title", "da"));
        assertEquals("Dansk", nullValue.toProperties("kl", "da").getProperty("title"));
        assertEquals("English", nullValue.value("kl", "title", "fo"));

        // The default language's empty text is kept in the fallback step too.
        DictionaryVo emptyDefault = dictionary("template",
                entry("prefix", desc("da", ""), desc("en", "at")));
        assertEquals("", emptyDefault.value("kl", "prefix", "da"));
        // A null default value falls through to the first non-blank value.
        DictionaryVo nullDefault = dictionary("template",
                entry("prefix", desc("da", null), desc("en", "at")));
        assertEquals("at", nullDefault.value("kl", "prefix", "da"));
    }

    /* ===================================================================== */
    /* HELPERS                                                               */
    /* ===================================================================== */

    /**
     * Builds a {@link DictionaryEntryVo} with the given key and one localized desc
     * per (lang, value) pair, in the order supplied. The first pair becomes the
     * first desc, which is significant for the {@code toProperties} fallback logic.
     */
    private DictionaryEntryVo entry(String key, String[]... langValuePairs) {
        DictionaryEntryVo e = new DictionaryEntryVo();
        e.setKey(key);
        for (String[] pair : langValuePairs) {
            // createDesc(lang) appends a new desc; setValue carries the value (may be null)
            e.createDesc(pair[0]).setValue(pair[1]);
        }
        return e;
    }

    /** Convenience for a (lang, value) pair. */
    private String[] desc(String lang, String value) {
        return new String[] { lang, value };
    }

    /** Builds a DictionaryVo from a set of pre-built entries, keyed by entry key. */
    private DictionaryVo dictionary(String name, DictionaryEntryVo... entries) {
        DictionaryVo dict = new DictionaryVo();
        dict.setName(name);
        Map<String, DictionaryEntryVo> map = new HashMap<>();
        for (DictionaryEntryVo e : entries) {
            map.put(e.getKey(), e);
        }
        dict.setEntries(map);
        return dict;
    }

    /* ===================================================================== */
    /* DictionaryVo.value(lang, key)                                         */
    /* ===================================================================== */

    /** 1a. value() returns the desc value for the requested lang/key. */
    @Test
    public void value_returnsDescValueForLangAndKey() {
        DictionaryVo dict = dictionary("greetings", entry("greeting", desc("en", "Hello")));

        assertEquals("Hello", dict.value("en", "greeting"));
    }

    /** 1b. value() returns null for a key that does not exist in the dictionary. */
    @Test
    public void value_returnsNullForUnknownKey() {
        DictionaryVo dict = dictionary("greetings", entry("greeting", desc("en", "Hello")));

        assertNull(dict.value("en", "missing"), "An unknown key must resolve to null");
    }

    /**
     * 1c. value() returns null when the entry exists but has no desc for the
     * requested language. The "greeting" entry only carries an English desc, so a
     * lookup for "da" must yield null.
     *
     * <p>Note: {@code getDesc} matches a desc whose lang is null OR equal to the
     * requested lang. Here the only desc has lang "en" (non-null), so it does not
     * match "da" and the lookup returns null.
     */
    @Test
    public void value_returnsNullWhenEntryHasNoDescForLang() {
        DictionaryVo dict = dictionary("greetings", entry("greeting", desc("en", "Hello")));

        assertNull(dict.value("da", "greeting"), "No 'da' desc exists for 'greeting'");
    }

    /* ===================================================================== */
    /* DictionaryVo.toProperties(lang)                                       */
    /* ===================================================================== */

    /**
     * 2. toProperties("da") covers the full branch matrix of the flattening logic:
     *  - entry "a" has both en and da descs -> the da value "Hej" is used.
     *  - entry "b" only has an en desc      -> falls back to the FIRST desc value "OnlyEn".
     *  - entry "c" has a single en desc whose value is null -> skipped (no property).
     *  - entry "d" has an empty descs list  -> skipped (no property).
     */
    @Test
    public void toProperties_usesLangValueFallsBackToFirstAndSkipsNullOrEmpty() {
        DictionaryEntryVo a = entry("a", desc("en", "Hello"), desc("da", "Hej"));
        DictionaryEntryVo b = entry("b", desc("en", "OnlyEn"));
        DictionaryEntryVo c = entry("c", desc("en", null));
        DictionaryEntryVo d = new DictionaryEntryVo();
        d.setKey("d");
        d.setDescs(new ArrayList<>()); // empty descs list

        DictionaryVo dict = dictionary("mixed", a, b, c, d);

        Properties props = dict.toProperties("da");

        // "a": exact da match wins over the first (en) desc
        assertEquals("Hej", props.getProperty("a"));
        // "b": no da desc -> fall back to the first desc's value
        assertEquals("OnlyEn", props.getProperty("b"));
        // "c": resolved desc has a null value -> not stored
        assertNull(props.getProperty("c"), "Entries whose chosen desc value is null are skipped");
        // "d": empty descs -> not stored
        assertNull(props.getProperty("d"), "Entries with an empty descs list are skipped");

        // Exactly the two storable entries are present
        assertEquals(2, props.size());
        assertTrue(props.containsKey("a"));
        assertTrue(props.containsKey("b"));
        assertFalse(props.containsKey("c"));
        assertFalse(props.containsKey("d"));
    }

    /**
     * 2a. An entry without any text, as the mapper gives it (no list of descriptions at all: an edit on the
     * Dictionaries page that blanked every value leaves one), has no value in any language, and the dictionary's
     * other keys are still read, so a template or an audio text that loads the dictionary still renders.
     */
    @Test
    public void anEntryWithoutAnyTextHasNoValue() {
        DictionaryEntryVo empty = new DictionaryEntryVo();
        empty.setKey("footer");
        DictionaryVo dict = dictionary("web", empty, entry("title", desc("en", "English"), desc("da", "Dansk")));

        Properties props = dict.toProperties("da", "en");
        assertEquals(1, props.size(), "only the entry with a text contributes a value");
        assertEquals("Dansk", props.getProperty("title"));
        assertFalse(props.containsKey("footer"));
        assertNull(dict.value("da", "footer", "en"));
        assertNull(dict.value("da", "footer"));
    }

    /**
     * 2b. toProperties() with a matching language uses that language's value directly,
     * confirming the primary (non-fallback) branch.
     */
    @Test
    public void toProperties_usesExactLangValueWhenPresent() {
        DictionaryEntryVo a = entry("a", desc("en", "Hello"), desc("da", "Hej"));
        DictionaryVo dict = dictionary("greetings", a);

        Properties props = dict.toProperties("en");

        assertEquals("Hello", props.getProperty("a"));
        assertEquals(1, props.size());
    }

    /* ===================================================================== */
    /* DictionaryVo getters / setters                                        */
    /* ===================================================================== */

    /** 3a. getName/setName round-trips the name. */
    @Test
    public void name_roundTrips() {
        DictionaryVo dict = new DictionaryVo();
        dict.setName("web");

        assertEquals("web", dict.getName());
    }

    /** 3b. getEntries/setEntries round-trips the same map instance and its contents. */
    @Test
    public void entries_roundTrip() {
        DictionaryEntryVo e = entry("greeting", desc("en", "Hello"));
        Map<String, DictionaryEntryVo> map = new HashMap<>();
        map.put(e.getKey(), e);

        DictionaryVo dict = new DictionaryVo();
        dict.setEntries(map);

        assertSame(map, dict.getEntries(), "setEntries/getEntries must round-trip the same map instance");
        assertEquals(1, dict.getEntries().size());
        assertSame(e, dict.getEntries().get("greeting"));
    }

    /* ===================================================================== */
    /* ExportedDictionaryVo(DictionaryVo) constructor                        */
    /* ===================================================================== */

    /**
     * 4. The DictionaryVo-based constructor copies the name and flattens the entries
     * map's VALUES into the export list. The list size matches the map size and every
     * source key is represented among the exported entries.
     */
    @Test
    public void exportedConstructor_copiesNameAndFlattensEntries() {
        DictionaryVo dict = dictionary("export-src",
                entry("a", desc("en", "Hello")),
                entry("b", desc("en", "World")));

        ExportedDictionaryVo exported = new ExportedDictionaryVo(dict);

        assertEquals("export-src", exported.getName());

        List<DictionaryEntryVo> entries = exported.getEntries();
        assertEquals(2, entries.size());

        List<String> keys = new ArrayList<>();
        for (DictionaryEntryVo e : entries) {
            keys.add(e.getKey());
        }
        assertTrue(keys.contains("a"), "Exported entries must contain key 'a'");
        assertTrue(keys.contains("b"), "Exported entries must contain key 'b'");
    }

    /** 4b. The export constructor on an empty dictionary yields an empty (non-null) entries list. */
    @Test
    public void exportedConstructor_handlesEmptyDictionary() {
        DictionaryVo dict = new DictionaryVo();
        dict.setName("empty");

        ExportedDictionaryVo exported = new ExportedDictionaryVo(dict);

        assertEquals("empty", exported.getName());
        assertTrue(exported.getEntries().isEmpty(), "No entries should be exported from an empty dictionary");
    }

    /* ===================================================================== */
    /* ExportedDictionaryVo.toDictionaryVo()                                 */
    /* ===================================================================== */

    /**
     * 5. toDictionaryVo() rebuilds a map-keyed DictionaryVo: the name is carried over
     * and each entry is keyed by its own key, retaining the original entry instances.
     */
    @Test
    public void toDictionaryVo_rebuildsMapKeyedByEntryKey() {
        DictionaryEntryVo a = entry("a", desc("en", "Hello"));
        DictionaryEntryVo b = entry("b", desc("en", "World"));

        ExportedDictionaryVo exported = new ExportedDictionaryVo();
        exported.setName("round-trip");
        exported.setEntries(new ArrayList<>(List.of(a, b)));

        DictionaryVo dict = exported.toDictionaryVo();

        assertEquals("round-trip", dict.getName());
        assertEquals(2, dict.getEntries().size());
        assertSame(a, dict.getEntries().get("a"), "Entry 'a' must be keyed by its own key");
        assertSame(b, dict.getEntries().get("b"), "Entry 'b' must be keyed by its own key");
        // The resolved value survives the round-trip
        assertEquals("Hello", dict.value("en", "a"));
    }

    /**
     * 5b. A full DictionaryVo -> ExportedDictionaryVo -> DictionaryVo round-trip
     * preserves the name and every key/value.
     */
    @Test
    public void exportAndImport_roundTripsDictionary() {
        DictionaryVo original = dictionary("full",
                entry("a", desc("en", "Hello"), desc("da", "Hej")),
                entry("b", desc("en", "World")));

        DictionaryVo restored = new ExportedDictionaryVo(original).toDictionaryVo();

        assertEquals("full", restored.getName());
        assertEquals(2, restored.getEntries().size());
        assertEquals("Hello", restored.value("en", "a"));
        assertEquals("Hej", restored.value("da", "a"));
        assertEquals("World", restored.value("en", "b"));
    }

    /* ===================================================================== */
    /* ExportedDictionaryVo default constructor + getters/setters            */
    /* ===================================================================== */

    /** 6a. The default constructor produces an empty (non-null) entries list. */
    @Test
    public void exportedDefaultConstructor_startsWithEmptyEntries() {
        ExportedDictionaryVo exported = new ExportedDictionaryVo();

        assertNull(exported.getName(), "Name defaults to null");
        assertTrue(exported.getEntries().isEmpty(), "Entries default to an empty list");
    }

    /** 6b. getName/setName and getEntries/setEntries round-trip on the export VO. */
    @Test
    public void exportedGettersSetters_roundTrip() {
        DictionaryEntryVo e = entry("greeting", desc("en", "Hello"));
        List<DictionaryEntryVo> list = new ArrayList<>(List.of(e));

        ExportedDictionaryVo exported = new ExportedDictionaryVo();
        exported.setName("web");
        exported.setEntries(list);

        assertEquals("web", exported.getName());
        assertSame(list, exported.getEntries(), "setEntries/getEntries must round-trip the same list instance");
        assertEquals(1, exported.getEntries().size());
        assertSame(e, exported.getEntries().get(0));
    }
}
