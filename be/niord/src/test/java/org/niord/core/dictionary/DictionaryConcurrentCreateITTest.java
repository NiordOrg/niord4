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
package org.niord.core.dictionary;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DICT5: two writers of one dictionary key at the same moment store it once. {@code "DictionaryEntry"} has no unique
 * index on (dictionary, key), by V006's decision, so {@code createEntry} and the dictionary import take a
 * transaction-level advisory lock keyed on the dictionary ({@link DictionaryService#DICTIONARY_LOCK_SPACE} and the
 * name's hash) before they read whether the key exists, and read it from the database. Without the lock both read
 * the key as missing and both insert it; the map in memory keeps one, and the other row comes back when the first is
 * deleted.
 *
 * <p>Deterministic: transaction A creates the key and holds its transaction open; transaction B starts its own write
 * of the key and is seen waiting in {@code pg_locks} through a separate connection; only then does A commit. Both
 * run in this one context, each in its own committed transaction, as two requests on two nodes would.
 */
class DictionaryConcurrentCreateITTest extends ITTest {

    @Autowired
    DictionaryService dictionaryService;

    @PersistenceContext
    EntityManager em;

    private final String name = "it-dict-lock-" + System.nanoTime();

    private static final String KEY = "it.dict5.key";

    @BeforeEach
    void createDictionary() {
        Tx.requiringNew().run(() -> {
            Dictionary dictionary = new Dictionary();
            dictionary.setName(name);
            em.persist(dictionary);
        });
    }

    @Test
    void twoCreatesOfOneKeyStoreItOnceAndTheSecondIsRefused() throws Exception {
        CountDownLatch aCreated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executor.submit(() -> Tx.requiringNew().call(() -> {
                Integer id = dictionaryService.createEntry(name, entry("First")).getId();
                aCreated.countDown();
                assertTrue(release.await(60, TimeUnit.SECONDS), "A was never released");
                return id;
            }));
            assertTrue(aCreated.await(60, TimeUnit.SECONDS), "A never created the key");

            Future<Integer> b = executor.submit(() -> Tx.requiringNew().call(
                    () -> dictionaryService.createEntry(name, entry("Second")).getId()));
            awaitWaitingOnTheLockOrDone(b);
            release.countDown();

            a.get(60, TimeUnit.SECONDS);
            ExecutionException refusal = assertThrows(ExecutionException.class, () -> b.get(60, TimeUnit.SECONDS),
                    "B must be refused: A has created the key");
            assertInstanceOf(IllegalArgumentException.class, refusal.getCause());
            assertEquals("Entry already exists: " + KEY, refusal.getCause().getMessage());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertEquals(1L, rows(), "one row for the key");
        assertEquals("First", value(), "the first create's value");
    }

    @Test
    void anImportOfTheKeyWaitsForACreateAndThenKeepsTheCreatedRow() throws Exception {
        CountDownLatch aCreated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executor.submit(() -> Tx.requiringNew().call(() -> {
                Integer id = dictionaryService.createEntry(name, entry("Created")).getId();
                aCreated.countDown();
                assertTrue(release.await(60, TimeUnit.SECONDS), "A was never released");
                return id;
            }));
            assertTrue(aCreated.await(60, TimeUnit.SECONDS), "A never created the key");

            Future<DictionaryService.ImportResult> b = executor.submit(() -> Tx.requiringNew().call(() -> {
                Dictionary file = new Dictionary();
                file.setName(name);
                file.createEntry(KEY).checkCreateDesc("en").setValue("Imported");
                return dictionaryService.importDictionary(file, DictionaryService.ImportChoice.DEFAULT);
            }));
            awaitWaitingOnTheLockOrDone(b);
            release.countDown();

            a.get(60, TimeUnit.SECONDS);
            DictionaryService.ImportResult result = b.get(60, TimeUnit.SECONDS);
            assertEquals(0, result.added(), "the import finds the key the create committed");
            assertEquals(1, result.kept(), "and keeps it: no overwrite was ticked");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertEquals(1L, rows(), "one row for the key");
        assertEquals("Created", value(), "the created row's value");
    }

    @AfterEach
    void removeDictionary() {
        Tx.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM public.\"DictionaryEntryDesc\" WHERE entity_id IN (SELECT e.id FROM "
                    + "public.\"DictionaryEntry\" e JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                    + "WHERE d.name = :name)").setParameter("name", name).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"DictionaryEntry\" WHERE dictionary_id IN (SELECT id FROM "
                    + "public.\"Dictionary\" WHERE name = :name)").setParameter("name", name).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"Dictionary\" WHERE name = :name")
                    .setParameter("name", name).executeUpdate();
        });
    }

    // ------------------------------------------------------------------------------------------- //

    private static DictionaryEntry entry(String value) {
        DictionaryEntry entry = new DictionaryEntry();
        entry.setKey(KEY);
        entry.createDesc("en").setValue(value);
        return entry;
    }

    /**
     * Polls pg_locks, on a connection of its own, until a session waits for the dictionary's lock, or until the
     * second writer has finished without waiting (as it did before the lock): its result then shows what it did
     */
    private void awaitWaitingOnTheLockOrDone(Future<?> second) throws SQLException, InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        try (Connection c = DriverManager.getConnection(dbJdbcUrl(), dbUsername(), dbPassword());
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                     + "AND classid::bigint = ? AND objid::bigint = ? AND objsubid = 2 AND NOT granted")) {
            ps.setLong(1, DictionaryService.DICTIONARY_LOCK_SPACE);
            ps.setLong(2, name.hashCode() & 0xFFFFFFFFL);
            while (System.currentTimeMillis() < deadline) {
                if (second.isDone()) {
                    return;
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("The second writer neither waited for the dictionary's lock nor finished in 60 s");
    }

    private long rows() throws SQLException {
        try (Connection c = DriverManager.getConnection(dbJdbcUrl(), dbUsername(), dbPassword());
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM public.\"DictionaryEntry\" e "
                     + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id WHERE d.name = ? AND e.dict_key = ?")) {
            ps.setString(1, name);
            ps.setString(2, KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String value() throws SQLException {
        try (Connection c = DriverManager.getConnection(dbJdbcUrl(), dbUsername(), dbPassword());
             PreparedStatement ps = c.prepareStatement("SELECT t.value FROM public.\"DictionaryEntryDesc\" t "
                     + "JOIN public.\"DictionaryEntry\" e ON t.entity_id = e.id "
                     + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                     + "WHERE d.name = ? AND e.dict_key = ? AND t.lang = 'en' ORDER BY e.id")) {
            ps.setString(1, name);
            ps.setString(2, KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
