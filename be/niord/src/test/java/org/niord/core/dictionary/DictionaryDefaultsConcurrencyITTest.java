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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.defaults.DefaultTexts;
import org.niord.core.defaults.DefaultsLedger;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two start-ups on one database (docs/code-data-config-plan.md §4.2, replicas): the add-missing step takes the
 * advisory lock {@link DefaultsLedger#ADD_MISSING_LOCK} before it reads the database, so the second run waits for
 * the first to commit and then finds nothing missing. Without the lock both would read the same missing keys and
 * insert each twice ({@code "DictionaryEntry"} has no unique index, by R-CDC1).
 *
 * <p>Deterministic: transaction A takes the lock and holds it; transaction B starts its add-missing step and is
 * seen waiting in {@code pg_locks} through a separate connection; only then does A run its own step and commit.
 * Both run in this one context, each in its own transaction, as two instances' start-ups would.
 */
class DictionaryDefaultsConcurrencyITTest extends ITTest {

    @Autowired
    DictionaryService dictionaryService;

    @Autowired
    DefaultsLedger ledger;

    @PersistenceContext
    EntityManager em;

    @TempDir
    Path classPathRoot;

    private final String bundle = "it-lock-" + System.nanoTime();

    @Test
    void theSecondRunWaitsForTheFirstAndAddsNothing() throws Exception {
        DefaultTexts.Layered layered = layered();
        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch bWaiting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executor.submit(() -> Tx.requiringNew().call(() -> {
                ledger.lockAddMissing();
                aLocked.countDown();
                assertTrue(bWaiting.await(60, TimeUnit.SECONDS), "B never waited on the lock");
                return dictionaryService.addMissingDefaults(layered);
            }));
            assertTrue(aLocked.await(60, TimeUnit.SECONDS), "A never took the lock");

            Future<Integer> b = executor.submit(() -> Tx.requiringNew().call(
                    () -> dictionaryService.addMissingDefaults(layered)));
            awaitWaitingOnTheLock();
            assertFalse(b.isDone(), "B must wait while A holds the lock");
            bWaiting.countDown();

            assertEquals(4, a.get(60, TimeUnit.SECONDS), "A adds every missing default");
            assertEquals(0, b.get(60, TimeUnit.SECONDS), "B, once A has committed, finds nothing missing");
        } finally {
            executor.shutdownNow();
        }

        for (String key : new String[] {"greeting", "farewell"}) {
            assertEquals(1L, count("SELECT count(*) FROM public.\"DictionaryEntry\" e JOIN public.\"Dictionary\" d "
                    + "ON e.dictionary_id = d.id WHERE d.name = ? AND e.dict_key = ?", key), key + ": one entry");
            assertEquals(2L, count("SELECT count(*) FROM public.\"DictionaryEntryDesc\" t JOIN public.\"DictionaryEntry\" e "
                    + "ON t.entity_id = e.id JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                    + "WHERE d.name = ? AND e.dict_key = ?", key), key + ": one description per language");
            assertEquals(2L, count("SELECT count(*) FROM public.\"DefaultsLedger\" WHERE dictionary = ? AND \"key\" = ?",
                    key), key + ": one ledger row per language");
        }
    }

    @AfterEach
    void removeSyntheticDictionary() {
        Tx.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM public.\"DictionaryEntryDesc\" WHERE entity_id IN (SELECT e.id FROM "
                    + "public.\"DictionaryEntry\" e JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                    + "WHERE d.name = :name)").setParameter("name", bundle).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"DictionaryEntry\" WHERE dictionary_id IN (SELECT id FROM "
                    + "public.\"Dictionary\" WHERE name = :name)").setParameter("name", bundle).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"Dictionary\" WHERE name = :name")
                    .setParameter("name", bundle).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE dictionary = :name")
                    .setParameter("name", bundle).executeUpdate();
        });
    }

    // ------------------------------------------------------------------------------------------- //

    /** Polls pg_locks, on a connection of its own, until a session waits for the add-missing lock */
    private void awaitWaitingOnTheLock() throws SQLException, InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        try (Connection c = DriverManager.getConnection(dbJdbcUrl(), dbUsername(), dbPassword());
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                     + "AND classid::bigint = ? AND objid::bigint = ? AND objsubid = 1 AND NOT granted")) {
            ps.setLong(1, DefaultsLedger.ADD_MISSING_LOCK >>> 32);
            ps.setLong(2, DefaultsLedger.ADD_MISSING_LOCK & 0xFFFFFFFFL);
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("No session waited for the add-missing advisory lock within 60 s");
    }

    private long count(String sql, String key) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbJdbcUrl(), dbUsername(), dbPassword());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, bundle);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Two keys in en (the base product's folder) and da (a language pack) */
    private DefaultTexts.Layered layered() throws IOException {
        write("defaults/base/texts/en/" + bundle + ".properties", "greeting = Hello\nfarewell = Bye\n");
        write("defaults/packs/da/" + bundle + ".properties", "greeting = Hej\nfarewell = Farvel\n");
        try (URLClassLoader cl = new URLClassLoader(new URL[] {classPathRoot.toUri().toURL()}, null)) {
            return DefaultTexts.read(cl, new String[] {bundle}, new String[] {"en", "da"}, "en");
        }
    }

    private void write(String name, String content) throws IOException {
        Path file = classPathRoot.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
