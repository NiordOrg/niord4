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
import org.niord.core.dictionary.vo.DictionaryEntryDescVo;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.DictionaryVo;
import org.niord.model.DataFilter;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DictionaryMapper}: pins the entity&lt;-&gt;VO projection that was previously
 * embedded in {@code Dictionary.toVo(DataFilter)} / the {@code Dictionary(DictionaryVo)} constructor
 * (and the owned {@link DictionaryEntry}/{@link DictionaryEntryDesc} conversions), so the extraction is
 * provably behaviour-preserving. Runs as a plain unit test with no persistence context.
 */
public class DictionaryMapperTest {

    private final DictionaryMapper mapper = new DictionaryMapper();

    /* ===================================================================== */
    /* HELPERS                                                               */
    /* ===================================================================== */

    /** Builds a {@link DictionaryEntry} with the given key, atonFilter and an "en"/"da" desc pair. */
    private DictionaryEntry entry(String key, String atonFilter, String enValue, String daValue) {
        DictionaryEntry e = new DictionaryEntry();
        e.setId(7);
        e.setKey(key);
        e.setAtonFilter(atonFilter);
        DictionaryEntryDesc en = e.createDesc("en");
        en.setValue(enValue);
        en.setLongValue(enValue + "-long");
        DictionaryEntryDesc da = e.createDesc("da");
        da.setValue(daValue);
        da.setLongValue(daValue + "-long");
        return e;
    }

    /** Builds a {@link DictionaryEntryVo} with the given key, atonFilter and a single "en" desc. */
    private DictionaryEntryVo entryVo(Integer id, String key, String atonFilter, String enValue) {
        DictionaryEntryVo vo = new DictionaryEntryVo();
        vo.setId(id);
        vo.setKey(key);
        vo.setAtonFilter(atonFilter);
        DictionaryEntryDescVo desc = vo.createDesc("en");
        desc.setValue(enValue);
        desc.setLongValue(enValue + "-long");
        return vo;
    }

    /* ===================================================================== */
    /* toVo                                                                  */
    /* ===================================================================== */

    /** toVo copies the name and emits one entry VO per entry, keyed by the entry key. */
    @Test
    public void toVo_copiesNameAndEntriesKeyedByKey() {
        Dictionary dict = new Dictionary();
        dict.setName("web");
        dict.getEntries().put("a", entry("a", "tag=light", "Alpha", "Alfa"));
        dict.getEntries().put("b", entry("b", null, "Bravo", "Bravo-da"));

        DictionaryVo vo = mapper.toVo(dict, DataFilter.get().fields(DataFilter.ALL));

        assertEquals("web", vo.getName());
        assertEquals(2, vo.getEntries().size());
        assertTrue(vo.getEntries().containsKey("a"));
        assertTrue(vo.getEntries().containsKey("b"));
        assertEquals("a", vo.getEntries().get("a").getKey());
        assertEquals("b", vo.getEntries().get("b").getKey());
    }

    /** toVo carries the entry's id, key, atonFilter and the localized desc fields through. */
    @Test
    public void toVo_carriesEntryFieldsAndAllDescs() {
        Dictionary dict = new Dictionary();
        dict.setName("d");
        dict.getEntries().put("k", entry("k", "tag=buoy", "Value", "Vaerdi"));

        DictionaryVo vo = mapper.toVo(dict, DataFilter.get().fields(DataFilter.ALL));

        DictionaryEntryVo entryVo = vo.getEntries().get("k");
        assertEquals(Integer.valueOf(7), entryVo.getId());
        assertEquals("k", entryVo.getKey());
        assertEquals("tag=buoy", entryVo.getAtonFilter());
        // No language filter => both descs are carried, with both value and longValue.
        assertEquals(2, entryVo.getDescs().size());
        DictionaryEntryDescVo en = entryVo.getDesc("en");
        assertEquals("Value", en.getValue());
        assertEquals("Value-long", en.getLongValue());
        DictionaryEntryDescVo da = entryVo.getDesc("da");
        assertEquals("Vaerdi", da.getValue());
        assertEquals("Vaerdi-long", da.getLongValue());
    }

    /** A language filter restricts the descs to the requested language only. */
    @Test
    public void toVo_languageFilterRestrictsDescs() {
        Dictionary dict = new Dictionary();
        dict.setName("d");
        dict.getEntries().put("k", entry("k", null, "Value", "Vaerdi"));

        DictionaryVo vo = mapper.toVo(dict, DataFilter.get().fields(DataFilter.ALL).lang("da"));

        DictionaryEntryVo entryVo = vo.getEntries().get("k");
        assertEquals(1, entryVo.getDescs().size(), "only the 'da' desc must survive the lang filter");
        assertEquals("da", entryVo.getDescs().get(0).getLang());
        assertEquals("Vaerdi", entryVo.getDescs().get(0).getValue());
        assertNull(entryVo.getDesc("en"), "the filtered-out 'en' desc must be absent");
    }

    /** When no desc matches the language filter, the first available desc is included as a fallback. */
    @Test
    public void toVo_languageFilterFallsBackToFirstDescWhenNoMatch() {
        Dictionary dict = new Dictionary();
        dict.setName("d");
        dict.getEntries().put("k", entry("k", null, "Value", "Vaerdi"));

        DictionaryVo vo = mapper.toVo(dict, DataFilter.get().fields(DataFilter.ALL).lang("fr"));

        DictionaryEntryVo entryVo = vo.getEntries().get("k");
        assertEquals(1, entryVo.getDescs().size(), "a non-matching lang filter falls back to the first desc");
        assertEquals("en", entryVo.getDescs().get(0).getLang());
        assertEquals("Value", entryVo.getDescs().get(0).getValue());
    }

    /** An entry with no descs leaves the VO descs untouched (null), mirroring the entity's guard. */
    @Test
    public void toVo_entryWithoutDescsLeavesVoDescsNull() {
        Dictionary dict = new Dictionary();
        dict.setName("d");
        DictionaryEntry e = new DictionaryEntry();
        e.setKey("k");
        e.setAtonFilter("tag=x");
        dict.getEntries().put("k", e);

        DictionaryVo vo = mapper.toVo(dict, DataFilter.get().fields(DataFilter.ALL));

        DictionaryEntryVo entryVo = vo.getEntries().get("k");
        assertEquals("k", entryVo.getKey());
        assertEquals("tag=x", entryVo.getAtonFilter());
        assertNull(entryVo.getDescs(), "descs must stay null when the entry has no descriptions");
    }

    /** toVo on a dictionary with no entries yields an empty (non-null) entry map. */
    @Test
    public void toVo_withNoEntriesYieldsEmptyMap() {
        Dictionary dict = new Dictionary();
        dict.setName("empty");

        DictionaryVo vo = mapper.toVo(dict, DataFilter.get().fields(DataFilter.ALL));

        assertEquals("empty", vo.getName());
        assertTrue(vo.getEntries().isEmpty(), "the VO entry map must be empty when there are no entries");
    }

    /** A null entity maps to a null VO. */
    @Test
    public void toVo_nullEntityMapsToNull() {
        assertNull(mapper.toVo(null, DataFilter.get().fields(DataFilter.ALL)));
    }

    /* ===================================================================== */
    /* toEntity                                                              */
    /* ===================================================================== */

    /** toEntity copies the name and builds one entry per VO entry, keyed by key, with descs. */
    @Test
    public void toEntity_copiesNameEntriesAndDescs() {
        DictionaryVo vo = new DictionaryVo();
        vo.setName("web");
        Map<String, DictionaryEntryVo> voEntries = new HashMap<>();
        voEntries.put("a", entryVo(11, "a", "tag=light", "Alpha"));
        voEntries.put("b", entryVo(12, "b", null, "Bravo"));
        vo.setEntries(voEntries);

        Dictionary dict = mapper.toEntity(vo);

        assertEquals("web", dict.getName());
        assertEquals(2, dict.getEntries().size());

        DictionaryEntry a = dict.getEntries().get("a");
        assertEquals("a", a.getKey());
        assertEquals("tag=light", a.getAtonFilter());
        assertEquals("Alpha", a.getDesc("en").getValue());
        // The back-reference must be wired to the owning dictionary.
        assertSame(dict, a.getDictionary());

        DictionaryEntry b = dict.getEntries().get("b");
        assertEquals("b", b.getKey());
        assertNull(b.getAtonFilter());
        assertEquals("Bravo", b.getDesc("en").getValue());
    }

    /** toEntity wires each built entry's dictionary back-reference to the new Dictionary. */
    @Test
    public void toEntity_wiresEntryBackReferences() {
        DictionaryVo vo = new DictionaryVo();
        vo.setName("web");
        Map<String, DictionaryEntryVo> voEntries = new HashMap<>();
        voEntries.put("a", entryVo(1, "a", null, "Alpha"));
        vo.setEntries(voEntries);

        Dictionary dict = mapper.toEntity(vo);

        assertSame(dict, dict.getEntries().get("a").getDictionary(), "entry built by toEntity must point back at the dictionary");
    }

    /** A null VO maps to a null entity. */
    @Test
    public void toEntity_nullVoMapsToNull() {
        assertNull(mapper.toEntity(null));
    }

    /* ===================================================================== */
    /* Round-trip                                                            */
    /* ===================================================================== */

    /** Entity -> VO -> entity -> VO preserves name, keys, atonFilter and the localized "en" value. */
    @Test
    public void roundTrip_preservesNameKeysAtonFilterAndValue() {
        Dictionary source = new Dictionary();
        source.setName("web");
        source.getEntries().put("a", entry("a", "tag=light", "Alpha", "Alfa"));

        // entity -> VO (single language to keep one desc per entry through the round trip)
        DictionaryVo vo = mapper.toVo(source, DataFilter.get().fields(DataFilter.ALL).lang("en"));
        // VO -> entity
        Dictionary rebuilt = mapper.toEntity(vo);
        // entity -> VO again
        DictionaryVo result = mapper.toVo(rebuilt, DataFilter.get().fields(DataFilter.ALL).lang("en"));

        assertEquals("web", result.getName());
        assertEquals(1, result.getEntries().size());
        DictionaryEntryVo a = result.getEntries().get("a");
        assertEquals("a", a.getKey());
        assertEquals("tag=light", a.getAtonFilter());
        assertEquals("Alpha", a.getDesc("en").getValue());
        assertEquals("Alpha-long", a.getDesc("en").getLongValue());
    }
}
