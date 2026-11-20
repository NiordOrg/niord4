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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Tier-1 tests for where {@link SettingsService} finds the server's home folder without the database: a
 * {@code niord.home} system property or environment variable, else {@code NIORD_HOME}, the deployments' variable and
 * the token the countries' settings files give the key, so the generic {@code niord-app}, which has no settings file
 * of its own, follows it as DK and GL do. A blank {@code NIORD_HOME} is no home, and no other key gains an upper-case
 * alias. And the refusal of a home that names no folder ({@link SettingsService#homeRefusal}): a token nothing
 * resolves, with or without a suffix, or a blank value, from the settings file, the database's row or an override.
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

    // ------------------------------------------------------------------ the refusal of a home that names no folder

    /** The tokens of a machine that sets user.home and nothing else */
    private static final Map<String, String> MACHINE = Map.of("user.home", "/home/mariner");

    private static String refusal(SettingsService.HomeValue home) {
        return SettingsService.homeRefusal(home, MACHINE::get);
    }

    private static SettingsService.HomeValue fromTheFile(String value) {
        return SettingsService.homeValue(null, null, value);
    }

    @Test
    void anUnresolvedTokenIsRefusedWithTheCauseAndTheCure() {
        assertEquals("niord.home cannot be resolved: its value is \"${NIORD_HOME}\" (from the settings file) and no "
                        + "NIORD_HOME is set; set the environment variable NIORD_HOME or start with "
                        + "-Dniord.home=<folder>",
                refusal(fromTheFile("${NIORD_HOME}")));
    }

    @Test
    void aBlankValueIsRefused() {
        assertEquals("niord.home cannot be resolved: its value is \"\" (from the niord.home system property or "
                        + "environment variable), which names no folder; set the environment variable NIORD_HOME or "
                        + "start with -Dniord.home=<folder>",
                refusal(SettingsService.homeValue("", null, "${user.home}/.niord")));
        assertNotNull(refusal(fromTheFile("   ")));
        assertNotNull(refusal(fromTheFile(null)));
    }

    @Test
    void aTokenWithASuffixIsRefusedThoughTheValueIsNotBlank() {
        String refusal = refusal(fromTheFile("${NIORD_HOME}/niord"));

        assertNotNull(refusal);
        assertTrue(refusal.contains("its value is \"${NIORD_HOME}/niord\""), refusal);
        assertTrue(refusal.contains("and no NIORD_HOME is set"), refusal);
    }

    @Test
    void aTokenThatResolvesToBlankTextIsUnresolved() {
        String refusal = SettingsService.homeRefusal(fromTheFile("${NIORD_HOME}/niord"),
                token -> "NIORD_HOME".equals(token) ? "  " : null);

        assertNotNull(refusal);
        assertTrue(refusal.contains("and no NIORD_HOME is set"), refusal);
    }

    @Test
    void aResolvedValueIsAccepted() {
        assertNull(refusal(fromTheFile("${user.home}/.niord")));
        assertNull(refusal(fromTheFile("/srv/niord")));
        assertNull(refusal(SettingsService.homeValue("/niord-home", null, "${NIORD_HOME}")));
    }

    @Test
    void theStoredRowsTokenWinsOverTheFileAndIsRefused() {
        Setting row = new Setting(HOME, "${NIORD_HOME}");

        String refusal = refusal(SettingsService.homeValue(null, row, "${user.home}/.niord"));

        assertEquals("niord.home cannot be resolved: its value is \"${NIORD_HOME}\" (from the database's niord.home "
                        + "setting) and no NIORD_HOME is set; set the environment variable NIORD_HOME or start with "
                        + "-Dniord.home=<folder>",
                refusal);
    }

    @Test
    void aStoredRowThatNamesAFolderWinsOverTheFilesToken() {
        Setting row = new Setting(HOME, "/srv/niord");

        assertNull(refusal(SettingsService.homeValue(null, row, "${NIORD_HOME}")));
    }

    @Test
    void anOverrideWinsOverTheStoredRowsToken() {
        SettingsService.HomeValue home = SettingsService.homeValue("/niord-home", new Setting(HOME, "${NIORD_HOME}"),
                "${NIORD_HOME}");

        assertNull(SettingsService.homeRefusal(home, token -> null));
    }

    @Test
    void everyUnresolvedTokenIsNamed() {
        String refusal = refusal(fromTheFile("${NIORD_DRIVE}/${NIORD_HOME}"));

        assertNotNull(refusal);
        assertTrue(refusal.contains("and none of NIORD_DRIVE, NIORD_HOME is set"), refusal);
    }
}
