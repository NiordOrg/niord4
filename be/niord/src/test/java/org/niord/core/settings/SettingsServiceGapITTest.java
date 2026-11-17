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

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting.Type;
import org.niord.test.ITTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap-filling integration tests for {@link SettingsService}, complementing
 * {@link SettingsServiceITTest}. These focus on behaviour that the sibling does not cover:
 *
 * <ul>
 *   <li>the System-property override path in {@link SettingsService#get(String)} and
 *       {@link SettingsService#peek(String)} (system properties take precedence over the
 *       cache and the database, and are NOT persisted);</li>
 *   <li>{@code ${token}} expansion sourced from a System property when no database setting
 *       backs the token;</li>
 *   <li>{@link SettingsService#set(String, Object)} rejecting an unknown key without mutating
 *       the database;</li>
 *   <li>{@link SettingsService#getAllForWeb()} / {@link SettingsService#getAllEditable()}
 *       filtering against the genuinely seeded settings.</li>
 * </ul>
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all database writes are rolled back,
 * keeping the shared seed database pristine and the tests independent. System properties are
 * always set and cleared in a {@code try/finally} so they never leak between tests or onto the
 * shared JVM.
 *
 * <p><b>Cache caveat:</b> {@link SettingsService} reads through an Infinispan
 * {@code SettingsCache} that {@link TestTransaction} does NOT roll back. To stay isolated, every
 * test here either:
 * <ul>
 *   <li>exercises the System-property override, which returns <em>before</em> the cache/DB is
 *       ever consulted (so nothing is cached), or</li>
 *   <li>uses {@link SettingsService#peek(String)}, which never caches and never creates a row,
 *       or</li>
 *   <li>uses a UNIQUE {@code it-test-gap-...} key that no other test references.</li>
 * </ul>
 * This coupling is documented in docs/framework-coupled-tests.md.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SettingsServiceGapITTest extends ITTest {

    @Autowired
    SettingsService settingsService;

    @PersistenceContext
    EntityManager em;

    /** Persists a setting row directly, bypassing the service, so peek()/set() have a row to act on. */
    private Setting seed(Setting setting) {
        em.persist(setting);
        em.flush();
        return setting;
    }

    /** Counts the rows currently in the Setting table. */
    private long settingCount() {
        return em.createQuery("select count(s) from Setting s", Long.class).getSingleResult();
    }

    // ------------------------------------------------------------------------------------------- //
    // get(key) — System property precedence (no DB, no cache, no persistence)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getReturnsSystemPropertyValueAndNeverPersistsIt() {
        String key = "it-test-gap-sysprop-get-" + System.nanoTime();
        System.setProperty(key, "from-system-property");
        try {
            long before = settingCount();

            Object value = settingsService.get(key);
            assertEquals("from-system-property", value,
                    "get() must return the System property value verbatim when one is set");

            em.flush();
            em.clear();

            // The System-property branch returns before the cache and the database, so no row
            // may be created for this key, and the overall row count must be unchanged.
            assertNull(em.find(Setting.class, key),
                    "get() must NOT create a row when a System property satisfies the lookup");
            assertEquals(before, settingCount(),
                    "the System-property branch of get() must not persist anything");
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    @Transactional
    void getSystemPropertyTakesPrecedenceOverPersistedDbValue() {
        String key = "it-test-gap-sysprop-over-db-get-" + System.nanoTime();
        seed(new Setting(key, "db-value"));
        em.clear();
        System.setProperty(key, "sysprop-value");
        try {
            assertEquals("sysprop-value", settingsService.get(key),
                    "a System property must shadow the persisted database value in get()");
        } finally {
            System.clearProperty(key);
        }

        // And once the System property is gone, the persisted value is what get() resolves to.
        em.clear();
        assertEquals("db-value", settingsService.get(key),
                "after clearing the System property, get() must fall back to the persisted value");
    }

    // ------------------------------------------------------------------------------------------- //
    // peek(key) — System property precedence over the database
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void peekReturnsSystemPropertyOverPersistedValue() {
        String key = "it-test-gap-sysprop-peek-" + System.nanoTime();
        seed(new Setting(key, "persisted-value"));
        em.clear();
        System.setProperty(key, "sysprop-wins");
        try {
            assertEquals("sysprop-wins", settingsService.peek(key),
                    "peek() must return the System property value in preference to the DB value");
        } finally {
            System.clearProperty(key);
        }

        // With the System property cleared, peek() reflects the actual database row.
        em.clear();
        assertEquals("persisted-value", settingsService.peek(key),
                "after clearing the System property, peek() must return the persisted value");
    }

    // ------------------------------------------------------------------------------------------- //
    // ${token} expansion sourced from a System property
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void peekExpandsTokenFromSystemPropertyWhenNoDbSettingExists() {
        long n = System.nanoTime();
        String tokenKey = "it-test-gap-token-" + n;     // no Setting row backs this token
        String refKey = "it-test-gap-ref-" + n;

        // Precondition: the token is not backed by any Setting row.
        assertNull(em.find(Setting.class, tokenKey), "precondition: token must have no DB setting");

        seed(new Setting(refKey, "pre-${" + tokenKey + "}-post"));
        em.clear();

        System.setProperty(tokenKey, "SYS");
        try {
            // peek() expands ${token}; SettingValueExpander resolves it from the System property
            // because no DB setting (and presumably no env var) provides the token.
            assertEquals("pre-SYS-post", settingsService.peek(refKey),
                    "an unresolved DB token must be expanded from the matching System property");
        } finally {
            System.clearProperty(tokenKey);
        }
    }

    @Test
    @Transactional
    void peekExpandsUnresolvableTokenToEmptyString() {
        long n = System.nanoTime();
        String refKey = "it-test-gap-unresolvable-" + n;
        String missingToken = "it-test-gap-no-such-token-" + n;

        // The referenced token is backed by neither a Setting row, a System property nor an env var.
        assertNull(em.find(Setting.class, missingToken), "precondition: token must not exist");
        assertNull(System.getProperty(missingToken), "precondition: no system property for token");

        seed(new Setting(refKey, "x-${" + missingToken + "}-y"));
        em.clear();

        // expandSettingValue() replaces an unresolvable ${token}
        // with an empty string. It calls SettingValueExpander.replaceToken(token, "") to blank out the
        // token; that method now removes the token text and records it (instead of short-circuiting on
        // the blank replacement and spinning until the livelock guard trips), so peek() expands the
        // value cleanly to "x--y".
        assertEquals("x--y", settingsService.peek(refKey),
                "an unresolvable ${token} must expand to an empty string");
    }

    // ------------------------------------------------------------------------------------------- //
    // set(key, value) — rejects an unknown key without mutating the database
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void setRejectsNonExistingKeyAndLeavesTheDatabaseUnchanged() {
        String key = "it-test-gap-set-missing-" + System.nanoTime();
        assertNull(em.find(Setting.class, key), "precondition: setting must not already exist");

        long before = settingCount();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> settingsService.set(key, "value"),
                "set() must reject an unknown key");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains(key),
                "the exception message must reference the missing key, was: " + ex.getMessage());

        em.flush();
        em.clear();

        assertNull(em.find(Setting.class, key), "set() must NOT create the missing setting");
        assertEquals(before, settingCount(),
                "a rejected set() must leave the Setting row count unchanged");
    }

    // ------------------------------------------------------------------------------------------- //
    // getAllForWeb() / getAllEditable() — named-query filtering against seeded settings
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getAllForWebReturnsOnlyWebFlaggedSettings() {
        List<Setting> webSettings = settingsService.getAllForWeb();
        assertFalse(webSettings.isEmpty(), "the seed data defines several web-flagged settings");

        // Every returned setting must actually be web-flagged.
        assertTrue(webSettings.stream().allMatch(Setting::isWeb),
                "getAllForWeb() must only return settings with web=true");

        List<String> keys = webSettings.stream().map(Setting::getKey).toList();
        // 'documentationUrl' is seeded with web=true; 'authServerUrl' is seeded with web=false.
        assertTrue(keys.contains("documentationUrl"),
                "a seeded web=true setting must be present in getAllForWeb()");
        assertFalse(keys.contains("authServerUrl"),
                "a seeded web=false setting must be excluded from getAllForWeb()");
    }

    @Test
    @Transactional
    void getAllEditableReturnsOnlyEditableSettingsAndExcludesNonEditable() {
        // The seed data flags every setting editable=true, so to assert exclusion of a
        // non-editable setting we add one temporary editable=false row (rolled back with the tx).
        // designated constructor: (key, value, type, description, cached, web, editable)
        String nonEditKey = "it-test-gap-edit-no-" + System.nanoTime();
        seed(new Setting(nonEditKey, "x", Type.String, null, false, false, false));
        em.clear();

        List<Setting> editable = settingsService.getAllEditable();
        assertFalse(editable.isEmpty(), "the seed data defines editable settings");

        // Every returned setting must actually be editable.
        assertTrue(editable.stream().allMatch(Setting::isEditable),
                "getAllEditable() must only return settings with editable=true");

        List<String> keys = editable.stream().map(Setting::getKey).toList();
        // 'documentationUrl' is seeded with editable=true.
        assertTrue(keys.contains("documentationUrl"),
                "a seeded editable=true setting must be present in getAllEditable()");
        assertFalse(keys.contains(nonEditKey),
                "an editable=false setting must be excluded from getAllEditable()");
    }
}
