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

import org.niord.core.batch.BatchData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one live bootstrap run, and what each of its archives created (docs/code-data-config-plan.md §7.2, C6, D4 as
 * amended on 2026-10-24).
 *
 * <p>A bootstrap is the one first-time deployment of a new environment: one run that applies the base product's
 * archive first and then the country's, and inside it the later archive takes precedence. For every entry it names
 * that an earlier archive of the same run created, each value it gives replaces the earlier one's (a template, a
 * name, a description in a language, a priority, a flag, a template parameter's settings, a list), and a value the
 * earlier archive left empty is filled. A value the later archive leaves out keeps the earlier one's; "gives" means
 * the field is in the file and is not null or empty ({@link Given}), so a bootstrap never clears a value. What is new
 * (an entry, a language, a template parameter, a domain link) is added, and nothing is removed. For a domain, the
 * later archive may give its active flag only ({@link BootstrapRunner} refuses any other restatement before the
 * claim). Rows from before the run do not occur: the claim refuses a database that holds data
 * ({@link BootstrapMarker#claim}), so the only such rows are start-up's own settings and default texts, which keep
 * start-up's rules. Outside the run, which is every sysadmin's import, a release folder's included, nothing changes.
 *
 * <p>The runner begins the run ({@link #begin()}), gives each job the job property {@value #PROPERTY} with the value
 * {@code <run id>/<archive index>}, and ends it ({@link #end(String)}) whatever the outcome. An import handler reads
 * its {@link Scope} from its job ({@link #scopeOf(BatchData)}), records each entry it creates
 * ({@link Scope#created}) and asks whether an entry it finds was created by an earlier archive of the run
 * ({@link Scope#createdEarlier}). The run id is a fresh random value held in memory. The runner writes it into its
 * own jobs' {@code BatchData} properties, which the batch instances endpoint leaves out
 * ({@code BatchService.getJobInstances}), and an upload or a batch set that carries the property is refused, as an
 * archive item is ({@code AbstractBatchableRestService}, {@code BatchSetService}, {@link BootstrapArchive}). So a job
 * that did not come from the live run (a sysadmin's import, a job of a run that has ended) gets {@link Scope#NONE}.
 * The record is per run, and a run happens once on a database: a failed one is never run again on it, and a
 * bootstrap job is never restarted ({@code BatchService.restartExecution}).
 *
 * <p>An import that replaces or adds to an entry an earlier archive of the run created writes what it added,
 * replaced and kept to its job log, a line per entry ({@link Additions}).
 */
@Component
public class BootstrapRun {

    /** The job property the runner gives every job of a run: {@code <run id>/<archive index>} */
    public static final String PROPERTY = "bootstrapRun";

    /** The kinds of entry an archive's import creates, each named by its import's own key */
    public enum Kind {
        /** a script resource, by its path in lower case, as {@code ScriptResourceService.findByPath} matches */
        TEMPLATE,
        /** a dictionary entry, by the dictionary's name and the key ({@link #dictionaryKey}) */
        DICTIONARY_ENTRY,
        /** a category, by its row id */
        CATEGORY,
        /** a parameter type, by its name */
        PARAM_TYPE,
        /** a promulgation type, by its typeId */
        PROMULGATION_TYPE,
        /** a report, by its reportId */
        REPORT,
        /** a domain, by its domainId; only its active flag is replaced */
        DOMAIN
    }

    private volatile String live;

    /** Each entry the live run created, with the index of the archive that created it first */
    private final Map<String, Integer> created = new ConcurrentHashMap<>();

    /**
     * Begins a run: a fresh run id, and nothing created yet
     *
     * @return the run id
     */
    public synchronized String begin() {
        created.clear();
        live = UUID.randomUUID().toString();
        return live;
    }

    /**
     * Ends the run, when it is the live one: its jobs' scopes answer nothing from now on
     *
     * @param run the run id {@link #begin()} returned
     */
    public synchronized void end(String run) {
        if (run != null && run.equals(live)) {
            live = null;
            created.clear();
        }
    }

    /**
     * Returns the value of {@value #PROPERTY} for a job of an archive of a run
     *
     * @param run     the run id
     * @param archive the archive's index in the run, from 0
     * @return the property's value
     */
    public static String token(String run, int archive) {
        return run + "/" + archive;
    }

    /**
     * Returns the key of a dictionary entry for {@link Kind#DICTIONARY_ENTRY}: the dictionary's name and the key,
     * separated by {@code '\0'}, as the dictionary service's own lookup keys are. The import refuses that character
     * in a key ({@code DictionaryService.importRefusal}), so no two pairs share a key.
     *
     * @param dictionary the dictionary's name
     * @param key        the entry's key
     * @return the key
     */
    public static String dictionaryKey(String dictionary, String key) {
        return dictionary + '\0' + key;
    }

    /**
     * Returns the scope of a job: the live run and the job's archive when the job's {@value #PROPERTY} names the live
     * run, else {@link Scope#NONE}
     *
     * @param job the job, or null (a handler driven outside the batch runtime, as a test drives it)
     * @return the scope
     */
    public Scope scopeOf(BatchData job) {
        String run = live;
        Object token = job == null || job.getProperties() == null ? null : job.getProperties().get(PROPERTY);
        if (run == null || token == null) {
            return Scope.NONE;
        }
        String value = String.valueOf(token);
        int slash = value.lastIndexOf('/');
        if (slash <= 0 || !run.equals(value.substring(0, slash))) {
            return Scope.NONE;
        }
        try {
            int archive = Integer.parseInt(value.substring(slash + 1));
            return archive >= 0 ? new Scope(this, run, archive) : Scope.NONE;
        } catch (NumberFormatException e) {
            return Scope.NONE;
        }
    }

    /**
     * Returns the scope of a job, {@link Scope#NONE} when there is no registry: a handler built by hand, as a test
     * builds one, has none injected
     *
     * @param run the registry, or null
     * @param job the job, or null
     * @return the scope
     */
    public static Scope scopeOf(BootstrapRun run, BatchData job) {
        return run == null ? Scope.NONE : run.scopeOf(job);
    }

    private static String entry(Kind kind, String key) {
        return kind + ":" + (kind == Kind.TEMPLATE ? key.toLowerCase(Locale.ROOT) : key);
    }

    /** The scope of one job: the run and the index of the job's archive in it, or {@link #NONE} */
    public static final class Scope {

        /** Outside a run: records nothing and answers false */
        public static final Scope NONE = new Scope(null, null, -1);

        private final BootstrapRun registry;
        private final String runId;
        private final int archive;

        private Scope(BootstrapRun registry, String runId, int archive) {
            this.registry = registry;
            this.runId = runId;
            this.archive = archive;
        }

        /** Returns the run id, or null outside a run */
        public String runId() {
            return runId;
        }

        /** Returns the index of the job's archive in the run, or -1 outside a run */
        public int archive() {
            return archive;
        }

        /** Returns whether this is a job of the live run */
        private boolean live() {
            return registry != null && runId.equals(registry.live);
        }

        /**
         * Records that the job's archive created the entry. An entry already recorded keeps the archive that
         * created it first.
         *
         * @param kind the kind of entry
         * @param key  its key
         */
        public void created(Kind kind, String key) {
            if (key != null && live()) {
                registry.created.putIfAbsent(entry(kind, key), archive);
            }
        }

        /**
         * Returns whether an earlier archive of the live run created the entry: one with a lower index than the
         * job's archive
         *
         * @param kind the kind of entry
         * @param key  its key
         * @return whether an earlier archive of the run created it
         */
        public boolean createdEarlier(Kind kind, String key) {
            if (key == null || !live()) {
                return false;
            }
            Integer by = registry.created.get(entry(kind, key));
            return by != null && by < archive;
        }

        /**
         * Returns the index of the archive of the live run that created the entry first, or -1
         *
         * @param kind the kind of entry
         * @param key  its key
         * @return the archive's index, or -1 when no archive of the live run created it
         */
        public int createdBy(Kind kind, String key) {
            Integer by = key == null || !live() ? null : registry.created.get(entry(kind, key));
            return by == null ? -1 : by;
        }

        @Override
        public String toString() {
            return runId == null ? "no bootstrap run" : "bootstrap run " + runId + ", archive " + archive;
        }
    }

    /**
     * What an import did to one entry an earlier archive of the run created, as its job log says it
     * (docs/code-data-config-plan.md §7.2): {@code added}, what is new (a language, a template parameter, a domain
     * link); {@code replaced}, each value the later archive gave that differed from the row's, as
     * {@code <field> <old> → <new>} ({@link #change}); {@code kept}, what the later archive gives and cannot change (a
     * matching key, a domain no row carries); {@code notes}, what else happened to the entry (made active by a new
     * child). A value the later archive leaves out, or gives equal to the row's, is not listed.
     *
     * @param entry    what the entry is, e.g. {@code Parameter type it_composite}
     * @param added    what was added
     * @param replaced what was replaced, the old value and the new
     * @param kept     what the later archive gives and was kept
     * @param notes    what else the line says
     */
    public record Additions(String entry, List<String> added, List<String> replaced, List<String> kept,
                            List<String> notes) {

        public Additions {
            added = List.copyOf(added);
            replaced = List.copyOf(replaced);
            kept = List.copyOf(kept);
            notes = List.copyOf(notes);
        }

        /** An outcome with no notes */
        public Additions(String entry, List<String> added, List<String> replaced, List<String> kept) {
            this(entry, added, replaced, kept, List.of());
        }

        /**
         * Returns how a replaced value is listed: {@code <field> <old> → <new>}, a value the row lacked shown as
         * {@code none}
         *
         * @param field the field, e.g. {@code the priority}
         * @param from  the row's value
         * @param to    the later archive's value
         * @return the entry of {@code replaced}
         */
        public static String change(String field, Object from, Object to) {
            return field + " " + shown(from) + " → " + shown(to);
        }

        private static String shown(Object value) {
            return value == null || "".equals(value) ? "none" : String.valueOf(value);
        }

        /**
         * Returns the job log's line, e.g. {@code Parameter type it_composite (an earlier archive of this bootstrap
         * run created it): added p1 da; replaced the p1 en name First → FIRST}, or {@code …: nothing to add}
         *
         * @return the line
         */
        public String line() {
            List<String> parts = new ArrayList<>(notes);
            if (!added.isEmpty()) {
                parts.add("added " + String.join(", ", added));
            }
            if (!replaced.isEmpty()) {
                parts.add("replaced " + String.join(", ", replaced));
            }
            if (parts.isEmpty()) {
                parts.add("nothing to add");
            }
            if (!kept.isEmpty()) {
                parts.add("kept " + String.join(", ", kept));
            }
            return entry + " (an earlier archive of this bootstrap run created it): " + String.join("; ", parts);
        }
    }
}
