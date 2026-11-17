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
import org.niord.core.area.AreaService;
import org.niord.core.settings.Setting.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SettingsService}, run against the seeded MySQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>Each test but the state writer's is wrapped in {@link Transactional} so all database writes are rolled back,
 * keeping the shared seed database pristine and the tests independent. The state writer's test commits, as the
 * system does, and puts back what was there.
 *
 * <p><b>Cache caveat:</b> {@link SettingsService} reads through an Infinispan {@code SettingsCache}.
 * The rollback restores the database but NOT the in-memory cache, and {@code get()} caches what it reads, a
 * setting's default included, using {@code cached=true} by default. To keep tests isolated and repeatable, the
 * read tests use a UNIQUE {@code it-test-...} key; a test that reads or writes a catalog key ({@code set} writes only
 * those, since C3b) evicts it afterwards, and {@code set} evicts the key it writes. This coupling is documented in
 * docs/framework-coupled-tests.md.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SettingsServiceITTest extends ITTest {

    @Autowired
    SettingsService settingsService;

    @PersistenceContext
    EntityManager em;

    /** Persists a setting row directly, bypassing the service, so set()/get() have a row to act on. */
    private Setting seed(Setting setting) {
        em.persist(setting);
        em.flush();
        return setting;
    }

    /** Counts the rows currently in the Setting table */
    private long settingCount() {
        return em.createQuery("select count(s) from Setting s", Long.class).getSingleResult();
    }

    /** Removes a catalog key's row inside the test transaction, so the rollback brings it back */
    private void removeRow(String key) {
        Setting row = em.find(Setting.class, key);
        assertNotNull(row, "precondition: start-up added " + key);
        em.remove(row);
        em.flush();
        settingsService.evictFromCache(key);
    }

    // ------------------------------------------------------------------------------------------- //
    // get(key) — a missing setting resolves to its default and is never created
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getOfAnUnknownKeyReturnsTheTemplatesDefaultAndCreatesNoRow() {
        String key = "it-test-missing-" + System.nanoTime();
        assertNull(em.find(Setting.class, key), "precondition: setting must not already exist");
        long before = settingCount();

        assertEquals("template-default", settingsService.get(new Setting(key, "template-default")));

        em.flush();
        em.clear();
        assertNull(em.find(Setting.class, key), "a read never creates a row");
        assertEquals(before, settingCount());
    }

    @Test
    @Transactional
    void getOfACatalogKeyWithoutARowReturnsTheSettingsFilesValue() {
        // The test tree's root niord.json, read as the country's settings file, sets infoDefaultLanguage to da
        removeRow("infoDefaultLanguage");
        long before = settingCount();
        try {
            assertEquals("da", settingsService.get(new Setting("infoDefaultLanguage", "en")),
                    "the settings file's value, not the template's");

            em.flush();
            em.clear();
            assertNull(em.find(Setting.class, "infoDefaultLanguage"), "a read never creates a row");
            assertEquals(before, settingCount());
        } finally {
            settingsService.evictFromCache("infoDefaultLanguage");
        }
    }

    @Test
    @Transactional
    void getReturnsPersistedValueForExistingSetting() {
        String key = "it-test-existing-" + System.nanoTime();
        seed(new Setting(key, "hello-world"));
        em.clear();

        assertEquals("hello-world", settingsService.get(key));
    }

    // ------------------------------------------------------------------------------------------- //
    // peek(key) — never auto-creates
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void peekReturnsNullForUnknownKeyAndDoesNotCreateIt() {
        String key = "it-test-peek-missing-" + System.nanoTime();

        assertNull(settingsService.peek(key), "peek() must return null for an unknown key");

        em.flush();
        em.clear();
        assertNull(em.find(Setting.class, key), "peek() must NOT auto-create a row");
    }

    @Test
    @Transactional
    void peekOfACatalogKeyWithoutARowReturnsTheSettingsFilesValue() {
        removeRow("infoDefaultLanguage");

        assertEquals("da", settingsService.peek("infoDefaultLanguage"));

        em.flush();
        em.clear();
        assertNull(em.find(Setting.class, "infoDefaultLanguage"), "peek() never creates a row");
    }

    @Test
    @Transactional
    void peekReturnsValueOfExistingSetting() {
        String key = "it-test-peek-existing-" + System.nanoTime();
        seed(new Setting(key, "peek-value"));
        em.clear();

        assertEquals("peek-value", settingsService.peek(key));
    }

    @Test
    @Transactional
    void peekThrowsForNullKey() {
        assertThrows(NullPointerException.class, () -> settingsService.peek(null));
    }

    // ------------------------------------------------------------------------------------------- //
    // set(key, value) — persistence and constraints, on catalog keys that have rows
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void setUpdatesExistingSettingAndPersistsTheNewValue() {
        Setting returned = settingsService.set("mailSender", "it-new@example.org");
        assertEquals("it-new@example.org", returned.getValue());

        em.flush();
        em.clear();

        Setting reloaded = em.find(Setting.class, "mailSender");
        assertEquals("it-new@example.org", reloaded.getValue(), "the updated value must be persisted to the database");
    }

    @Test
    @Transactional
    void setThrowsForNonExistingSetting() {
        String key = "it-test-set-missing-" + System.nanoTime();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> settingsService.set(key, "value"));
        assertTrue(ex.getMessage().contains(key),
                "the exception message must reference the missing key");

        em.flush();
        em.clear();
        assertNull(em.find(Setting.class, key), "set() must NOT create the missing setting");
    }

    @Test
    @Transactional
    void setThrowsForACatalogKeyWithoutARow() {
        removeRow("mailSender");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> settingsService.set("mailSender", "it@example.org"));
        assertEquals("Non-existing setting mailSender", ex.getMessage());

        em.flush();
        assertNull(em.find(Setting.class, "mailSender"), "set() must NOT create the missing setting");
    }

    @Test
    @Transactional
    void setRefusesARowNoCatalogDeclares() {
        // A row of its own that no code reads or declares, rolled back with the test
        String key = "it-undeclared-" + System.nanoTime();
        seed(new Setting(key, "x"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> settingsService.set(key, "it-changed"));
        assertEquals("Unknown setting " + key + ": no catalog declares it", ex.getMessage());

        em.flush();
        em.clear();
        assertEquals("x", em.find(Setting.class, key).getValue(), "the row is unchanged");
    }

    @Test
    @Transactional
    void setRefusesAStateKey() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> settingsService.set(AreaService.AREA_LAST_UPDATE.getKey(), 1L));
        assertEquals("areaLastUpdate is a state setting: only the system writes it", ex.getMessage());
    }

    @Test
    @Transactional
    void setRefusesAValueOfTheWrongType() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> settingsService.set("mailMaxPerMinute", "lots"));
        assertEquals("mailMaxPerMinute is Integer-typed and takes an integral number in int range",
                ex.getMessage());
        assertEquals(10, em.find(Setting.class, "mailMaxPerMinute").getValue(), "the row is unchanged");
    }

    /** A row wins over the settings file, so a required key's row may not be emptied (second review of PR #179) */
    @Test
    @Transactional
    void setRefusesANullOrBlankValueForARequiredKey() {
        Object stored = em.find(Setting.class, "country").getValue();
        for (Object blank : java.util.Arrays.asList(null, "", "  ")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> settingsService.set("country", blank));
            assertEquals("country is required: it takes a value, not an empty one", ex.getMessage());
        }
        assertEquals(stored, em.find(Setting.class, "country").getValue(), "the row is unchanged");
    }

    @Test
    @Transactional
    void setPersistsAnIntegerValueAsAnInteger() {
        settingsService.set("mailMaxPerMinute", 42);
        em.flush();
        em.clear();

        // The JSON attribute converter reads numbers without a fraction back as Integer.
        Object reloaded = em.find(Setting.class, "mailMaxPerMinute").getValue();
        assertEquals(Integer.valueOf(42), reloaded);
    }

    @Test
    @Transactional
    void setCanStoreNullValue() {
        settingsService.set("mailSender", null);
        em.flush();
        em.clear();

        assertNull(em.find(Setting.class, "mailSender").getValue(), "set() must be able to clear a value");
    }

    // ------------------------------------------------------------------------------------------- //
    // writeState — the internal writer of state settings, committed as the system writes them
    // ------------------------------------------------------------------------------------------- //

    @Test
    void writeStateCreatesTheRowWithTheCatalogsFlagsAndThenUpdatesOnlyTheValue() {
        String key = AreaService.AREA_LAST_UPDATE.getKey();
        Setting previous = Tx.requiringNew().call(() -> {
            Setting row = em.find(Setting.class, key);
            if (row != null) {
                em.detach(row);
                Setting copy = new Setting(row);
                em.remove(em.find(Setting.class, key));
                return copy;
            }
            return null;
        });
        try {
            settingsService.writeState(AreaService.AREA_LAST_UPDATE, 1_600_000_000_000L);

            Setting created = Tx.requiringNew().call(() -> em.find(Setting.class, key));
            assertNotNull(created, "the first write creates the row");
            assertEquals(1_600_000_000_000L, ((Number) created.getValue()).longValue());
            assertEquals(Type.Date, created.getType());
            assertEquals("When the area tree's sort order was last recomputed", created.getDescription());
            assertFalse(created.isCached());
            assertFalse(created.isWeb());
            assertFalse(created.isEditable());

            // An existing deployment's row keeps what it has: only the value changes
            Tx.requiringNew().run(() -> em.createNativeQuery(
                    "UPDATE public.\"Setting\" SET description = NULL, type = NULL WHERE setting_key = :key")
                    .setParameter("key", key).executeUpdate());
            settingsService.writeState(AreaService.AREA_LAST_UPDATE, 1_700_000_000_000L);

            Setting updated = Tx.requiringNew().call(() -> em.find(Setting.class, key));
            assertEquals(1_700_000_000_000L, ((Number) updated.getValue()).longValue());
            assertNull(updated.getType(), "the type is never changed");
            assertNull(updated.getDescription(), "the description is never changed");
            assertEquals(new Date(1_700_000_000_000L), settingsService.getDate(AreaService.AREA_LAST_UPDATE));
        } finally {
            Tx.requiringNew().run(() -> {
                Setting row = em.find(Setting.class, key);
                if (row != null) {
                    em.remove(row);
                }
            });
            if (previous != null) {
                Tx.requiringNew().run(() -> em.persist(previous));
            }
            settingsService.evictFromCache(key);
        }
    }

    @Test
    void writeStateRefusesAnOperatorKey() {
        assertThrows(IllegalArgumentException.class,
                () -> settingsService.writeState(new Setting("mailSender"), "it@example.org"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Typed accessors — coercion of stored values
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getStringReturnsStringAndCoercesNonStrings() {
        String strKey = "it-test-getstring-str-" + System.nanoTime();
        String numKey = "it-test-getstring-num-" + System.nanoTime();
        seed(new Setting(strKey, "a-string"));
        seed(new Setting(numKey, 123));
        em.clear();

        assertEquals("a-string", settingsService.getString(strKey));
        // A non-String value is coerced via toString().
        assertEquals("123", settingsService.getString(numKey));
    }

    @Test
    @Transactional
    void getStringReturnsNullForNullValuedSetting() {
        String key = "it-test-getstring-null-" + System.nanoTime();
        seed(new Setting(key, (Object) null));
        em.clear();

        assertNull(settingsService.getString(key));
    }

    @Test
    @Transactional
    void getBooleanRecognisesTruthyTokens() {
        String tKey = "it-test-bool-true-" + System.nanoTime();
        String yKey = "it-test-bool-yes-" + System.nanoTime();
        String realKey = "it-test-bool-real-" + System.nanoTime();
        seed(new Setting(tKey, "true"));
        seed(new Setting(yKey, "yes"));
        seed(new Setting(realKey, Boolean.TRUE));
        em.clear();

        assertEquals(Boolean.TRUE, settingsService.getBoolean(tKey));
        assertEquals(Boolean.TRUE, settingsService.getBoolean(yKey));
        assertEquals(Boolean.TRUE, settingsService.getBoolean(realKey));
    }

    @Test
    @Transactional
    void getBooleanReadsFalseTokensAndLeavesJunkUnset() {
        String falseKey = "it-test-bool-false-" + System.nanoTime();
        String noKey = "it-test-bool-no-" + System.nanoTime();
        String paddedKey = "it-test-bool-padded-" + System.nanoTime();
        String junkKey = "it-test-bool-junk-" + System.nanoTime();
        seed(new Setting(falseKey, "false"));
        seed(new Setting(noKey, "no"));
        seed(new Setting(paddedKey, " FALSE "));
        seed(new Setting(junkKey, "maybe"));
        em.clear();

        assertEquals(Boolean.FALSE, settingsService.getBoolean(falseKey));
        assertEquals(Boolean.FALSE, settingsService.getBoolean(noKey));
        assertEquals(Boolean.FALSE, settingsService.getBoolean(paddedKey), "trimmed and read case-insensitively");
        // Null, so a typo keeps the default of the setting it was meant for (SETTINGS-3)
        assertNull(settingsService.getBoolean(junkKey));
    }

    @Test
    @Transactional
    void getBooleanOfASystemPropertyFalseSwitchesOffASettingThatDefaultsToTrue() {
        String key = "it-test-bool-sysprop-false-" + System.nanoTime();
        Setting trueByDefault = new Setting(key, Boolean.TRUE);
        System.setProperty(key, "false");
        try {
            assertEquals(Boolean.FALSE, settingsService.getBoolean(trueByDefault),
                    "a system property false must switch off a Boolean setting whose default is true");
        } finally {
            System.clearProperty(key);
        }

        assertEquals(Boolean.TRUE, settingsService.getBoolean(trueByDefault),
                "without the system property, the default");
    }

    @Test
    @Transactional
    void getIntegerCoercesNumbersAndNumericStrings() {
        String numKey = "it-test-int-num-" + System.nanoTime();
        String strKey = "it-test-int-str-" + System.nanoTime();
        seed(new Setting(numKey, 7));
        seed(new Setting(strKey, "55"));
        em.clear();

        assertEquals(Integer.valueOf(7), settingsService.getInteger(numKey));
        assertEquals(Integer.valueOf(55), settingsService.getInteger(strKey));
    }

    @Test
    @Transactional
    void getIntegerThrowsForNonNumericString() {
        String key = "it-test-int-bad-" + System.nanoTime();
        seed(new Setting(key, "not-a-number"));
        em.clear();

        assertThrows(NumberFormatException.class, () -> settingsService.getInteger(key));
    }

    @Test
    @Transactional
    void getLongCoercesNumbersAndStrings() {
        String numKey = "it-test-long-num-" + System.nanoTime();
        String strKey = "it-test-long-str-" + System.nanoTime();
        seed(new Setting(numKey, 9));
        seed(new Setting(strKey, "9000000000")); // larger than Integer.MAX_VALUE
        em.clear();

        assertEquals(Long.valueOf(9L), settingsService.getLong(numKey));
        assertEquals(Long.valueOf(9_000_000_000L), settingsService.getLong(strKey));
    }

    @Test
    @Transactional
    void getDoubleCoercesNumbersAndStrings() {
        String numKey = "it-test-double-num-" + System.nanoTime();
        String strKey = "it-test-double-str-" + System.nanoTime();
        seed(new Setting(numKey, 1.5d));
        seed(new Setting(strKey, "2.25"));
        em.clear();

        assertEquals(1.5d, settingsService.getDouble(numKey), 0.0);
        assertEquals(2.25d, settingsService.getDouble(strKey), 0.0);
    }

    @Test
    @Transactional
    void getFloatCoercesNumbersAndStrings() {
        String numKey = "it-test-float-num-" + System.nanoTime();
        String strKey = "it-test-float-str-" + System.nanoTime();
        seed(new Setting(numKey, 3.5d));
        seed(new Setting(strKey, "4.75"));
        em.clear();

        assertEquals(3.5f, settingsService.getFloat(numKey), 0.0f);
        assertEquals(4.75f, settingsService.getFloat(strKey), 0.0f);
    }

    @Test
    @Transactional
    void getDateRoundTripsThroughEpochMillis() {
        String key = "it-test-date-" + System.nanoTime();
        Date date = new Date(1_600_000_000_000L);
        seed(new Setting(key, date.getTime()));
        em.clear();

        assertEquals(date, settingsService.getDate(key),
                "getDate must reconstruct the Date from its stored epoch-millis Long");
    }

    @Test
    @Transactional
    void getPathBuildsPathFromStringValue() {
        String key = "it-test-path-" + System.nanoTime();
        seed(new Setting(key, "/var/niord/repo"));
        em.clear();

        assertEquals("/var/niord/repo", settingsService.getPath(key).toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // ${token} expansion
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getExpandsNestedSettingToken() {
        long n = System.nanoTime();
        String baseKey = "it-test-token-base-" + n;
        String refKey = "it-test-token-ref-" + n;
        seed(new Setting(baseKey, "BASE"));
        seed(new Setting(refKey, "prefix-${" + baseKey + "}-suffix"));
        em.clear();

        assertEquals("prefix-BASE-suffix", settingsService.getString(refKey),
                "a ${token} must be expanded with the referenced setting's value");
    }

    @Test
    @Transactional
    void getExpandsUnresolvableTokenToEmptyString() {
        long n = System.nanoTime();
        String key = "it-test-token-unknown-" + n;
        // The referenced token does not exist as a setting, system property or env var.
        seed(new Setting(key, "x-${it-test-no-such-token-" + n + "}-y"));
        em.clear();

        // An unresolvable ${token} is replaced with an empty
        // string. expandSettingValue() calls SettingValueExpander.replaceToken(token, "") to blank out
        // the token, and that method now actually removes the token text and records it (rather than
        // short-circuiting on the blank replacement and spinning until the livelock guard trips), so
        // the value expands cleanly to "x--y".
        assertEquals("x--y", settingsService.getString(key),
                "an unresolvable ${token} must be replaced with an empty string, dropping the token");
    }

    @Test
    @Transactional
    void peekAlsoExpandsTokens() {
        long n = System.nanoTime();
        String baseKey = "it-test-peek-token-base-" + n;
        String refKey = "it-test-peek-token-ref-" + n;
        seed(new Setting(baseKey, "VAL"));
        seed(new Setting(refKey, "[${" + baseKey + "}]"));
        em.clear();

        assertEquals("[VAL]", settingsService.peek(refKey));
    }

    // ------------------------------------------------------------------------------------------- //
    // JSON round-tripping
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void setAsJsonPersistsTheSerialisedJsonString() {
        // setAsJson() delegates to set(), so the key must be a catalog key with a row: a json setting
        String key = "editorFieldsNw";
        assertNotNull(em.find(Setting.class, key), "precondition: the seed has the row");
        em.clear();

        settingsService.setAsJson(key, List.of("da", "en"));
        em.flush();
        em.clear();

        // setAsJson stores JsonUtils.toJson(data), i.e. the serialised JSON *string* as the value.
        assertEquals("[\"da\",\"en\"]", em.find(Setting.class, key).getValue());
    }

    @Test
    @Transactional
    void getFromJsonParsesAStoredJsonObjectValueIntoMap() {
        String key = "it-test-json-map-" + System.nanoTime();
        // A genuine JSON-typed value (a Map): the converter stores/reads it as a structured object,
        // so getFromJson can serialise+reparse it into the requested target class.
        seed(new Setting(key, Map.of("a", 1, "b", 2)));
        em.clear();

        @SuppressWarnings("unchecked")
        Map<String, Object> map = settingsService.getFromJson(key, Map.class);
        assertNotNull(map, "getFromJson must parse a structured JSON value");
        assertEquals(2, map.size());
        assertEquals(1, map.get("a"));
        assertEquals(2, map.get("b"));
    }

    @Test
    @Transactional
    void getFromJsonParsesAStoredJsonArrayValueIntoList() {
        String key = "it-test-json-arr-" + System.nanoTime();
        seed(new Setting(key, List.of("da", "en")));
        em.clear();

        @SuppressWarnings("unchecked")
        List<String> list = settingsService.getFromJson(key, List.class);
        assertEquals(List.of("da", "en"), list);
    }

    // ------------------------------------------------------------------------------------------- //
    // getAllForWeb / getAllEditable — named-query backed reads
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getAllForWebServesDeclaredWebSettingsOnly() {
        long n = System.nanoTime();
        String undeclaredWebKey = "it-test-web-yes-" + n;
        String nonWebKey = "it-test-web-no-" + n;
        // designated constructor: (key, value, type, description, cached, web, editable)
        seed(new Setting(undeclaredWebKey, "w", Type.String, null, false, true, false));
        // One of the legacy rows DK's and GL's databases still hold with web=true; no catalog declares it
        seed(new Setting("faMrnPrefix", "urn:mrn:legacy", Type.String, null, false, true, true));
        seed(new Setting(nonWebKey, "n", Type.String, null, false, false, false));
        em.clear();

        List<String> keys = settingsService.getAllForWeb().stream()
                .map(Setting::getKey)
                .toList();
        assertTrue(keys.contains("documentationUrl"), "a declared web=true setting must be returned");
        // SETTINGS-11: the public list leaves out a row no catalog declares, as the Settings page does
        // (owner, 2026-10-21 and 2026-11-16; plan §5.6); the row stays in the database untouched
        assertFalse(keys.contains(undeclaredWebKey), "a web=true row no catalog declares must be left out");
        assertFalse(keys.contains("faMrnPrefix"), "a legacy web=true row no catalog declares must be left out");
        assertFalse(keys.contains(nonWebKey), "web=false settings must be excluded");
        assertNotNull(em.find(Setting.class, "faMrnPrefix"), "the undeclared row itself is not touched");
    }

    @Test
    @Transactional
    void getAllEditableIncludesDeclaredEditableSettingsAndExcludesTheRest() {
        long n = System.nanoTime();
        String undeclaredEditKey = "it-test-edit-yes-" + n;
        String nonEditKey = "it-test-edit-no-" + n;
        seed(new Setting(undeclaredEditKey, "e", Type.String, null, false, false, true));
        seed(new Setting(nonEditKey, "x", Type.String, null, false, false, false));
        em.clear();

        List<String> keys = settingsService.getAllEditable().stream()
                .map(Setting::getKey)
                .toList();
        assertTrue(keys.contains("documentationUrl"), "a declared editable=true setting must be returned");
        // Since C4.2a the page lists only what a catalog declares (owner, 2026-10-21; plan §5.6)
        assertFalse(keys.contains(undeclaredEditKey), "an editable row no catalog declares must be left out");
        assertFalse(keys.contains(nonEditKey), "editable=false settings must be excluded");
    }
}
