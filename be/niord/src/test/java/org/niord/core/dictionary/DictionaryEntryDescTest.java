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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the {@link DictionaryEntryDesc} entity.
 *
 * <p>These tests exercise the pure in-memory behavior of the localized description
 * entity: construction from its {@link DictionaryEntryDescVo} value object, conversion
 * back to a VO, the {@code descDefined()} predicate, the {@code copyDesc()} field copy,
 * and the value/longValue/lang accessors. No database, no CDI, no Quarkus is involved.
 *
 * <p>Two behaviors are deliberately pinned because they are easy to get wrong:
 * <ul>
 *   <li>{@code descDefined()} is driven by {@code value} ONLY; a non-blank {@code longValue}
 *       does NOT make the desc "defined".</li>
 *   <li>The entity's {@code copyDesc()} copies BOTH {@code value} and {@code longValue},
 *       unlike the VO's {@code copyDesc()} which copies only {@code value}.</li>
 * </ul>
 */
public class DictionaryEntryDescTest {

    /* ===================================================================== */
    /* CONSTRUCTION FROM VO                                                  */
    /* ===================================================================== */

    /** 1. new DictionaryEntryDesc(vo) copies lang (via super), value and longValue. */
    @Test
    public void constructorFromVo_copiesLangValueAndLongValue() {
        DictionaryEntryDescVo vo = new DictionaryEntryDescVo();
        vo.setLang("da");
        vo.setValue("V");
        vo.setLongValue("LV");

        DictionaryEntryDesc desc = new DictionaryEntryDesc(vo);

        // lang is carried through the DescEntity super-constructor
        assertEquals("da", desc.getLang());
        assertEquals("V", desc.getValue());
        assertEquals("LV", desc.getLongValue());
    }

    /* ===================================================================== */
    /* CONVERSION TO VO                                                      */
    /* ===================================================================== */

    /** 2. toVo() returns a VO with the same lang, value and longValue. */
    @Test
    public void toVo_carriesLangValueAndLongValue() {
        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setLang("en");
        desc.setValue("Hello");
        desc.setLongValue("A longer hello");

        DictionaryEntryDescVo vo = desc.toVo();

        assertEquals("en", vo.getLang());
        assertEquals("Hello", vo.getValue());
        assertEquals("A longer hello", vo.getLongValue());
    }

    /* ===================================================================== */
    /* descDefined()  -- driven by VALUE only                               */
    /* ===================================================================== */

    /** 3a. descDefined() is true when value is non-blank. */
    @Test
    public void descDefined_trueWhenValueNonBlank() {
        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setValue("non-blank");

        assertTrue(desc.descDefined(), "A non-blank value must make the desc defined");
    }

    /** 3b. descDefined() is false when value is null. */
    @Test
    public void descDefined_falseWhenValueNull() {
        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setValue(null);

        assertFalse(desc.descDefined(), "A null value must make the desc undefined");
    }

    /** 3c. descDefined() is false when value is the empty string. */
    @Test
    public void descDefined_falseWhenValueEmpty() {
        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setValue("");

        assertFalse(desc.descDefined(), "An empty value must make the desc undefined");
    }

    /**
     * 3d. descDefined() is driven by VALUE only: a non-blank longValue does NOT
     * make the desc defined when value is null.
     */
    @Test
    public void descDefined_falseWhenOnlyLongValueSet() {
        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setValue(null);
        desc.setLongValue("x");

        assertFalse(desc.descDefined(),
                "longValue must NOT affect descDefined(); only value counts");
    }

    /* ===================================================================== */
    /* copyDesc()  -- copies BOTH value and longValue                       */
    /* ===================================================================== */

    /**
     * 4. copyDesc() copies BOTH value and longValue from the source entity.
     * (Unlike the VO's copyDesc(), which copies only value.)
     */
    @Test
    public void copyDesc_copiesValueAndLongValue() {
        DictionaryEntryDesc source = new DictionaryEntryDesc();
        source.setValue("v2");
        source.setLongValue("lv2");

        DictionaryEntryDesc target = new DictionaryEntryDesc();
        assertNull(target.getValue(), "Pre-condition: target value starts null");
        assertNull(target.getLongValue(), "Pre-condition: target longValue starts null");

        target.copyDesc(source);

        assertEquals("v2", target.getValue());
        assertEquals("lv2", target.getLongValue());
    }

    /* ===================================================================== */
    /* ACCESSOR ROUND-TRIPS                                                  */
    /* ===================================================================== */

    /** 5a. getValue/setValue round-trip (including overwrite). */
    @Test
    public void value_roundTrips() {
        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setValue("first");
        assertEquals("first", desc.getValue());

        desc.setValue("second");
        assertEquals("second", desc.getValue());
    }

    /** 5b. getLongValue/setLongValue round-trip (including overwrite). */
    @Test
    public void longValue_roundTrips() {
        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setLongValue("long-first");
        assertEquals("long-first", desc.getLongValue());

        desc.setLongValue("long-second");
        assertEquals("long-second", desc.getLongValue());
    }

    /** 5c. getLang/setLang round-trip (inherited from DescEntity). */
    @Test
    public void lang_roundTrips() {
        DictionaryEntryDesc desc = new DictionaryEntryDesc();
        desc.setLang("da");
        assertEquals("da", desc.getLang());

        desc.setLang("en");
        assertEquals("en", desc.getLang());
    }
}
