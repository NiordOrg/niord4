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

package org.niord.core.settings;

import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the {@link Setting} entity.
 *
 * <p>These tests exercise pure in-memory behavior of the constructors, the
 * {@code updateType()} type inference, the fluent method-chaining API and the
 * {@code Type} enum: no database, no CDI, no Quarkus. The {@code value} column's
 * JSON {@code @Convert} only applies at the JPA layer, so in-memory the value is
 * exactly the object that was supplied.
 */
public class SettingTest {

    /* ===================================================================== */
    /* CONSTRUCTORS                                                          */
    /* ===================================================================== */

    /** 1. The default constructor leaves key/value/type null (updateType() is a no-op for a null value). */
    @Test
    public void defaultConstructor_leavesKeyValueAndTypeNull() {
        Setting s = new Setting();

        assertNull(s.getKey(), "key must be null");
        assertNull(s.getValue(), "value must be null");
        assertNull(s.getType(), "type must stay null when value is null");
    }

    /** 2. new Setting(key) sets the key, leaves the value null and delegates to (key, value, true) so cached defaults true. */
    @Test
    public void keyOnlyConstructor_setsKeyNullValueAndCachedTrue() {
        Setting s = new Setting("my.key");

        assertEquals("my.key", s.getKey());
        assertNull(s.getValue(), "value must be null");
        assertTrue(s.isCached(), "the (key) constructor must default cached to true");
    }

    /** 3. new Setting(key, value) defaults cached to true. */
    @Test
    public void keyValueConstructor_defaultsCachedTrue() {
        Setting s = new Setting("my.key", "v");

        assertEquals("my.key", s.getKey());
        assertEquals("v", s.getValue());
        assertTrue(s.isCached(), "the (key, value) constructor must default cached to true");
    }

    /** 4. new Setting(key, value, cached=false) yields a non-cached setting. */
    @Test
    public void keyValueCachedConstructor_honoursCachedFalse() {
        Setting s = new Setting("my.key", "v", false);

        assertEquals("my.key", s.getKey());
        assertEquals("v", s.getValue());
        assertFalse(s.isCached(), "cached=false must be honoured");
    }

    /* ===================================================================== */
    /* updateType() INFERENCE                                                */
    /* ===================================================================== */

    /** 5a. An Integer value infers Type.Integer when no explicit type is given. */
    @Test
    public void updateType_infersIntegerFromIntegerValue() {
        Setting s = new Setting("k", Integer.valueOf(7), null, false, false, false);
        assertEquals(Type.Integer, s.getType());
    }

    /** 5b. A Long value infers Type.Long. */
    @Test
    public void updateType_infersLongFromLongValue() {
        Setting s = new Setting("k", Long.valueOf(7L), null, false, false, false);
        assertEquals(Type.Long, s.getType());
    }

    /** 5c. A Double value infers Type.Double. */
    @Test
    public void updateType_infersDoubleFromDoubleValue() {
        Setting s = new Setting("k", Double.valueOf(1.5d), null, false, false, false);
        assertEquals(Type.Double, s.getType());
    }

    /** 5d. A Float value infers Type.Float. */
    @Test
    public void updateType_infersFloatFromFloatValue() {
        Setting s = new Setting("k", Float.valueOf(1.5f), null, false, false, false);
        assertEquals(Type.Float, s.getType());
    }

    /** 5e. A String value infers Type.String. */
    @Test
    public void updateType_infersStringFromStringValue() {
        Setting s = new Setting("k", "s", null, false, false, false);
        assertEquals(Type.String, s.getType());
    }

    /** 5f. A Boolean value infers Type.Boolean. */
    @Test
    public void updateType_infersBooleanFromBooleanValue() {
        Setting s = new Setting("k", Boolean.TRUE, null, false, false, false);
        assertEquals(Type.Boolean, s.getType());
    }

    /** 5g. A value that matches none of the scalar branches (a Map) falls through to Type.json. */
    @Test
    public void updateType_infersJsonFromMapValue() {
        Setting s = new Setting("k", new HashMap<String, Object>(), null, false, false, false);
        assertEquals(Type.json, s.getType());
    }

    /** 5h. A value that matches none of the scalar branches (a List) also falls through to Type.json. */
    @Test
    public void updateType_infersJsonFromListValue() {
        Setting s = new Setting("k", new ArrayList<String>(), null, false, false, false);
        assertEquals(Type.json, s.getType());
    }

    /** 5i. A null value leaves the type null (the updateType() guard skips inference). */
    @Test
    public void updateType_leavesTypeNullForNullValue() {
        Setting s = new Setting("k", null, null, false, false, false);
        assertNull(s.getType(), "type must stay null when value is null");
    }

    /** 6. An explicitly supplied type is NOT overridden by inference, even when it disagrees with the value's class. */
    @Test
    public void updateType_doesNotOverrideExplicitType() {
        // value is an Integer, but the caller pinned Type.String -> updateType() must leave it alone.
        Setting s = new Setting("k", Integer.valueOf(7), Type.String, null, true, false, false);
        assertEquals(Type.String, s.getType());
    }

    /* ===================================================================== */
    /* DESIGNATED + COPY CONSTRUCTORS                                        */
    /* ===================================================================== */

    /** 7. The designated constructor sets every field; a null description stays null via StringUtils.defaultString(null, null). */
    @Test
    public void designatedConstructor_setsAllFieldsAndNullDescriptionStaysNull() {
        Setting s = new Setting("mail.host", "smtp", Type.Path, null, true, true, true);

        assertEquals("mail.host", s.getKey());
        assertEquals("smtp", s.getValue());
        assertEquals(Type.Path, s.getType());
        assertNull(s.getDescription(), "a null description must remain null");
        assertTrue(s.isCached(), "cached");
        assertTrue(s.isWeb(), "web");
        assertTrue(s.isEditable(), "editable");
    }

    /** 7b. The designated constructor preserves a non-null description verbatim. */
    @Test
    public void designatedConstructor_preservesNonNullDescription() {
        Setting s = new Setting("k", "v", Type.String, "the description", false, false, false);

        assertEquals("the description", s.getDescription());
        assertFalse(s.isCached());
        assertFalse(s.isWeb());
        assertFalse(s.isEditable());
    }

    /** 8. The copy constructor copies key/value/type/description/cached/web/editable from the template. */
    @Test
    public void copyConstructor_copiesAllFields() {
        Setting template = new Setting("orig.key", "orig.value", Type.Password, "desc", true, false, true);

        Setting copy = new Setting(template);

        assertEquals("orig.key", copy.getKey());
        assertEquals("orig.value", copy.getValue());
        assertEquals(Type.Password, copy.getType());
        assertEquals("desc", copy.getDescription());
        assertTrue(copy.isCached(), "cached must be copied");
        assertFalse(copy.isWeb(), "web must be copied");
        assertTrue(copy.isEditable(), "editable must be copied");
    }

    /* ===================================================================== */
    /* METHOD CHAINING                                                       */
    /* ===================================================================== */

    /** 9. Each fluent setter returns the same instance and updates the corresponding field. */
    @Test
    public void methodChaining_returnsSameInstanceAndSetsFields() {
        Setting base = new Setting();

        // assertSame proves the very first chained call returns the same object...
        assertSame(base, base.key("k"), "key(..) must return the same Setting instance");

        // ...and the full chain (each link returning 'this') leaves every field set.
        Setting chained = base
                .value("v")
                .description("d")
                .cached(true)
                .web(true)
                .editable(true)
                .type(Type.Password);

        assertSame(base, chained, "the chain must operate on the same instance");
        assertEquals("k", chained.getKey());
        assertEquals("v", chained.getValue());
        assertEquals("d", chained.getDescription());
        assertTrue(chained.isCached());
        assertTrue(chained.isWeb());
        assertTrue(chained.isEditable());
        assertEquals(Type.Password, chained.getType());
    }

    /* ===================================================================== */
    /* Type ENUM                                                             */
    /* ===================================================================== */

    /** 10. The Type enum declares exactly the 10 expected constants and resolves "json" by name. */
    @Test
    public void typeEnum_hasExactlyTenExpectedValues() {
        assertEquals(10, Type.values().length, "Type must declare exactly 10 constants");
        assertSame(Type.json, Type.valueOf("json"), "Type.valueOf(\"json\") must resolve to Type.json");

        // Pin the exact membership so a renamed/added/removed constant fails the test.
        assertEquals(Type.String, Type.valueOf("String"));
        assertEquals(Type.Password, Type.valueOf("Password"));
        assertEquals(Type.Integer, Type.valueOf("Integer"));
        assertEquals(Type.Long, Type.valueOf("Long"));
        assertEquals(Type.Float, Type.valueOf("Float"));
        assertEquals(Type.Double, Type.valueOf("Double"));
        assertEquals(Type.Date, Type.valueOf("Date"));
        assertEquals(Type.Boolean, Type.valueOf("Boolean"));
        assertEquals(Type.Path, Type.valueOf("Path"));
        assertEquals(Type.json, Type.valueOf("json"));
    }
}
