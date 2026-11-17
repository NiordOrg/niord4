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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base product's settings file passes the settings checks ({@link DefaultSettingsChecks},
 * docs/code-data-config-plan.md §5.5): {@code defaults/base/settings.json} against
 * {@code defaults/base/settings-catalog.json}, both from this module's main resources. There is no
 * {@code defaults/country/} in this module, and since C3b no root-level {@code niord.json} in its main resources:
 * {@code settings.json} is the file start-up reads. (The test tree's root-level {@code niord.json} stands for a
 * country's transitional file and is not under {@code defaults/}; the checks do not read it.) A unit test, so Backend CI's unit job runs it, and it
 * prints the warnings and the {@code useBaseDefault} list.
 */
class BaseProductSettingsCheckTest {

    @Test
    void theBaseProductsSettingsPassTheSettingsChecks() {
        DefaultSettingsChecks.Result result = DefaultSettingsChecks.check(getClass().getClassLoader());
        System.out.print(result.report());

        assertTrue(result.errors().isEmpty(), () -> "The base product's settings break the settings checks:\n"
                + result.report());
        // Guards against a green run that read nothing
        assertEquals(26, result.valuesRead().get(DefaultSettingsChecks.BASE_SETTINGS),
                "the values of " + DefaultSettingsChecks.BASE_SETTINGS);
    }
}
