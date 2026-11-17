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
package org.niord.core.bootstrap;

import org.niord.core.NiordBaseVersion;
import org.niord.core.defaults.DefaultsLedger;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.service.BaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The bootstrap marker (docs/code-data-config-plan.md §7.2, D4): the one row of the table {@code "BootstrapState"}
 * (changeset V007), which says whether this database is still to be bootstrapped.
 *
 * <p>At the first start of a version that has the table, the row is written once: {@code pending} when the database
 * holds no domain, message or area, {@code existing} otherwise. It is never decided again, so every running
 * deployment and every seeded database reads {@code existing} from then on.
 *
 * <p>A bootstrap runs once, on a new, empty database. Its claim ({@link #claim}) locks the row and, in that one
 * transaction, checks both guards: the marker reads {@code pending}, and the database holds no data
 * ({@link #firstDataTable}: no row in a table of schema {@code public} apart from those a first start writes by
 * itself). Only then does it move the marker to {@code in-progress}; the runner moves it on to {@code applied} or
 * {@code failed}. {@code failed} and {@code in-progress} are final: a bootstrap is never run again on the same
 * database, nor taken over, and the remedy is a database recreated empty.
 *
 * <p>A live run touches {@code updated} every {@link #HEARTBEAT}, also while it waits for a job past its deadline to
 * end. A run that broke off (the backend stopped, an {@code Error}, a database outage when the run wrote
 * {@code failed}) leaves {@code in-progress} with an {@code updated} that no longer moves; once that is older than
 * {@link #STALE_AFTER}, the refusal says the run broke off rather than that it is running.
 *
 * <p>Native SQL like {@link DefaultsLedger}, with no JPA entity.
 */
@Service
public class BootstrapMarker extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapMarker.class);

    public static final String PENDING = "pending";
    public static final String EXISTING = "existing";
    public static final String IN_PROGRESS = "in-progress";
    public static final String APPLIED = "applied";
    public static final String FAILED = "failed";

    /** The longest error text the row keeps */
    private static final int MAX_ERROR = 4000;

    /** How often a live run touches the marker */
    public static final Duration HEARTBEAT = Duration.ofSeconds(30);

    /**
     * How long an {@code in-progress} marker may show no sign of life before a refusal calls the run one that broke
     * off, not one that is running
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /**
     * The tables of schema {@code public} whose rows a first start writes by itself, so that they never make a
     * database hold data: the marker, the defaults ledger, the settings, Liquibase's two tables, the users, a row
     * for each user mirrored from Keycloak at a first authenticated request, and the queued mails and their
     * recipients, which a scheduled alert may write before the bootstrap is posted
     * ({@code SecomCertificateAlertService.checkAndAlert}, {@code SecomStoreWatchService.watch}). The default texts'
     * dictionaries ({@link DictionaryService#DEFAULT_BUNDLES}) are counted apart, by dictionary
     * ({@link #firstDataTable}), and the tables extensions own (PostGIS's {@code spatial_ref_sys}) are never probed.
     */
    public static final List<String> NOT_DATA = List.of("BootstrapState", "DefaultsLedger", "Setting",
            "databasechangelog", "databasechangeloglock", "User", "ScheduledMail", "ScheduledMailRecipient");

    /** The tables that hold the dictionaries, probed only for rows outside the default texts' dictionaries */
    private static final List<String> DICTIONARY_TABLES = List.of("Dictionary", "DictionaryEntry",
            "DictionaryEntryDesc");

    /** The marker row as read */
    public record State(String state, String hash, String error, String baseVersion, OffsetDateTime updated) {
    }

    /**
     * What a claim found: whether it claimed the marker, the state it read (null when no row is written yet), the
     * row's error, whether an {@code in-progress} row has shown no sign of life for {@link #STALE_AFTER}, and, for a
     * {@code pending} row over a database that holds data, the first table that holds a row
     */
    public record Claim(boolean claimed, String state, String error, boolean stale, String dataTable) {
    }

    private final DefaultsLedger ledger;

    BootstrapMarker(DefaultsLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * Writes the marker row at the first start of a version that has it: {@code pending} for a database with no
     * domain, message or area, {@code existing} otherwise. A row already there is left as it is. Runs right after
     * the settings' add-missing and re-injection, before the other start-up listeners, under the add-missing
     * advisory lock, so two nodes starting together decide once.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    @Transactional
    public void init() {
        ledger.lockAddMissing();
        // EXISTS, never count(*): "Message" is large on a running deployment
        int written = em.createNativeQuery("INSERT INTO public.\"BootstrapState\" "
                        + "(id, state, \"baseVersion\", updated) "
                        + "SELECT 1, CASE WHEN EXISTS (SELECT 1 FROM public.\"Domain\") "
                        + "OR EXISTS (SELECT 1 FROM public.\"Message\") "
                        + "OR EXISTS (SELECT 1 FROM public.\"Area\") "
                        + "THEN '" + EXISTING + "' ELSE '" + PENDING + "' END, :version, now() "
                        + "ON CONFLICT DO NOTHING")
                .setParameter("version", NiordBaseVersion.get())
                .executeUpdate();
        if (written > 0) {
            read().ifPresent(s -> log.info("Bootstrap marker written: " + s.state()));
        }
    }

    /**
     * Reads the marker row
     *
     * @return the row, or empty before the first start has written it
     */
    @Transactional(readOnly = true)
    public Optional<State> read() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT state, hash, error, \"baseVersion\", updated "
                        + "FROM public.\"BootstrapState\" WHERE id = 1")
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.get(0);
        return Optional.of(new State((String) r[0], (String) r[1], (String) r[2], (String) r[3], toOffset(r[4])));
    }

    /**
     * Claims the marker for a run, in a transaction of its own ({@code REQUIRES_NEW}) that commits at once, before the
     * first job starts. It locks the row ({@code FOR UPDATE}), and only when the row reads {@code pending} and the
     * database holds no data ({@link #firstDataTable}, read in the same transaction) does it move the marker to
     * {@code in-progress} with the run's hash. A second caller waits for the lock and then reads {@code in-progress}.
     * Nothing is written when it is not claimed.
     *
     * @param hash the run's hash
     * @return what the claim found, and whether it claimed the marker
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String hash) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT state, error, updated < now() - interval '"
                        + STALE_AFTER.toSeconds() + " seconds' FROM public.\"BootstrapState\" WHERE id = 1 FOR UPDATE")
                .getResultList();
        if (rows.isEmpty()) {
            return new Claim(false, null, null, false, null);
        }
        String state = (String) rows.get(0)[0];
        String error = (String) rows.get(0)[1];
        boolean stale = Boolean.TRUE.equals(rows.get(0)[2]);
        if (!PENDING.equals(state)) {
            return new Claim(false, state, error, stale, null);
        }
        // Within the claim's transaction and lock: a direct call, not through the proxy
        Optional<String> table = firstDataTable();
        if (table.isPresent()) {
            return new Claim(false, state, error, stale, table.get());
        }
        boolean claimed = em.createNativeQuery("UPDATE public.\"BootstrapState\" SET state = '" + IN_PROGRESS + "', "
                        + "hash = :hash, error = NULL, \"baseVersion\" = :version, updated = now() "
                        + "WHERE id = 1 AND state = '" + PENDING + "'")
                .setParameter("hash", hash)
                .setParameter("version", NiordBaseVersion.get())
                .executeUpdate() > 0;
        return new Claim(claimed, claimed ? IN_PROGRESS : state, null, false, null);
    }

    /**
     * Returns the tables the claim probes for data, in the order it probes them: every table of schema
     * {@code public} (plain or partitioned) that no extension owns and that is not on the {@link #NOT_DATA} list,
     * sorted by name in the catalog's own order (byte order, so {@code BatchData} comes before {@code Sequence})
     *
     * @return the tables' names
     */
    @Transactional(readOnly = true)
    public List<String> dataTables() {
        @SuppressWarnings("unchecked")
        List<String> tables = em.createNativeQuery("SELECT c.relname::text FROM pg_catalog.pg_class c "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') "
                        + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d "
                        + "WHERE d.classid = 'pg_catalog.pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e') "
                        + "AND c.relname::text NOT IN (:notData) "
                        + "ORDER BY c.relname")
                .setParameter("notData", NOT_DATA)
                .getResultList();
        return tables;
    }

    /**
     * Returns the first table, in the order of {@link #dataTables}, that holds a row: what makes the database one that
     * holds data, which the claim refuses. The dictionaries' tables count only for a row outside the default texts'
     * dictionaries ({@link DictionaryService#DEFAULT_BUNDLES}), which a first start writes by itself.
     *
     * @return the table, or empty for a database that holds no data
     */
    @Transactional(readOnly = true)
    public Optional<String> firstDataTable() {
        List<String> defaults = Arrays.asList(DictionaryService.DEFAULT_BUNDLES);
        for (String table : dataTables()) {
            var probe = switch (table) {
                case "Dictionary" -> em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM public.\"Dictionary\" "
                        + "WHERE name NOT IN (:defaults))");
                case "DictionaryEntry" -> em.createNativeQuery("SELECT EXISTS (SELECT 1 "
                        + "FROM public.\"DictionaryEntry\" e JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                        + "WHERE d.name NOT IN (:defaults))");
                case "DictionaryEntryDesc" -> em.createNativeQuery("SELECT EXISTS (SELECT 1 "
                        + "FROM public.\"DictionaryEntryDesc\" x JOIN public.\"DictionaryEntry\" e "
                        + "ON x.entity_id = e.id JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                        + "WHERE d.name NOT IN (:defaults))");
                default -> em.createNativeQuery("SELECT EXISTS (SELECT 1 FROM public." + quoted(table) + ")");
            };
            if (DICTIONARY_TABLES.contains(table)) {
                probe.setParameter("defaults", defaults);
            }
            if (Boolean.TRUE.equals(probe.getSingleResult())) {
                return Optional.of(table);
            }
        }
        return Optional.empty();
    }

    /** A table's name as an SQL identifier, quoted */
    private static String quoted(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * Touches the marker of a live run, so that a refusal does not call it one that broke off
     *
     * @param hash the run's hash
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(String hash) {
        em.createNativeQuery("UPDATE public.\"BootstrapState\" SET updated = now() "
                        + "WHERE id = 1 AND state = '" + IN_PROGRESS + "' AND hash = :hash")
                .setParameter("hash", hash)
                .executeUpdate();
    }

    /**
     * Touches the marker of a live run and writes why it is still {@code in-progress}, which {@code GET} shows
     *
     * @param hash the run's hash
     * @param text what the run waits for
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void note(String hash, String text) {
        em.createNativeQuery("UPDATE public.\"BootstrapState\" SET updated = now(), error = :text "
                        + "WHERE id = 1 AND state = '" + IN_PROGRESS + "' AND hash = :hash")
                .setParameter("hash", hash)
                .setParameter("text", truncate(text))
                .executeUpdate();
    }

    /** Moves the marker from {@code in-progress} to {@code applied} */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markApplied() {
        finish(APPLIED, null);
    }

    /**
     * Moves the marker from {@code in-progress} to {@code failed}
     *
     * @param error the job, its file and its status, or what else stopped the run
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String error) {
        finish(FAILED, truncate(error));
    }

    private static String truncate(String text) {
        return text != null && text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
    }

    private void finish(String state, String error) {
        // No null parameter: PostgreSQL cannot always type one, so a missing error is written as a literal NULL
        var query = em.createNativeQuery("UPDATE public.\"BootstrapState\" SET state = :state, error = "
                        + (error == null ? "NULL" : ":error")
                        + ", updated = now() WHERE id = 1 AND state = '" + IN_PROGRESS + "'")
                .setParameter("state", state);
        if (error != null) {
            query.setParameter("error", error);
        }
        int updated = query.executeUpdate();
        if (updated == 0) {
            log.warn("The bootstrap marker was not in-progress when the run ended (" + state + ")");
        }
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(java.time.ZoneOffset.UTC);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return null;
    }
}
