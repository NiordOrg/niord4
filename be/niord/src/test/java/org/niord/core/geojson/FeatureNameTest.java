/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.core.geojson;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FeatureName}, which parses GeoJSON feature-name properties of the form
 * {@code name:lang} (feature name) and {@code name:index:lang} (feature coordinate name).
 *
 * These are framework-neutral tests exercising pure parsing logic; no Quarkus context is required.
 */
public class FeatureNameTest {

    // ------------------------------------------------------------------
    // Feature-name parsing ("name:lang")
    // ------------------------------------------------------------------

    @Test
    public void parsesFeatureNameKey() {
        FeatureName fn = new FeatureName("name:da", "Danmark");

        assertTrue(fn.isValid());
        assertTrue(fn.isFeatureName());
        assertFalse(fn.isFeatureCoordName());
        assertEquals("da", fn.getLanguage());
        assertEquals(FeatureName.Type.FeatureName, fn.getType());
        assertEquals("name:da", fn.getKey());
        assertEquals("Danmark", fn.getValueString());
        assertEquals("Danmark", fn.getValue());
    }

    @Test
    public void featureNameValueIsTheRawObject() {
        // getValue() returns the raw stored object, not a copy/conversion.
        Object raw = Integer.valueOf(42);
        FeatureName fn = new FeatureName("name:en", raw);

        assertSame(raw, fn.getValue());
        // getValueString() goes through Object.toString().
        assertEquals("42", fn.getValueString());
    }

    // ------------------------------------------------------------------
    // Feature-coordinate-name parsing ("name:index:lang")
    // ------------------------------------------------------------------

    @Test
    public void parsesFeatureCoordinateNameKey() {
        FeatureName fn = new FeatureName("name:2:en", "X");

        assertTrue(fn.isValid());
        assertTrue(fn.isFeatureCoordName());
        assertFalse(fn.isFeatureName());
        assertEquals(2, fn.getCoordIndex());
        assertEquals("en", fn.getLanguage());
        assertEquals(FeatureName.Type.FeatureCoordName, fn.getType());
        assertEquals("name:2:en", fn.getKey());
        assertEquals("X", fn.getValueString());
    }

    @Test
    public void parsesFeatureCoordinateNameWithZeroIndex() {
        FeatureName fn = new FeatureName("name:0:da", "Punkt");

        assertTrue(fn.isFeatureCoordName());
        assertEquals(0, fn.getCoordIndex());
        assertEquals("da", fn.getLanguage());
    }

    @Test
    public void parsesFeatureCoordinateNameWithMultiDigitIndex() {
        FeatureName fn = new FeatureName("name:123:de", "Stelle");

        assertTrue(fn.isFeatureCoordName());
        assertEquals(123, fn.getCoordIndex());
        assertEquals("de", fn.getLanguage());
    }

    // ------------------------------------------------------------------
    // Invalid keys
    // ------------------------------------------------------------------

    @Test
    public void invalidKeyIsNotRecognised() {
        FeatureName fn = new FeatureName("foo", "x");

        assertFalse(fn.isValid());
        assertFalse(fn.isFeatureName());
        assertFalse(fn.isFeatureCoordName());
        assertNull(fn.getType());
        // language is never assigned for an invalid key.
        assertNull(fn.getLanguage());
        // The original value is still retained even for an invalid key.
        assertEquals("x", fn.getValueString());
    }

    @Test
    public void keyWithoutLanguageIsInvalid() {
        // "name:" has an empty language portion, which [a-zA-Z_]+ requires at least one char.
        FeatureName fn = new FeatureName("name:", "x");

        assertFalse(fn.isValid());
        assertNull(fn.getType());
        assertNull(fn.getLanguage());
    }

    @Test
    public void coordKeyWithoutLanguageIsInvalid() {
        FeatureName fn = new FeatureName("name:0:", "x");

        assertFalse(fn.isValid());
        assertNull(fn.getType());
    }

    @Test
    public void coordKeyWithNonNumericIndexIsInvalid() {
        // "name:x:en" - index portion is not numeric, so neither pattern matches.
        FeatureName fn = new FeatureName("name:x:en", "y");

        assertFalse(fn.isValid());
        assertNull(fn.getType());
    }

    @Test
    public void coordIndexDefaultsToZeroForPlainFeatureName() {
        // coordIndex is only assigned for FeatureCoordName keys; for a plain FeatureName
        // it remains at the default int value 0.
        FeatureName fn = new FeatureName("name:en", "Hello");

        assertTrue(fn.isFeatureName());
        assertEquals(0, fn.getCoordIndex());
    }

    // ------------------------------------------------------------------
    // Map.Entry constructor parity with String constructor
    // ------------------------------------------------------------------

    @Test
    public void mapEntryConstructorMatchesStringConstructor() {
        FeatureName fromEntry =
                new FeatureName(new AbstractMap.SimpleEntry<String, Object>("name:fr", "Bonjour"));
        FeatureName fromString = new FeatureName("name:fr", "Bonjour");

        assertEquals(fromString.isValid(), fromEntry.isValid());
        assertEquals(fromString.getType(), fromEntry.getType());
        assertEquals(fromString.getLanguage(), fromEntry.getLanguage());
        assertEquals(fromString.getKey(), fromEntry.getKey());
        assertEquals(fromString.getValueString(), fromEntry.getValueString());

        // Concrete expected values for the entry-based instance.
        assertTrue(fromEntry.isFeatureName());
        assertEquals("fr", fromEntry.getLanguage());
        assertEquals("name:fr", fromEntry.getKey());
        assertEquals("Bonjour", fromEntry.getValueString());
    }

    @Test
    public void mapEntryConstructorParsesCoordinateName() {
        FeatureName fn =
                new FeatureName(new AbstractMap.SimpleEntry<String, Object>("name:5:es", "Hola"));

        assertTrue(fn.isFeatureCoordName());
        assertEquals(5, fn.getCoordIndex());
        assertEquals("es", fn.getLanguage());
        assertEquals(FeatureName.Type.FeatureCoordName, fn.getType());
    }

    // ------------------------------------------------------------------
    // Null / value-string handling
    // ------------------------------------------------------------------

    @Test
    public void valueStringIsNullWhenValueIsNull() {
        FeatureName fn = new FeatureName("name:da", null);

        assertNull(fn.getValueString());
        assertNull(fn.getValue());
        // Key parsing is independent of the value, so this remains a valid feature name.
        assertTrue(fn.isFeatureName());
        assertEquals("da", fn.getLanguage());
    }

    // ------------------------------------------------------------------
    // Static getFeatureName(properties, language)
    // ------------------------------------------------------------------

    @Test
    public void getFeatureNameReturnsMatchingLanguage() {
        Map<String, Object> props = new HashMap<>();
        props.put("name:da", "Danmark");

        assertEquals("Danmark", FeatureName.getFeatureName(props, "da"));
    }

    @Test
    public void getFeatureNameReturnsNullForMissingLanguage() {
        Map<String, Object> props = new HashMap<>();
        props.put("name:da", "Danmark");

        assertNull(FeatureName.getFeatureName(props, "en"));
    }

    @Test
    public void getFeatureNameReturnsNullForNullProperties() {
        assertNull(FeatureName.getFeatureName(null, "da"));
    }

    @Test
    public void getFeatureNameConvertsNonStringValueToString() {
        // Values are stored as Objects; getFeatureName uses toString().
        Map<String, Object> props = new HashMap<>();
        props.put("name:en", Integer.valueOf(7));

        assertEquals("7", FeatureName.getFeatureName(props, "en"));
    }

    @Test
    public void getFeatureNameReturnsNullWhenStoredValueIsNull() {
        Map<String, Object> props = new HashMap<>();
        props.put("name:da", null);

        assertNull(FeatureName.getFeatureName(props, "da"));
    }

    // ------------------------------------------------------------------
    // Static getFeatureCoordinateName(properties, language, coordIndex)
    // ------------------------------------------------------------------

    @Test
    public void getFeatureCoordinateNameReturnsMatchingValue() {
        Map<String, Object> props = new HashMap<>();
        props.put("name:0:en", "aa");

        assertEquals("aa", FeatureName.getFeatureCoordinateName(props, "en", 0));
    }

    @Test
    public void getFeatureCoordinateNameReturnsNullForWrongIndex() {
        Map<String, Object> props = new HashMap<>();
        props.put("name:0:en", "aa");

        assertNull(FeatureName.getFeatureCoordinateName(props, "en", 1));
    }

    @Test
    public void getFeatureCoordinateNameReturnsNullForWrongLanguage() {
        Map<String, Object> props = new HashMap<>();
        props.put("name:0:en", "aa");

        assertNull(FeatureName.getFeatureCoordinateName(props, "da", 0));
    }

    @Test
    public void getFeatureCoordinateNameReturnsNullForNullProperties() {
        assertNull(FeatureName.getFeatureCoordinateName(null, "en", 0));
    }

    @Test
    public void getFeatureCoordinateNameConvertsNonStringValueToString() {
        Map<String, Object> props = new HashMap<>();
        props.put("name:3:en", Boolean.TRUE);

        assertEquals("true", FeatureName.getFeatureCoordinateName(props, "en", 3));
    }

    // ------------------------------------------------------------------
    // Behaviour-pinning tests for surprising (documented-vs-actual) behaviour
    // ------------------------------------------------------------------

    @Test
    public void multiCharacterLanguageIsAccepted_documentsBehavior() {
        // The class Javadoc states "lang is the 2 character language code", but the regex
        // [a-zA-Z_]+ accepts any non-empty run of letters/underscores. So a long language token
        // such as "english" is accepted as a valid feature name. Pinning current behaviour.
        FeatureName fn = new FeatureName("name:english", "Denmark");

        assertTrue(fn.isValid());
        assertTrue(fn.isFeatureName());
        assertEquals("english", fn.getLanguage());
    }

    @Test
    public void underscoreInLanguageIsAccepted_documentsBehavior() {
        // [a-zA-Z_]+ permits underscores, so a locale-style token like "da_DK" parses as a
        // valid feature-name language even though it is not a 2-character code.
        FeatureName fn = new FeatureName("name:da_DK", "Danmark");

        assertTrue(fn.isValid());
        assertTrue(fn.isFeatureName());
        assertEquals("da_DK", fn.getLanguage());
    }

    @Test
    public void singleCharacterLanguageIsAccepted_documentsBehavior() {
        // Despite the "2 character" contract, a single-character language token is accepted.
        FeatureName fn = new FeatureName("name:e", "X");

        assertTrue(fn.isFeatureName());
        assertEquals("e", fn.getLanguage());
    }

    @Test
    public void leadingZeroCoordinateIndexIsParsedNumerically_documentsBehavior() {
        // "\\d+" matches "007" and Integer.valueOf strips the leading zeros to 7.
        FeatureName fn = new FeatureName("name:007:en", "X");

        assertTrue(fn.isFeatureCoordName());
        assertEquals(7, fn.getCoordIndex());
    }
}
