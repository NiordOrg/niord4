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
import org.niord.core.area.AreaService;
import org.niord.core.db.JpaJsonAttributeConverter;
import org.niord.core.defaults.DefaultTexts;
import org.niord.core.defaults.DefaultsLedger;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings add-missing step ({@link SettingsService#addMissing()}, docs/code-data-config-plan.md §5.3, R-CDC5):
 * a catalog operator key the database lacks is added with the catalog's flags and recorded in the ledger with where
 * its value came from; a present key is adopted; a key whose row was removed is not added again; a state key never;
 * and two start-ups on one database run one after the other.
 *
 * <p>Every step commits, as start-up does. Each test snapshots the rows and ledger rows of the keys it touches and
 * {@link #restore()} puts them back. The keys: {@code mapTileContact}, which the test tree's root {@code niord.json}
 * sets to {@code ""} (source {@code root}); {@code documentationUrl}, whose seeded row equals that file's value; and
 * {@code mailSender}, a seeded row.
 */
class SettingsAddMissingITTest extends ITTest {

    private static final String CONTACT = "mapTileContact";
    private static final String DOCS = "documentationUrl";
    private static final String SENDER = "mailSender";

    @Autowired
    SettingsService settingsService;

    @Autowired
    DefaultsLedger ledger;

    @PersistenceContext
    EntityManager em;

    /** The touched keys' rows and ledger rows as they were, by key */
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();

    private record Snapshot(Setting row, List<Object[]> ledgerRows) {
    }

    @Test
    void aMissingKeyIsAddedWithTheCatalogsFlagsAndRecordedWithItsSource() throws SQLException {
        removeRow(CONTACT);
        removeLedgerRow(CONTACT);

        assertTrue(addMissing() >= 1, "the missing key is added");

        Setting added = row(CONTACT);
        assertNotNull(added, "added: the database lacked it and the ledger had not seen it");
        SettingsCatalog.Entry entry = settingsService.catalogEntry(CONTACT);
        assertEquals("", added.getValue(), "the settings file's value");
        assertEquals(entry.type(), added.getType());
        assertEquals(entry.description(), added.getDescription());
        assertEquals(entry.cached(), added.isCached());
        assertEquals(entry.web(), added.isWeb());
        assertEquals(entry.editable(), added.isEditable());
        assertEquals(List.of(DefaultsLedger.SOURCE_ROOT, hash("")), ledgerRow(CONTACT),
                "recorded with the test tree's root niord.json as the source, and the value's hash");

        assertEquals(0, addMissing(), "a second run adds nothing");
    }

    @Test
    void aPresentKeyIsAdoptedWithItsHashOnlyWhenItEqualsTheDefault() throws SQLException {
        snapshot(DOCS);
        snapshot(SENDER);
        removeLedgerRow(DOCS);
        removeLedgerRow(SENDER);
        Tx.requiringNew().run(() -> em.find(Setting.class, SENDER).setValue("it-operator@example.org"));
        settingsService.evictFromCache(SENDER);
        long before = settingCount();

        assertEquals(0, addMissing(), "adoption adds no row");

        assertEquals(before, settingCount());
        assertEquals(List.of(DefaultsLedger.SOURCE_ADOPTED, hash(settingsService.resolveDefault(new Setting(DOCS)))),
                ledgerRow(DOCS), "equal to the default: adopted with the hash");
        assertEquals(java.util.Arrays.asList(DefaultsLedger.SOURCE_ADOPTED, null), ledgerRow(SENDER),
                "an operator's value: adopted without a hash");
        assertEquals("it-operator@example.org", row(SENDER).getValue(), "adoption never changes a row");
    }

    /**
     * A row a database holds before its key is declared, made by hand, is adopted as it is: start-up never changes
     * its value, type or flags. One whose {@code web} flag is false is therefore still not served at
     * {@code /rest/settings/web}; to serve such a row, give it the catalog's flags, or delete it before the first
     * start on the version that declares the key, so start-up adds it. Whether the Settings page lists it is the
     * catalog's answer, not the row's {@code editable} flag, so a key the catalog makes editable is listed.
     */
    @Test
    void aRowMadeByHandIsAdoptedWithItsOwnFlags() throws SQLException {
        String key = FrontendSettings.WMS_LAYER_ENABLED_BY_DEFAULT.getKey();
        removeLedgerRow(key);
        Tx.requiringNew().run(() -> {
            Setting row = em.find(Setting.class, key);
            row.setValue(true);
            row.setWeb(false);
            row.setEditable(false);
        });
        settingsService.evictFromCache(key);

        assertEquals(0, addMissing(), "adoption adds no row");

        Setting adopted = row(key);
        assertEquals(Boolean.TRUE, adopted.getValue(), "the value made by hand");
        assertFalse(adopted.isWeb(), "its own web flag, not the catalog's");
        assertFalse(adopted.isEditable(), "its own editable flag, not the catalog's");
        assertEquals(java.util.Arrays.asList(DefaultsLedger.SOURCE_ADOPTED, null), ledgerRow(key),
                "recorded as adopted, without a hash: its value is not the default");
        assertFalse(Tx.requiringNew().call(() -> settingsService.getAllForWeb()).stream()
                .anyMatch(s -> s.getKey().equals(key)), "not served to the web applications");
        assertTrue(Tx.requiringNew().call(() -> settingsService.getAllEditable()).stream()
                .anyMatch(s -> s.getKey().equals(key)), "on the Settings page: the catalog makes it editable");
    }

    @Test
    void aKeyWhoseRowWasRemovedIsNotAddedAgain() throws SQLException {
        removeRow(CONTACT);
        assertNotNull(ledgerRow(CONTACT), "precondition: the ledger has the key");

        assertEquals(0, addMissing());

        assertNull(row(CONTACT), "a row removed by hand stays removed");
    }

    @Test
    void aStateKeyIsNeverAdded() throws SQLException {
        String key = AreaService.AREA_LAST_UPDATE.getKey();
        removeRow(key);
        removeLedgerRow(key);

        addMissing();

        assertNull(row(key), "only the system writes a state setting, when it has a value");
        assertNull(ledgerRow(key), "a state key is not recorded");
    }

    /**
     * Two start-ups on one database, as {@code DictionaryDefaultsConcurrencyITTest} does for texts: transaction A
     * takes the lock and holds it; B starts its add-missing step and is seen waiting in {@code pg_locks}; only then
     * does A add and commit, and B finds nothing missing.
     */
    @Test
    void theSecondRunWaitsForTheFirstAndAddsNothing() throws Exception {
        removeRow(CONTACT);
        removeLedgerRow(CONTACT);
        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch bWaiting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executor.submit(() -> Tx.requiringNew().call(() -> {
                ledger.lockAddMissing();
                aLocked.countDown();
                assertTrue(bWaiting.await(60, TimeUnit.SECONDS), "B never waited on the lock");
                return settingsService.addMissing();
            }));
            assertTrue(aLocked.await(60, TimeUnit.SECONDS), "A never took the lock");

            Future<Integer> b = executor.submit(() -> Tx.requiringNew().call(() -> settingsService.addMissing()));
            awaitWaitingOnTheLock();
            assertFalse(b.isDone(), "B must wait while A holds the lock");
            bWaiting.countDown();

            assertTrue(a.get(60, TimeUnit.SECONDS) >= 1, "A adds the missing key");
            assertEquals(0, b.get(60, TimeUnit.SECONDS), "B, once A has committed, finds nothing missing");
        } finally {
            executor.shutdownNow();
        }
        assertNotNull(row(CONTACT));
        assertEquals(1L, count("SELECT count(*) FROM public.\"DefaultsLedger\" WHERE kind = 'SETTING' AND \"key\" = ?",
                CONTACT), "one ledger row");
    }

    @AfterEach
    void restore() {
        snapshots.forEach((key, snapshot) -> {
            Tx.requiringNew().run(() -> {
                em.createNativeQuery("DELETE FROM public.\"Setting\" WHERE setting_key = :key")
                        .setParameter("key", key).executeUpdate();
                em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE kind = 'SETTING' AND \"key\" = :key")
                        .setParameter("key", key).executeUpdate();
                if (snapshot.row() != null) {
                    em.persist(snapshot.row());
                }
                for (Object[] r : snapshot.ledgerRows()) {
                    em.createNativeQuery("INSERT INTO public.\"DefaultsLedger\" (kind, \"key\", dictionary, lang, hash, "
                                    + "source, \"baseVersion\", created) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)")
                            .setParameter(1, r[0]).setParameter(2, r[1]).setParameter(3, r[2]).setParameter(4, r[3])
                            .setParameter(5, r[4]).setParameter(6, r[5]).setParameter(7, r[6]).setParameter(8, r[7])
                            .executeUpdate();
                }
            });
            settingsService.evictFromCache(key);
        });
    }

    // ------------------------------------------------------------------------------------------- //

    private int addMissing() {
        return Tx.requiringNew().call(() -> settingsService.addMissing());
    }

    /** Records the key's row and ledger rows, once, before a test changes them */
    @SuppressWarnings("unchecked")
    private void snapshot(String key) {
        if (snapshots.containsKey(key)) {
            return;
        }
        snapshots.put(key, Tx.requiringNew().call(() -> {
            Setting row = em.find(Setting.class, key);
            List<Object[]> ledgerRows = new ArrayList<>(em.createNativeQuery("SELECT kind, \"key\", dictionary, lang, "
                            + "hash, source, \"baseVersion\", created FROM public.\"DefaultsLedger\" "
                            + "WHERE kind = 'SETTING' AND \"key\" = :key")
                    .setParameter("key", key)
                    .getResultList());
            return new Snapshot(row == null ? null : new Setting(row), ledgerRows);
        }));
    }

    private void removeRow(String key) {
        snapshot(key);
        Tx.requiringNew().run(() -> em.createNativeQuery("DELETE FROM public.\"Setting\" WHERE setting_key = :key")
                .setParameter("key", key).executeUpdate());
        settingsService.evictFromCache(key);
    }

    private void removeLedgerRow(String key) {
        snapshot(key);
        Tx.requiringNew().run(() -> em.createNativeQuery(
                        "DELETE FROM public.\"DefaultsLedger\" WHERE kind = 'SETTING' AND \"key\" = :key")
                .setParameter("key", key).executeUpdate());
    }

    private Setting row(String key) {
        return Tx.requiringNew().call(() -> em.find(Setting.class, key));
    }

    private long settingCount() {
        return Tx.requiringNew().call(() -> em.createQuery("select count(s) from Setting s", Long.class)
                .getSingleResult());
    }

    /** The key's SETTING ledger row as (source, hash), or null */
    private List<String> ledgerRow(String key) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbJdbcUrl(), dbUsername(), dbPassword());
             PreparedStatement ps = c.prepareStatement("SELECT source, hash, dictionary, lang FROM public.\"DefaultsLedger\" "
                     + "WHERE kind = 'SETTING' AND \"key\" = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                assertEquals("", rs.getString(3), "a setting row has no dictionary");
                assertEquals("", rs.getString(4), "a setting row has no language");
                return java.util.Arrays.asList(rs.getString(1), rs.getString(2));
            }
        }
    }

    private long count(String sql, String key) throws SQLException {
        try (Connection c = DriverManager.getConnection(dbJdbcUrl(), dbUsername(), dbPassword());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

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

    private static String hash(Object value) {
        return DefaultTexts.sha256(String.valueOf(new JpaJsonAttributeConverter().convertToDatabaseColumn(value)));
    }
}
