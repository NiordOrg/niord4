/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.secom.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SecomConfigService#normalizeLanguages}: the per-series S-124
 * language restriction is stored canonically - model-language order, lower-case, no blanks
 * or duplicates - and a code outside the model languages is rejected rather than dropped.
 */
class SecomConfigServiceLanguagesTest {

    private static final String[] MODEL = {"da", "en", "kl"};

    @Test
    void nullOrEmptyMeansNoRestriction() {
        assertEquals(List.of(), SecomConfigService.normalizeLanguages(null, MODEL));
        assertEquals(List.of(), SecomConfigService.normalizeLanguages(List.of(), MODEL));
        assertEquals(List.of(), SecomConfigService.normalizeLanguages(Arrays.asList("", "  ", null), MODEL),
                "blank entries do not count as a restriction");
    }

    @Test
    void codesAreOrderedAsTheModelLanguagesRegardlessOfRequestOrder() {
        assertEquals(List.of("da", "en"), SecomConfigService.normalizeLanguages(List.of("en", "da"), MODEL));
        assertEquals(List.of("da", "kl"), SecomConfigService.normalizeLanguages(List.of("kl", "da"), MODEL));
    }

    @Test
    void codesAreTrimmedLowerCasedAndDeduplicated() {
        assertEquals(List.of("en"), SecomConfigService.normalizeLanguages(List.of(" EN ", "en", "En"), MODEL));
    }

    @Test
    void aFullSelectionIsKeptAsAnExplicitRestriction() {
        // Selecting every model language is stored as is: whether that should collapse to "no
        // restriction" is a presentation choice, and keeping it explicit means a model language
        // added later does not silently widen the series
        assertEquals(List.of("da", "en", "kl"),
                SecomConfigService.normalizeLanguages(List.of("kl", "en", "da"), MODEL));
    }

    @Test
    void aCodeOutsideTheModelLanguagesIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecomConfigService.normalizeLanguages(List.of("en", "fr"), MODEL));
        assertTrue(e.getMessage().contains("'fr'"), "names the offending code: " + e.getMessage());
        assertTrue(e.getMessage().contains("[da, en, kl]"), "lists the model languages: " + e.getMessage());
    }

    @Test
    void modelLanguagesAreMatchedCaseInsensitively() {
        assertEquals(List.of("da"), SecomConfigService.normalizeLanguages(List.of("DA"), new String[]{"Da", "EN"}));
    }
}
