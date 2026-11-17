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
package org.niord.core.defaults;

import org.hibernate.Session;
import org.niord.core.NiordBaseVersion;
import org.niord.core.service.BaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The ledger of what start-up added from the defaults, and of what an operator deleted
 * (docs/code-data-config-plan.md §4.2, table {@code "DefaultsLedger"}, changeset V006).
 *
 * <p>A row is a (kind, key, dictionary, language) the system has recorded: a text it added from a layer (source
 * {@code base}, {@code pack:<lang>} or {@code country}) with the sha-256 of the value, a default that
 * was already in the database when the ledger first saw it ({@code adopted}, with the hash only when the row's
 * value equalled the default), or a deleted key ({@code deleted}, language {@value #LANG_DELETED}, no hash).
 * Start-up never adds a triple the ledger holds, so what an operator deleted or cleared stays gone (R-CDC5). A text
 * row with the source {@code root} was written before NG-B by the transitional root-bundle layer and is kept as it
 * is (no migration).
 *
 * <p>A {@code SETTING} row has {@code ''} as its dictionary and language (§5.3): a setting start-up added, with the
 * sha-256 of its value's JSON form and where the value came from ({@code base}, the base product's settings file;
 * {@code country}, a country's; {@code root}, a root-level {@code niord.json} on the class path (niord-base's test tree's, until it
 * moves to {@code defaults/}, §5.2); {@code home},
 * the server's {@code ${niord.home}/niord.json}; {@code code}, the code default), or one already in the database
 * ({@code adopted}, as for texts). Start-up never adds a setting the ledger holds, so a row removed by hand stays
 * removed.
 *
 * <p>The ledger has no JPA entity: it is written by native SQL on the transaction's own connection, in one JDBC
 * batch, so it draws from no sequence and a rolled-back transaction rolls its rows back too.
 */
@Service
public class DefaultsLedger extends BaseService {

    public static final String KIND_TEXT = "TEXT";
    public static final String KIND_SETTING = "SETTING";

    /** The language of a deleted key's row: the key is deleted in every language */
    public static final String LANG_DELETED = "*";

    public static final String SOURCE_ADOPTED = "adopted";
    public static final String SOURCE_DELETED = "deleted";

    /** The sources of an added setting: the settings file it came from, or the code default */
    public static final String SOURCE_BASE = "base";
    public static final String SOURCE_COUNTRY = "country";
    public static final String SOURCE_ROOT = "root";
    public static final String SOURCE_HOME = "home";
    public static final String SOURCE_CODE = "code";

    /**
     * The key of the transaction-level advisory lock the add-missing steps take before they read the database
     * (ASCII {@code NIORDDEF}); shared by the texts and, from C3, the settings
     */
    public static final long ADD_MISSING_LOCK = 0x4E494F5244444546L;

    private static final String INSERT = "INSERT INTO public.\"DefaultsLedger\" "
            + "(kind, \"key\", dictionary, lang, hash, source, \"baseVersion\", created) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) ON CONFLICT DO NOTHING";

    /** A ledger row to write */
    public record Row(String kind, String key, String dictionary, String lang, String hash, String source) {

        /** A text row */
        public static Row text(String dictionary, String key, String lang, String hash, String source) {
            return new Row(KIND_TEXT, key, dictionary, lang, hash, source);
        }

        /** A setting row: no dictionary and no language */
        public static Row setting(String key, String hash, String source) {
            return new Row(KIND_SETTING, key, "", "", hash, source);
        }
    }

    /** Updated atomically, as two transactions can commit rows at the same time */
    private final AtomicReference<Texts> texts = new AtomicReference<>(Texts.EMPTY);

    /**
     * Takes the add-missing advisory lock for the rest of the caller's transaction. A second start-up on the same
     * database waits here until the first has committed, and then reads what the first added.
     */
    public void lockAddMissing() {
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                ps.setLong(1, ADD_MISSING_LOCK);
                ps.execute();
            }
        });
    }

    /**
     * Reads every TEXT row into a new snapshot, which also becomes the one {@link #texts()} returns.
     * Called after {@link #lockAddMissing()}.
     *
     * @return the snapshot
     */
    public Texts loadTexts() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT dictionary, \"key\", lang, hash FROM public.\"DefaultsLedger\" WHERE kind = :kind")
                .setParameter("kind", KIND_TEXT)
                .getResultList();
        Texts loaded = Texts.EMPTY.with(rows.stream()
                .map(r -> Row.text((String) r[0], (String) r[1], (String) r[2], (String) r[3], null))
                .toList());
        texts.set(loaded);
        return loaded;
    }

    /**
     * Reads the keys of every SETTING row, from the database and never from a cache. Called after
     * {@link #lockAddMissing()}.
     *
     * @return the keys
     */
    public Set<String> loadSettingKeys() {
        @SuppressWarnings("unchecked")
        List<String> keys = em.createNativeQuery("SELECT \"key\" FROM public.\"DefaultsLedger\" WHERE kind = :kind")
                .setParameter("kind", KIND_SETTING)
                .getResultList();
        return new HashSet<>(keys);
    }

    /**
     * Returns the last snapshot of the TEXT rows, including what committed transactions have written since. The
     * snapshot is this node's: it is read at start-up and then grows only with this node's own commits, so a key
     * deleted on another node reaches it only at its next start. It serves the add-missing step, which reads it under
     * the lock after {@link #loadTexts()}, and the {@code asAdded} marks; the import reads the deleted keys from the
     * database ({@link #deletedKeys}).
     */
    public Texts texts() {
        return texts.get();
    }

    /**
     * Reads the keys an operator deleted from the dictionary (its {@code *} rows), from the database and never from
     * the snapshot, so that every node's import sees a delete made on any node
     *
     * @param dictionary the dictionary name
     * @return the deleted keys
     */
    public Set<String> deletedKeys(String dictionary) {
        @SuppressWarnings("unchecked")
        List<String> keys = em.createNativeQuery("SELECT \"key\" FROM public.\"DefaultsLedger\" "
                        + "WHERE kind = :kind AND dictionary = :dictionary AND lang = :lang")
                .setParameter("kind", KIND_TEXT)
                .setParameter("dictionary", dictionary)
                .setParameter("lang", LANG_DELETED)
                .getResultList();
        return new HashSet<>(keys);
    }

    /**
     * Writes the rows in one JDBC batch on the transaction's connection; a row the ledger already holds is skipped.
     *
     * @param rows the rows
     * @return the number of rows written
     */
    public int insertAll(List<Row> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        String baseVersion = NiordBaseVersion.get();
        int[] written = {0};
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                for (Row row : rows) {
                    ps.setString(1, row.kind());
                    ps.setString(2, row.key());
                    ps.setString(3, row.dictionary());
                    ps.setString(4, row.lang());
                    ps.setString(5, row.hash());
                    ps.setString(6, row.source());
                    ps.setString(7, baseVersion);
                    ps.addBatch();
                }
                for (int count : ps.executeBatch()) {
                    written[0] += Math.max(count, 0);
                }
            }
        });
        List<Row> textRows = rows.stream().filter(r -> KIND_TEXT.equals(r.kind())).toList();
        afterCommit(() -> texts.updateAndGet(t -> t.with(textRows)));
        return written[0];
    }

    /**
     * Records that the key was deleted from the dictionary, in every language. Written in the caller's
     * transaction, so a delete that rolls back leaves no row.
     *
     * @param dictionary the dictionary name
     * @param key        the key
     */
    public void recordDeleted(String dictionary, String key) {
        insertAll(List.of(Row.text(dictionary, key, LANG_DELETED, null, SOURCE_DELETED)));
    }

    /** Runs the action once the current transaction has committed, or at once outside a transaction */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** An immutable snapshot of the ledger's TEXT rows */
    public static final class Texts {

        static final Texts EMPTY = new Texts(Set.of(), Set.of(), Map.of(), Map.of());

        private final Set<String> triples;
        private final Set<String> deleted;
        private final Map<String, String> hashes;
        private final Map<String, Set<String>> keysByDictionary;

        private Texts(Set<String> triples, Set<String> deleted, Map<String, String> hashes,
                      Map<String, Set<String>> keysByDictionary) {
            this.triples = triples;
            this.deleted = deleted;
            this.hashes = hashes;
            this.keysByDictionary = keysByDictionary;
        }

        /** Returns a copy with the rows added */
        Texts with(Collection<Row> rows) {
            Set<String> newTriples = new HashSet<>(triples);
            Set<String> newDeleted = new HashSet<>(deleted);
            Map<String, String> newHashes = new HashMap<>(hashes);
            Map<String, Set<String>> newKeys = new HashMap<>();
            keysByDictionary.forEach((dict, keys) -> newKeys.put(dict, new HashSet<>(keys)));
            for (Row row : rows) {
                if (LANG_DELETED.equals(row.lang())) {
                    newDeleted.add(id(row.dictionary(), row.key()));
                    continue;
                }
                String triple = id(row.dictionary(), row.key(), row.lang());
                if (newTriples.add(triple) && row.hash() != null) {
                    newHashes.put(triple, row.hash());
                }
                newKeys.computeIfAbsent(row.dictionary(), d -> new HashSet<>()).add(row.key());
            }
            return new Texts(newTriples, newDeleted, newHashes, newKeys);
        }

        /** Returns whether the ledger holds the triple */
        public boolean has(String dictionary, String key, String lang) {
            return triples.contains(id(dictionary, key, lang));
        }

        /** Returns whether the key was deleted from the dictionary (its {@code *} row) */
        public boolean deleted(String dictionary, String key) {
            return deleted.contains(id(dictionary, key));
        }

        /** Returns whether the ledger holds the key in any language */
        public boolean hasKey(String dictionary, String key) {
            return keysByDictionary.getOrDefault(dictionary, Set.of()).contains(key);
        }

        /** Returns the hash recorded for the triple, or null */
        public String hash(String dictionary, String key, String lang) {
            return hashes.get(id(dictionary, key, lang));
        }

        /** Returns the keys the ledger holds for the dictionary in any language */
        public Set<String> keys(String dictionary) {
            return Collections.unmodifiableSet(keysByDictionary.getOrDefault(dictionary, Set.of()));
        }

        private static String id(String... parts) {
            return String.join("\0", parts);
        }
    }
}
