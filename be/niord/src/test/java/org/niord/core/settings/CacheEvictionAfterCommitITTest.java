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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.dictionary.Dictionary;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.dictionary.DictionaryService;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write to a setting or a dictionary empties the cache again after it commits (SETTINGS-9). The cache is emptied
 * inside the write's transaction too, and a read on another thread before the commit fills it again from the old
 * row; without the second eviction that old value stayed: a setting's for up to three minutes, a dictionary's until
 * the next change of that dictionary or a restart.
 *
 * <p>Each test holds the write's transaction open after the write (latches, no sleeps), reads on this thread in that
 * window (which gives the old value, and caches it), lets the write commit, and asserts that the next read gives the
 * new value. Every write commits, so each test puts back what it changed: the setting's old value, and the synthetic
 * dictionary, its entries and its ledger rows.
 */
class CacheEvictionAfterCommitITTest extends ITTest {

    /** An operator setting the seed has a row for, a plain text that no test reads for its meaning */
    private static final String SETTING = "mailSender";

    @Autowired
    SettingsService settingsService;

    @Autowired
    DictionaryService dictionaryService;

    @PersistenceContext
    EntityManager em;

    private final String dictionary = "it-cache-" + System.nanoTime();

    @Test
    void aSettingReadBeforeTheWriteCommitsDoesNotKeepTheOldValue() throws Exception {
        Object original = Tx.requiringNew().call(() -> em.find(Setting.class, SETTING).getValue());
        Object current = settingsService.get(SETTING);
        String changed = "it-cache-" + System.nanoTime() + "@example.org";
        try {
            assertNotStale(() -> settingsService.set(SETTING, changed),
                    () -> settingsService.get(SETTING), current, changed);
        } finally {
            settingsService.set(SETTING, original);
            settingsService.evictFromCache(SETTING);
        }
    }

    @Test
    void aDictionaryReadBeforeAnUpdateCommitsDoesNotKeepTheOldText() throws Exception {
        createDictionary("Old text");

        assertNotStale(() -> dictionaryService.updateEntry(dictionary, entry("it.key", "New text")),
                this::text, "Old text", "New text");
    }

    @Test
    void aDictionaryReadBeforeACreateCommitsDoesNotKeepTheMissingKey() throws Exception {
        createDictionary(null);

        assertNotStale(() -> dictionaryService.createEntry(dictionary, entry("it.key", "Added text")),
                this::text, null, "Added text");
    }

    @Test
    void aDictionaryReadBeforeADeleteCommitsDoesNotKeepTheDeletedKey() throws Exception {
        createDictionary("Deleted text");

        assertNotStale(() -> dictionaryService.deleteEntry(dictionary, "it.key"),
                this::text, "Deleted text", null);
    }

    @Test
    void aDictionaryReadBeforeAnImportCommitsDoesNotKeepTheMissingKey() throws Exception {
        createDictionary(null);
        Dictionary imported = new Dictionary();
        imported.setName(dictionary);
        imported.getEntries().put("it.key", entry("it.key", "Imported text"));

        assertNotStale(() -> dictionaryService.importDictionary(imported, DictionaryService.ImportChoice.DEFAULT),
                this::text, null, "Imported text");
    }

    @AfterEach
    void removeSyntheticDictionary() {
        Tx.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM public.\"DictionaryEntryDesc\" WHERE entity_id IN (SELECT e.id FROM "
                    + "public.\"DictionaryEntry\" e JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                    + "WHERE d.name = :name)").setParameter("name", dictionary).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"DictionaryEntry\" WHERE dictionary_id IN (SELECT id FROM "
                    + "public.\"Dictionary\" WHERE name = :name)").setParameter("name", dictionary).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"Dictionary\" WHERE name = :name")
                    .setParameter("name", dictionary).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE dictionary = :name")
                    .setParameter("name", dictionary).executeUpdate();
        });
    }

    // ------------------------------------------------------------------------------------------- //

    /**
     * Runs the write in a transaction of its own on another thread and holds that transaction open after the write;
     * reads on this thread in that window, then lets the write commit and reads again.
     *
     * @param write  the write, run inside the held transaction
     * @param read   the read through the cache
     * @param before the value the read gives while the write has not committed
     * @param after  the value the read must give once the write has committed
     */
    private void assertNotStale(Runnable write, Supplier<Object> read, Object before, Object after)
            throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch readInTheWindow = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> Tx.requiringNew().run(() -> {
                write.run();
                em.flush();
                written.countDown();
                assertTrue(readInTheWindow.await(60, TimeUnit.SECONDS), "the window's read never came");
            }));
            assertTrue(written.await(60, TimeUnit.SECONDS), "the write never ran");

            assertEquals(before, read.get(), "precondition: before the commit a read gives the old value");
            readInTheWindow.countDown();
            writer.get(60, TimeUnit.SECONDS);

            assertEquals(after, read.get(), "after the commit the next read gives the new value");
        } finally {
            readInTheWindow.countDown();
            executor.shutdownNow();
        }
    }

    /** The English text of the synthetic dictionary's key, read through the dictionary cache */
    private Object text() {
        return Tx.requiringNew().call(() -> dictionaryService.value(dictionary, "en", "it.key"));
    }

    /** Commits the synthetic dictionary, with the key in English when a text is given, and caches it */
    private void createDictionary(String text) {
        Tx.requiringNew().run(() -> {
            Dictionary dict = new Dictionary();
            dict.setName(dictionary);
            em.persist(dict);
        });
        if (text != null) {
            dictionaryService.createEntry(dictionary, entry("it.key", text));
        }
        assertEquals(text, text(), "precondition: the committed dictionary is read and cached");
    }

    /** A detached entry template with an English text */
    private static DictionaryEntry entry(String key, String text) {
        DictionaryEntry entry = new DictionaryEntry();
        entry.setKey(key);
        entry.createDesc("en").setValue(Objects.requireNonNull(text));
        return entry;
    }
}
