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
package org.niord.core.defaults;

import org.junit.jupiter.api.Test;
import org.niord.test.CountryBuildGroup;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A country's settings file and texts pass the settings and text checks (docs/code-data-config-plan.md §4.7, §5.5):
 * the country side of {@link BaseProductSettingsCheckTest} and {@link BaseProductTextsCheckTest}, run in every country
 * build (the country build group) over the country's class path: its {@code defaults/country/settings.json} against
 * the pinned base product's catalog and its own, including the {@code required} rule start-up only warns about, and
 * its {@code defaults/country/texts/} and the packs' backend half against the base product's English. It holds a
 * country to the two remaining text layers and to the root-bundle rule: a root-level {@code <bundle>_<lang>.properties}
 * is no longer read, so one fails the build.
 *
 * <p>The settings report is printed: the country build group does not redirect its output, so it reaches the Maven
 * log. While the class path has no {@code defaults/country/} and no {@code defaults/packs/} (a country before the
 * plan's C6) there is nothing of the country's to check, and the test is skipped. DK and GL have both; niord-base's
 * own unit run checks its test tree's stand-in da pack.
 */
@CountryBuildGroup
class CountryDefaultsChecksTest {

    @Test
    void theCountrysSettingsAndTextsPassTheChecks() {
        ClassLoader cl = CountryDefaultsChecksTest.class.getClassLoader();
        assumeTrue(hasCountryDefaults(cl), "no defaults/country/ or defaults/packs/ on the class path: the country "
                + "adds them in the code/data/config plan's C6");

        DefaultSettingsChecks.Result settings = DefaultSettingsChecks.check(cl);
        System.out.print(settings.report());
        List<DefaultTextsChecks.Finding> texts = DefaultTextsChecks.checkTexts(cl);

        assertTrue(settings.errors().isEmpty(), () -> "The settings files break the settings checks:\n"
                + settings.report());
        assertTrue(texts.isEmpty(), () -> "The texts break the text checks:\n" + texts.stream()
                .map(DefaultTextsChecks.Finding::toString)
                .collect(Collectors.joining("\n")));
    }

    /** Whether the class path holds a country's settings file or texts, or a pack's backend half */
    static boolean hasCountryDefaults(ClassLoader cl) {
        return cl.getResource(DefaultSettingsChecks.COUNTRY_SETTINGS) != null
                || !DefaultTextsChecks.languages(cl, DefaultTextsChecks.COUNTRY_FOLDER).isEmpty()
                || !DefaultTextsChecks.languages(cl, DefaultTextsChecks.PACKS_FOLDER).isEmpty();
    }
}
