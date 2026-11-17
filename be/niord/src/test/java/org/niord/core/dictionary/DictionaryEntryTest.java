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
import org.niord.model.DataFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Framework-neutral unit tests for the {@link DictionaryEntry} entity and its localized
 * {@link DictionaryEntryDesc} children.
 *
 * <p>The behavior under test is pure in-memory object manipulation: localized-description
 * creation, entity &lt;-&gt; VO conversion ({@link DictionaryEntry#toVo(DataFilter)} and the
 * {@code DictionaryEntry(DictionaryEntryVo)} constructor), merge semantics
 * ({@link DictionaryEntry#updateDictionaryEntry(DictionaryEntry)}) and the
 * {@link DataFilter}-driven language filtering inherited from {@code ILocalizable}.
 * No database, CDI or Quarkus is involved, so these tests survive a future Spring migration.
 */
public class DictionaryEntryTest {

    /* ===================================================================== */
    /* createDesc                                                            */
    /* ===================================================================== */

    /**
     * 1. createDesc("en") creates a DictionaryEntryDesc with the given language, wires the
     * back-reference to the owning entry and registers it in the entry's desc list.
     */
    @Test
    public void createDesc_setsLangBackReferenceAndAddsToDescs() {
        DictionaryEntry entry = new DictionaryEntry();

        DictionaryEntryDesc desc = entry.createDesc("en");

        // Language is set on the new desc.
        assertEquals("en", desc.getLang());
        // The desc is the one retrievable through the localizable lookup.
        assertSame(desc, entry.getDesc("en"));
        // The back-reference points to the owning entry.
        assertSame(entry, entry.getDesc("en").getEntity());
        // Exactly one desc was added.
        assertEquals(1, entry.getDescs().size());
    }

    /* ===================================================================== */
    /* Constructor from VO                                                   */
    /* ===================================================================== */

    /**
     * 2. The DictionaryEntryVo constructor copies id, key, atonFilter and the localized
     * descriptions (each value copied via createDesc(...).copyDesc(new DictionaryEntryDesc(desc))).
     */
    @Test
    public void constructorFromVo_copiesScalarFieldsAndDescs() {
        DictionaryEntryVo vo = new DictionaryEntryVo();
        vo.setId(3);
        vo.setKey("k");
        vo.setAtonFilter("af");
        vo.createDesc("en").setValue("V");

        DictionaryEntry entry = new DictionaryEntry(vo);

        assertEquals(Integer.valueOf(3), entry.getId());
        assertEquals("k", entry.getKey());
        assertEquals("af", entry.getAtonFilter());

        // The localized value was copied across and is reachable by language.
        assertEquals(1, entry.getDescs().size());
        DictionaryEntryDesc enDesc = entry.getDesc("en");
        assertNotNull(enDesc, "Expected an 'en' desc to be created from the VO");
        assertEquals("V", enDesc.getValue());
        // The desc created during construction is wired back to its owning entry.
        assertSame(entry, enDesc.getEntity());
    }

    /* ===================================================================== */
    /* updateDictionaryEntry                                                 */
    /* ===================================================================== */

    /**
     * 3. updateDictionaryEntry copies key + atonFilter, updates an existing localized desc in
     * place (reusing the same desc object) and creates a brand-new desc for a language that did
     * not yet exist on the target.
     */
    @Test
    public void updateDictionaryEntry_updatesExistingDescAndCreatesNewOnes() {
        DictionaryEntry target = makeEntry("k1", "en", "Old");
        DictionaryEntryDesc originalEnDesc = target.getDesc("en");

        // Source carries a new key/filter, an updated 'en' value and an additional 'da' desc.
        DictionaryEntry src = makeEntry("k2", "en", "New");
        src.setAtonFilter("x");
        src.createDesc("da").setValue("Dansk");

        target.updateDictionaryEntry(src);

        // Scalar fields are taken from the source.
        assertEquals("k2", target.getKey());
        assertEquals("x", target.getAtonFilter());

        // The existing 'en' desc is updated in place: same object, new value.
        assertEquals("New", target.getDesc("en").getValue());
        assertSame(originalEnDesc, target.getDesc("en"),
                "Existing desc object must be reused, not replaced");

        // The previously absent 'da' desc was created.
        assertNotNull(target.getDesc("da"), "A 'da' desc should have been created");
        assertEquals("Dansk", target.getDesc("da").getValue());

        // Two descs in total: the reused 'en' and the new 'da'.
        assertEquals(2, target.getDescs().size());
    }

    /* ===================================================================== */
    /* toVo                                                                  */
    /* ===================================================================== */

    /**
     * 4. toVo(DataFilter.get()) carries the scalar fields (id, key, atonFilter) and the localized
     * description across to the value object.
     */
    @Test
    public void toVo_carriesScalarFieldsAndDescs() {
        DictionaryEntry entry = makeEntry("k", "en", "V");
        entry.setId(42);
        entry.setAtonFilter("af");

        DictionaryEntryVo vo = entry.toVo(DataFilter.get());

        assertEquals(Integer.valueOf(42), vo.getId());
        assertEquals("k", vo.getKey());
        assertEquals("af", vo.getAtonFilter());
        assertNotNull(vo.getDescs(), "Expected the VO to carry descs");
        assertEquals(1, vo.getDescs().size());
        assertEquals("V", vo.getDesc("en").getValue());
    }

    /**
     * 5a. toVo with a language-restricted filter emits only the matching localized description.
     */
    @Test
    public void toVo_withLangFilter_emitsOnlyMatchingDesc() {
        DictionaryEntry entry = makeEntry("k", "en", "EN");
        entry.createDesc("da").setValue("DA");

        DictionaryEntryVo vo = entry.toVo(DataFilter.get().lang("da"));

        assertEquals(1, vo.getDescs().size(),
                "Only the 'da' desc should survive a lang('da') filter");
        assertNotNull(vo.getDesc("da"));
        assertEquals("DA", vo.getDesc("da").getValue());
        assertNull(vo.getDesc("en"), "The 'en' desc must be filtered out");
    }

    /**
     * 5b. toVo with an unrestricted filter (no language) emits every localized description.
     */
    @Test
    public void toVo_withoutLangFilter_emitsAllDescs() {
        DictionaryEntry entry = makeEntry("k", "en", "EN");
        entry.createDesc("da").setValue("DA");

        DictionaryEntryVo vo = entry.toVo(DataFilter.get());

        assertEquals(2, vo.getDescs().size(), "Both descs should survive an unrestricted filter");
        assertEquals("EN", vo.getDesc("en").getValue());
        assertEquals("DA", vo.getDesc("da").getValue());
    }

    /* ===================================================================== */
    /* Getters / setters round-trip                                          */
    /* ===================================================================== */

    /**
     * 6. The plain getters/setters for dictionary, key, atonFilter and descs round-trip the values
     * they are given.
     */
    @Test
    public void gettersAndSetters_roundTripValues() {
        DictionaryEntry entry = new DictionaryEntry();

        Dictionary dictionary = new Dictionary();
        entry.setDictionary(dictionary);
        assertSame(dictionary, entry.getDictionary());

        entry.setKey("the-key");
        assertEquals("the-key", entry.getKey());

        entry.setAtonFilter("the-filter");
        assertEquals("the-filter", entry.getAtonFilter());

        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setLang("en");
        desc.setValue("v");
        List<DictionaryEntryDesc> descs = List.of(desc);
        entry.setDescs(descs);
        assertEquals(descs, entry.getDescs());
        assertEquals(1, entry.getDescs().size());
        assertSame(desc, entry.getDescs().get(0));
    }

    /* ===================================================================== */
    /* Helpers                                                               */
    /* ===================================================================== */

    /** Builds a DictionaryEntry with the given key and a single (lang, value) localized desc. */
    private DictionaryEntry makeEntry(String key, String lang, String value) {
        DictionaryEntry entry = new DictionaryEntry();
        entry.setKey(key);
        entry.createDesc(lang).setValue(value);
        return entry;
    }
}
