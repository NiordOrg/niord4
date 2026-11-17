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
package org.niord.core.conf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jackson-core releases on the class path carry the fix for the number check that backtracks
 * quadratically on a long malformed number (GHSA-p6pp-m3f8-5c89, CVE-2026-89407). Request bodies are
 * parsed before method security runs, so an anonymous caller reaches that check.
 *
 * <p>{@code be/pom.xml} overrides the two Jackson BOMs Spring Boot manages for it. These tests fail when
 * a Boot upgrade, a dropped override or a dependency change puts an affected release back on the class
 * path. They check the release, not how long a crafted number takes to parse, so they assert an outcome
 * rather than a timing.
 *
 * <p>Affected and fixed, per the advisory: Jackson 3 from 3.0.0 to 3.1.6 (fixed in 3.1.7) and 3.2.0 to
 * 3.2.1 (fixed in 3.2.2); Jackson 2 from 2.17.0 to 2.18.10 (2.18.11), 2.19.0 to 2.21.6 (2.21.7) and
 * 2.22.0 to 2.22.2 (2.22.3). A later minor line than those is taken to carry the fix.
 */
class JacksonPatchLevelTest {

    /** Whether a Jackson 3 jackson-core release carries the fix */
    static boolean jackson3Fixed(int major, int minor, int patch) {
        if (major != 3) {
            return major > 3;
        }
        return switch (minor) {
            case 0 -> false;
            case 1 -> patch >= 7;
            case 2 -> patch >= 2;
            default -> true;
        };
    }

    /** Whether a Jackson 2 jackson-core release carries the fix */
    static boolean jackson2Fixed(int major, int minor, int patch) {
        if (major != 2) {
            return major > 2;
        }
        if (minor < 17) {
            return true;    // before the check was introduced
        }
        return switch (minor) {
            case 17, 19, 20 -> false;
            case 18 -> patch >= 11;
            case 21 -> patch >= 7;
            case 22 -> patch >= 3;
            default -> true;
        };
    }

    @Test
    void theJackson3CoreOnTheClassPathCarriesTheFix() {
        tools.jackson.core.Version version = tools.jackson.core.json.PackageVersion.VERSION;
        assertTrue(jackson3Fixed(version.getMajorVersion(), version.getMinorVersion(), version.getPatchLevel()),
                "tools.jackson.core:jackson-core " + version + " has the quadratic number check; see be/pom.xml");
    }

    @Test
    void theJackson2CoreOnTheClassPathCarriesTheFix() {
        com.fasterxml.jackson.core.Version version = com.fasterxml.jackson.core.json.PackageVersion.VERSION;
        assertTrue(jackson2Fixed(version.getMajorVersion(), version.getMinorVersion(), version.getPatchLevel()),
                "com.fasterxml.jackson.core:jackson-core " + version + " has the quadratic number check; see be/pom.xml");
    }

    @Test
    void theReleasesSpringBootManagesAreAffected() {
        // What spring-boot-starter-parent 4.0.7 manages without the override in be/pom.xml
        assertFalse(jackson3Fixed(3, 1, 4));
        assertFalse(jackson2Fixed(2, 21, 4));
    }

    @Test
    void theFixedReleasesOfEachAffectedLineAreRecognised() {
        assertFalse(jackson3Fixed(3, 0, 4));
        assertFalse(jackson3Fixed(3, 1, 6));
        assertTrue(jackson3Fixed(3, 1, 7));
        assertFalse(jackson3Fixed(3, 2, 1));
        assertTrue(jackson3Fixed(3, 2, 2));
        assertTrue(jackson3Fixed(3, 3, 0));

        assertFalse(jackson2Fixed(2, 18, 10));
        assertTrue(jackson2Fixed(2, 18, 11));
        assertFalse(jackson2Fixed(2, 20, 9));
        assertFalse(jackson2Fixed(2, 21, 6));
        assertTrue(jackson2Fixed(2, 21, 7));
        assertFalse(jackson2Fixed(2, 22, 2));
        assertTrue(jackson2Fixed(2, 22, 3));
        assertTrue(jackson2Fixed(2, 23, 0));
    }
}
