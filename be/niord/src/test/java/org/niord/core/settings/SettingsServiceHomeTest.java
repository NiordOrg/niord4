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
package org.niord.core.settings;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ListableBeanFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Tier-1 tests for where {@link SettingsService} finds the server's home folder without the database: a
 * {@code niord.home} system property or environment variable, else {@code NIORD_HOME}, the deployments' variable and
 * the token the countries' settings files give the key, so the generic {@code niord-app}, which has no settings file
 * of its own, follows it as DK and GL do. A blank {@code NIORD_HOME} is no home, and no other key gains an upper-case
 * alias.
 */
class SettingsServiceHomeTest {

    private static final String HOME = "niord.home";

    @TempDir
    Path tempDir;

    private String savedHomeVariable;

    private String savedMode;

    private String savedHome;

    @BeforeEach
    void saveProperties() {
        savedHomeVariable = System.getProperty("NIORD_HOME");
        savedMode = System.getProperty("MODE");
        savedHome = System.getProperty(HOME);
        assumeTrue(System.getProperty(HOME) == null && System.getenv(HOME) == null,
                "a niord.home property or variable of the run would win over NIORD_HOME");
    }

    @AfterEach
    void restoreProperties() {
        restore("NIORD_HOME", savedHomeVariable);
        restore("MODE", savedMode);
        restore(HOME, savedHome);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private String tempHome() {
        return tempDir.toAbsolutePath().toString().replace('\\', '/');
    }

    private static SettingsService service() {
        return new SettingsService(mock(SettingsCache.class), mock(ListableBeanFactory.class));
    }

    @Test
    void niordHomeFollowsTheNiordHomeVariable() {
        System.setProperty("NIORD_HOME", tempHome());

        assertEquals(tempHome(), service().override(HOME));
    }

    @Test
    void peekAnswersTheNiordHomeVariableWithoutTheDatabase() {
        System.setProperty("NIORD_HOME", tempHome());

        // The service has no entity manager: a read of the row would throw
        assertEquals(tempHome(), service().peek(HOME));
    }

    @Test
    void aNiordHomePropertyWinsOverTheNiordHomeVariable() {
        System.setProperty("NIORD_HOME", tempHome());
        String own = tempHome() + "/own";
        System.setProperty(HOME, own);

        assertEquals(own, service().override(HOME));
    }

    @Test
    void aBlankNiordHomeVariableIsNoHome() {
        System.setProperty("NIORD_HOME", "  ");

        // The blank property is passed over; an exported NIORD_HOME of the machine then answers
        assertEquals(StringUtils.trimToNull(System.getenv("NIORD_HOME")), service().override(HOME));
    }

    @Test
    void noOtherKeyGainsAnUpperCaseAlias() {
        System.setProperty("NIORD_HOME", tempHome());
        System.setProperty("MODE", "TEST");
        SettingsService service = service();

        assertNull(service.override("mode"));
        assertEquals(tempHome(), service.override("NIORD_HOME"));
    }
}
