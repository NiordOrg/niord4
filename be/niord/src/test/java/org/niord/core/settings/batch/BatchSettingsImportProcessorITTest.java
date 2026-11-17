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
package org.niord.core.settings.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the filtering logic in {@link BatchSettingsImportProcessor#processItem(Object)},
 * exercised against the seeded database of the {@link ITTest} infrastructure.
 *
 * <p>{@code processItem} decides, for each imported {@link Setting}, whether the setting needs to be updated
 * (docs/code-data-config-plan.md §5.3). It never creates a row: a key no catalog declares, a {@code state} key, a key
 * the database has no row for and a value that does not fit the row's type are skipped, each with a line in the job's
 * log. Otherwise it returns the setting (signalling "an update is needed") when the row's stored value differs from
 * the item's, except for the special case of an empty password setting (type {@link Setting.Type#Password} with a
 * {@code null} value), which is always skipped by returning {@code null}. When the values are equal it returns
 * {@code null} (no update needed).
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its
 * {@link SettingsService} dependency, the per-instance {@link BatchData} ({@code job}) and a
 * {@code FileHandler}-backed logger that writes into the batch job folder. To test the business logic without
 * that runtime, this suite uses a {@link TestableProcessor} subclass declared in the same package: it
 * overrides {@code getLog()} to return a parent-less {@link java.util.logging.Logger} that only collects its lines
 * (so the {@code FileHandler} that targets the non-existent batch folder is never opened) and exposes a
 * {@code wire(...)} method that assigns the real {@link SettingsService} bean and a hand-built
 * {@link BatchData} directly into the package-private/protected fields. {@code processItem} is invoked
 * directly.
 *
 * <p>Every test is wrapped in {@link Transactional} and works on seeded rows of catalog keys ({@code mailSender},
 * {@code mailMaxPerMinute}, {@code wmsPassword}); a row a test removes comes back with the rollback, keeping the
 * shared seed database pristine.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchSettingsImportProcessorITTest extends ITTest {

    @Autowired
    SettingsService settingsService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchSettingsImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less logger that collects its lines
     * (avoiding any file I/O into a non-existent batch job folder) and lets the test inject the real
     * {@link SettingsService} bean and the {@code job} batch data directly. Both assigned fields are
     * package-private/protected and reachable from this same package.
     */
    static class TestableProcessor extends BatchSettingsImportProcessor {
        private final java.util.logging.Logger log =
                java.util.logging.Logger.getLogger("it-proc-settings-" + UUID.randomUUID());

        /** The lines the processor wrote to the job's log */
        final List<String> lines = new ArrayList<>();

        {
            log.setUseParentHandlers(false);
            log.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    lines.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return log;
        }

        void wire(SettingsService ss, BatchData j) {
            this.settingsService = ss;
            this.job = j;
        }
    }

    /** Wires a fresh {@link TestableProcessor} with the injected service bean and an empty batch data. */
    private TestableProcessor freshProcessor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(settingsService, new BatchData());
        return processor;
    }

    /** The stored value of a seeded row */
    private Object storedValue(String key) {
        Setting row = em.find(Setting.class, key);
        assertNotNull(row, "precondition: the seed has a " + key + " row");
        return row.getValue();
    }

    private long settingCount() {
        return em.createQuery("select count(s) from Setting s", Long.class).getSingleResult();
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) Changed value -> update needed
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void returnsSettingWhenCurrentValueDiffersFromItemValue() throws Exception {
        Object stored = storedValue("mailSender");

        // The imported item carries the SAME key but a DIFFERENT value, so an update is required.
        Setting item = new Setting("mailSender", "it-imported-" + UUID.randomUUID() + "@example.org");
        assertNotEquals(stored, item.getValue(), "precondition: the values differ");

        Object result = freshProcessor().processItem(item);

        assertNotNull(result, "a differing value must be reported as needing an update");
        assertSame(item, result, "processItem must return the very item it was handed (the update payload)");
        em.flush();
        em.clear();
        assertEquals(stored, em.find(Setting.class, "mailSender").getValue(),
                "processItem must NOT persist the update itself - the stored value is unchanged");
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) Unchanged value -> no update
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void returnsNullWhenCurrentValueEqualsItemValue() throws Exception {
        Setting item = new Setting("mailMaxPerMinute", storedValue("mailMaxPerMinute"));

        Object result = freshProcessor().processItem(item);

        assertNull(result, "an unchanged value must NOT be reported as needing an update");
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) Empty password -> always skipped
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void returnsNullForEmptyPasswordEvenWhenValueDiffers() throws Exception {
        assertNotNull(storedValue("wmsPassword"), "precondition: the password setting has a stored value");

        // The imported item is an EMPTY password (type Password, null value) for the same key: an export from the
        // Settings page carries no password. Without the guard this would be an update.
        Setting emptyPwd = new Setting("wmsPassword", null, Setting.Type.Password, null, true, false, false);

        Object result = freshProcessor().processItem(emptyPwd);

        assertNull(result, "an empty password must be skipped even though the stored value differs from null");
    }

    /**
     * The guard tests the type the row (else the catalog) gives, not the file's: an entry without a type, as a
     * hand-made file has it, does not clear the password either
     */
    @Test
    @Transactional
    void returnsNullForAnUntypedEmptyPassword() throws Exception {
        assertNotNull(storedValue("wmsPassword"), "precondition: the password setting has a stored value");
        Setting untyped = new Setting("wmsPassword", null);
        assertNull(untyped.getType(), "precondition: the item carries no type");

        assertNull(freshProcessor().processItem(untyped), "an untyped empty password must be skipped too");
    }

    /** A required key keeps a value: a null or blank one in the file is skipped, naming why */
    @Test
    @Transactional
    void aBlankValueForARequiredKeyIsSkipped() throws Exception {
        assertNotNull(storedValue("country"), "precondition: the deployment has a country row");
        for (Object blank : java.util.Arrays.asList("", "  ", null)) {
            TestableProcessor processor = freshProcessor();

            assertNull(processor.processItem(new Setting("country", blank)), "country = '" + blank + "'");
            assertTrue(processor.lines.contains("Skipped country: required, and the file gives no value"),
                    processor.lines::toString);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // (4) Refused keys and values -> skipped with a line in the job's log, never a row created
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void aKeyNoCatalogDeclaresIsSkippedAndNoRowIsCreated() throws Exception {
        String key = "it.batch.setting." + UUID.randomUUID();
        long before = settingCount();
        TestableProcessor processor = freshProcessor();

        assertNull(processor.processItem(new Setting(key, "v2")));

        em.flush();
        em.clear();
        assertNull(em.find(Setting.class, key), "the import never creates a row");
        assertEquals(before, settingCount());
        assertTrue(processor.lines.contains("Skipped " + key + ": no catalog declares it"), processor.lines::toString);
    }

    @Test
    @Transactional
    void aStateKeyIsSkipped() throws Exception {
        TestableProcessor processor = freshProcessor();

        assertNull(processor.processItem(new Setting("areaLastUpdate", 1_600_000_000_000L)));

        assertTrue(processor.lines.stream().anyMatch(l -> l.startsWith("Skipped areaLastUpdate: a state setting")),
                processor.lines::toString);
    }

    @Test
    @Transactional
    void aCatalogKeyWithoutARowIsSkippedAndNoRowIsCreated() throws Exception {
        em.remove(em.find(Setting.class, "mailSender"));
        em.flush();
        TestableProcessor processor = freshProcessor();

        assertNull(processor.processItem(new Setting("mailSender", "it@example.org")));

        em.flush();
        assertNull(em.find(Setting.class, "mailSender"), "the import never creates a row");
        assertTrue(processor.lines.contains("Skipped mailSender: this deployment has no such setting"),
                processor.lines::toString);
    }

    @Test
    @Transactional
    void aValueOfTheWrongTypeIsSkipped() throws Exception {
        TestableProcessor processor = freshProcessor();

        assertNull(processor.processItem(new Setting("mailMaxPerMinute", "lots")));

        assertTrue(processor.lines.stream().anyMatch(l -> l.startsWith("Skipped mailMaxPerMinute: Integer-typed, takes")),
                processor.lines::toString);
    }

    // ------------------------------------------------------------------------------------------- //
    // Unchecked-cast contract
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void throwsClassCastExceptionForNonSettingItem() {
        TestableProcessor processor = freshProcessor();

        // processItem performs an unchecked cast to Setting; a wrong-typed item must fail fast.
        assertThrows(ClassCastException.class, () -> processor.processItem("not-a-setting"));
    }
}
