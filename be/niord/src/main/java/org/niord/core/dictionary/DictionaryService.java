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
package org.niord.core.dictionary;

import org.springframework.stereotype.Service;

import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.aton.AtonFilter;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.defaults.DefaultTexts;
import org.niord.core.defaults.DefaultsGuard;
import org.niord.core.defaults.DefaultsLedger;
import org.niord.core.dictionary.vo.DictionaryEntryDescVo;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.DictionaryImportPreviewVo;
import org.niord.core.dictionary.vo.DictionaryKeyUsersVo;
import org.niord.core.dictionary.vo.DictionaryVo;
import org.niord.core.dictionary.vo.ExportedDictionaryVo;
import org.niord.core.service.BaseService;
import org.niord.core.util.JsonUtils;
import org.niord.core.web.NiordWebException;
import org.niord.model.DataFilter;
import org.hibernate.Session;
import org.slf4j.Logger;
import tools.jackson.core.type.TypeReference;

import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import javax.script.ScriptException;
import java.sql.PreparedStatement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Business interface for accessing dictionaries.
 *
 * <p>At start-up the default texts are layered key by key ({@link DefaultTexts}) and each (dictionary, key, language)
 * the database lacks is added; an existing row is never changed. The {@link DefaultsLedger} records what was added
 * and what an operator deleted, so a deleted key is not added again (docs/code-data-config-plan.md §4).
 */
@Service
@SuppressWarnings("unused")
public class DictionaryService extends BaseService {

    public static final String[] DEFAULT_BUNDLES = {"web", "message", "pdf", "mail", "template"};

    /** The import's job property naming the missing keys to add; absent, every missing key not deleted is added */
    public static final String IMPORT_ADD = "add";

    /** The import's job property naming the existing keys to overwrite; absent, none is */
    public static final String IMPORT_OVERWRITE = "overwrite";

    /** The longest key, AtoN filter, value and long value a row holds (the columns' sizes) */
    static final int MAX_KEY_LENGTH = 255;
    static final int MAX_ATON_FILTER_LENGTH = 255;
    static final int MAX_VALUE_LENGTH = 1000;

    /**
     * The first key of the dictionary locks (two-key advisory locks: this space and the dictionary name's hash). It is
     * neither the S-124 dataset lock's space ({@code 0x5124}) nor the firing run lock's ({@code 0x0F12E}), and a
     * one-key lock (the defaults ledger's) is a key space of its own in PostgreSQL.
     */
    static final int DICTIONARY_LOCK_SPACE = 0x0D1C7;

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DictionaryService.class);

    private final NiordApp app;

    private final DefaultsLedger ledger;

    DictionaryService(NiordApp app, DefaultsLedger ledger) {
        this.app = app;
        this.ledger = ledger;
    }

    private Map<String, DictionaryVo> cachedDictionaries = new ConcurrentHashMap<>();

    /** Called upon application startup */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void init() {

        // Add the default texts the dictionaries lack
        ClassLoader cl = DictionaryService.class.getClassLoader();
        DefaultsGuard.check(cl, DEFAULT_BUNDLES, app.getLanguages());
        addMissingDefaults(DefaultTexts.read(cl, DEFAULT_BUNDLES, app.getLanguages(), app.getDefaultLanguage()));

        // Cache all dictionaries after the add, so the cache never holds what another start-up was adding
        long t0 = System.currentTimeMillis();
        cachedDictionaries.clear();
        List<Dictionary> dictionaries = getAll(Dictionary.class);
        dictionaries.forEach(d -> getCachedDictionary(d.getName()));
        log.info(String.format("Cached %d dictionaries in %d ms", dictionaries.size(), System.currentTimeMillis() - t0));
    }

    /**
     * Returns the dictionary names in sorted order
     *
     * @return the dictionary names
     */
    public List<String> getDictionaryNames() {
        return getAll(Dictionary.class).stream()
                .map(Dictionary::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Returns the dictionary with the given name
     *
     * @param name the name
     * @return the dictionary with the given name, or null if not found
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    public Dictionary findByName(String name) {
        try {
            return em
                    .createNamedQuery("Dictionary.findByName", Dictionary.class)
                    .setParameter("name", name)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Adds the given dictionary entry template
     *
     * @param name  the name of the dictionary
     * @param entry the template dictionary entry to add
     * @return the added dictionary entry
     */
    @Transactional
    public DictionaryEntry createEntry(String name, DictionaryEntry entry) {
        if (entry.isPersisted()) {
            throw new IllegalArgumentException("Cannot create dictionary entry with existing ID "
                    + entry.getId());
        }

        // One writer of the dictionary at a time (DICT5): the table has no unique key on (dictionary, key)
        lockDictionary(name);

        Dictionary dict = findByName(name);
        if (dict == null) {
            throw new IllegalArgumentException("Non-existing dictionary: " + name);
        }

        // The key is required: refused with a sentence (400), not left to the database's NOT NULL (500)
        if (StringUtils.isBlank(entry.getKey())) {
            throw new IllegalArgumentException("A dictionary entry needs a key");
        }

        // Read from the database after the lock, so a row another transaction committed meanwhile is seen, which the
        // dictionary's entries as this persistence context loaded them would not hold
        if (entryExists(name, entry.getKey())) {
            throw new IllegalArgumentException("Entry already exists: " + entry.getKey());
        }

        entry.getDescs().removeIf(desc -> !desc.descDefined());
        if (entry.getDescs().isEmpty()) {
            throw new IllegalArgumentException("No localized values defined for entry: " + entry.getKey());
        }

        entry.setDictionary(dict);
        dict.getEntries().put(entry.getKey(), entry);
        entry.getDescs().forEach(e -> e.setEntity(entry));

        // Persist the entry directly so the generated id is assigned to the returned instance.
        // Relying on the cascade from saveEntity(dict) (em.merge) would assign the id to a managed
        // copy and leave the returned transient entry id-less (DR1).
        DictionaryEntry persisted = saveEntity(entry);
        saveEntity(dict);

        evictCachedDictionary(name);

        return persisted;
    }

    /**
     * Takes the dictionary's lock until the caller's transaction ends: a transaction-level advisory lock keyed on
     * {@link #DICTIONARY_LOCK_SPACE} and the name's hash. The create and the import take it before they read whether
     * a key exists, so a second writer of the dictionary waits until the first has committed, and its reads, each a
     * statement of its own under READ COMMITTED, then see what the first stored. Two names of one hash share a lock,
     * which only makes one wait for the other.
     *
     * @param name the name of the dictionary
     */
    private void lockDictionary(String name) {
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                ps.setInt(1, DICTIONARY_LOCK_SPACE);
                ps.setInt(2, Objects.hashCode(name));
                ps.execute();
            }
        });
    }

    /** Whether the database holds a row of the key in the dictionary (this transaction's pending rows included) */
    private boolean entryExists(String name, String key) {
        return !em.createNamedQuery("DictionaryEntry.findByDictionaryAndKey", DictionaryEntry.class)
                .setParameter("name", name)
                .setParameter("key", key)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    /**
     * Updates the given dictionary entry template
     *
     * @param name  the name of the dictionary
     * @param entry the template dictionary entry to update
     * @return the updated dictionary entry
     */
    @Transactional
    public DictionaryEntry updateEntry(String name, DictionaryEntry entry) {
        Dictionary dict = findByName(name);
        if (dict == null) {
            throw new IllegalArgumentException("Non-existing dictionary: " + name);
        }

        DictionaryEntry original = dict.getEntries().get(entry.getKey());
        if (original == null) {
            throw new IllegalArgumentException("Non-existing dictionary entry: " + entry.getKey());
        }

        original.setAtonFilter(entry.getAtonFilter());

        // A stored value that is exactly "" is that language's text on purpose (the English
        // navwarn.date.time_interval_prefix), and the admin page sends it back as "": keep it. Removing it
        // would make the language fall back to another language's text, and start-up would not add it again, as the
        // ledger has it. Sending null still clears it; a language with no stored row is still not added empty.
        Set<String> emptyOnPurpose = original.getDescs().stream()
                .filter(desc -> "".equals(desc.getValue()))
                .map(DictionaryEntryDesc::getLang)
                .collect(Collectors.toSet());
        original.copyDescs(entry.getDescs());
        original.getDescs().removeIf(desc -> !desc.descDefined()
                && !(emptyOnPurpose.contains(desc.getLang()) && "".equals(desc.getValue())));
        original.getDescs().forEach(e -> e.setEntity(original));

        saveEntity(original);

        evictCachedDictionary(name);

        return original;
    }

    /**
     * Deletes the given dictionary key, in every language. The {@link DefaultsLedger} records the delete, so start-up
     * does not add the key again, in any language, a language added later included (R-CDC5). A key a list parameter
     * type holds among its values is not deleted: the database would refuse the delete at commit.
     *
     * @param name the name of the dictionary
     * @param key  the dictionary key to delete
     * @return if the entry was deleted
     * @throws NiordWebException 409 naming the parameter types, when a list parameter type holds the key
     */
    @Transactional
    public boolean deleteEntry(String name, String key) {
        Dictionary dict = findByName(name);
        if (dict == null) {
            throw new IllegalArgumentException("Non-existing dictionary: " + name);
        }

        DictionaryEntry original = dict.getEntries().get(key);
        if (original == null) {
            return false;
        }

        // Before any write, so that a refused delete leaves no deleted mark in the ledger
        List<String> paramTypes = paramTypesListing(name, key);
        if (!paramTypes.isEmpty()) {
            throw new NiordWebException("The key '" + key + "' of the dictionary '" + name
                    + "' is a value of the parameter types " + String.join(", ", paramTypes)
                    + "; remove it from them before the key is deleted", 409);
        }

        dict.getEntries().remove(key);
        original.setDictionary(null);
        remove(original);
        saveEntity(dict);
        ledger.recordDeleted(name, key);

        evictCachedDictionary(name);

        return true;
    }

    /**
     * Removes the cached dictionary now and again once the caller's transaction commits: a read on another thread
     * before the commit fills the cache again from the old rows, and would keep them until the next change of the
     * dictionary or a restart (SETTINGS-9). Without a transaction both happen at once.
     *
     * @param name the name of the dictionary
     */
    private void evictCachedDictionary(String name) {
        cachedDictionaries.remove(name);
        DefaultsLedger.afterCommit(() -> cachedDictionaries.remove(name));
    }

    /**
     * Returns what uses the key, for the Dictionaries page's delete confirmation (docs/code-data-config-plan.md
     * §4.6): the script resources that name the key in quotes, or its runtime prefix (such as {@code msg.status.},
     * which a template completes at runtime), the Java code that reads the key from this dictionary
     * ({@link RequiredTexts}), and the list parameter types whose values hold the key, which refuse its delete.
     *
     * <p>One query with up to four {@code LIKE} patterns over the script resources' content; no template is loaded
     * or parsed. The patterns quote the key with {@code "} and {@code '}, so a key does not match a longer key it
     * starts, and escape {@code %} and {@code _}, which keys contain. Parameter lists are not scanned.
     *
     * @param name the name of the dictionary
     * @param key  the key
     * @return what uses the key
     */
    @Transactional(readOnly = true)
    public DictionaryKeyUsersVo keyUsers(String name, String key) {
        String prefix = RequiredTexts.runtimePrefix(key);
        List<String> patterns = new ArrayList<>(quotedLikePatterns(key));
        if (prefix != null) {
            patterns.addAll(quotedLikePatterns(prefix));
        }

        StringBuilder jpql = new StringBuilder("select s.path from ScriptResource s where ");
        for (int i = 0; i < patterns.size(); i++) {
            jpql.append(i == 0 ? "" : " or ").append("s.content like :p").append(i).append(" escape '!'");
        }
        jpql.append(" order by s.path");
        TypedQuery<String> query = em.createQuery(jpql.toString(), String.class);
        for (int i = 0; i < patterns.size(); i++) {
            query.setParameter("p" + i, patterns.get(i));
        }

        List<String> readers = RequiredTexts.readers(name, key).stream().map(Enum::name).toList();
        return new DictionaryKeyUsersVo(query.getResultList(), prefix, readers, paramTypesListing(name, key));
    }

    /** The names of the list parameter types whose values hold the key of the dictionary, sorted */
    private List<String> paramTypesListing(String name, String key) {
        return em.createQuery("select distinct p.name from ListParamType p join p.values v "
                        + "where v.dictionary.name = :name and v.key = :key order by p.name", String.class)
                .setParameter("name", name)
                .setParameter("key", key)
                .getResultList();
    }

    /** The LIKE patterns of the text in double and in single quotes, with {@code !} as the escape character */
    private static List<String> quotedLikePatterns(String text) {
        String escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return List.of("%\"" + escaped + "\"%", "%'" + escaped + "'%");
    }

    /**
     * Returns the cached dictionary with the given name.
     *
     * <p>A translation whose value still equals what start-up added or adopted is marked {@code asAdded}
     * (docs/code-data-config-plan.md §4.5); the mark is cached with the dictionary and cleared with it.
     *
     * @param name the name
     * @return the cached dictionary with the given name
     */
    public DictionaryVo getCachedDictionary(String name) {
        // Check if the dictionary is cached already
        DictionaryVo dict = cachedDictionaries.get(name);
        if (dict == null) {
            Dictionary dictionary = findByName(name);
            if (dictionary != null) {
                // TODO: Load more efficiently by using "DictionaryEntry.loadWithDescs" query
                dict = dictionary.toVo(DataFilter.get());
                markAsAdded(dict);
                cachedDictionaries.put(dict.getName(), dict);
            }
        }

        return dict;
    }

    /**
     * Marks each translation whose value has the hash the ledger recorded when start-up added or adopted it. A key
     * an operator deleted is never marked: whatever has it since, an operator wrote.
     */
    private void markAsAdded(DictionaryVo dict) {
        DefaultsLedger.Texts texts = ledger.texts();
        for (DictionaryEntryVo entry : dict.getEntries().values()) {
            if (entry.getDescs() == null || texts.deleted(dict.getName(), entry.getKey())) {
                continue;
            }
            for (DictionaryEntryDescVo desc : entry.getDescs()) {
                String hash = texts.hash(dict.getName(), entry.getKey(), desc.getLang());
                if (hash != null && desc.getValue() != null && hash.equals(DefaultTexts.sha256(desc.getValue()))) {
                    desc.setAsAdded(true);
                }
            }
        }
    }

    /**
     * Shortcut for getting a dictionary value
     *
     * @param name the name of the dictionary
     * @param lang the language of the dictionary
     * @param key  the key
     */
    public String value(String name, String lang, String key) {
        DictionaryVo dict = getCachedDictionary(name);
        return dict != null ? dict.value(lang, key, app.getDefaultLanguage()) : null;
    }

    /**
     * Returns the given dictionaries as a Properties object for the given language.
     * Returns null if undefined.
     *
     * @param names    the dictionary names
     * @param language the language
     * @return the dictionaries for the given language as a Properties object
     */
    public Properties getDictionariesAsProperties(String[] names, String language) {
        Properties langDict = new Properties();
        for (String name : names) {
            DictionaryVo dictionary = getCachedDictionary(name);
            if (dictionary != null) {
                langDict.putAll(dictionary.toProperties(language, app.getDefaultLanguage()));
            }
        }
        return langDict;
    }

    /**
     * Returns the given dictionaries as a ResourceBundle for the given language.
     * Returns null if undefined.
     *
     * @param names    the dictionary names
     * @param language the language
     * @return the dictionaries for the given language as a ResourceBundle
     */
    public ResourceBundle getDictionariesAsResourceBundle(String[] names, String language) {

        // Construct a property file with all language-specific values from all included dictionaries
        Properties langDict = getDictionariesAsProperties(names, language);

        final Locale dictLocale = new Locale(language);

        // Convert the Properties object to a resource bundle
        return new ResourceBundle() {

            /** {@inheritDoc} **/
            @Override
            @SuppressWarnings("all")
            protected Object handleGetObject(String key) {
                return langDict.getProperty(key);
            }

            /** {@inheritDoc} **/
            public Locale getLocale() {
                return dictLocale;
            }

            /** {@inheritDoc} **/
            @Override
            @SuppressWarnings("all")
            public Enumeration<String> getKeys() {
                Set<String> handleKeys = langDict.stringPropertyNames();
                return Collections.enumeration(handleKeys);
            }
        };
    }

    /**
     * Adds the default texts the dictionaries lack and records them in the {@link DefaultsLedger}; never changes or
     * deletes an existing description (docs/code-data-config-plan.md §4.2).
     *
     * <p>Takes the add-missing advisory lock first and reads the database and the ledger only then, so a second
     * start-up on the same database waits for the first and then finds nothing missing. Per default (dictionary,
     * key, language):
     * <ul>
     *     <li>present in the database and not in the ledger: recorded as {@code adopted}, with the value's hash only
     *         when the row equals the default;</li>
     *     <li>missing, while the ledger has the key deleted, or has the triple (a cleared translation), or has the
     *         key in some language and the database lacks it in every language (removed some other way): skipped;</li>
     *     <li>otherwise missing: added, and recorded with its layer as the source and the value's hash.</li>
     * </ul>
     * The ledger rows are written in one batch.
     *
     * @param layered the layered defaults
     * @return the number of descriptions added
     */
    @Transactional
    int addMissingDefaults(DefaultTexts.Layered layered) {
        ledger.lockAddMissing();

        // The database's descriptions of the layered dictionaries, read after the lock
        Set<String> names = layered.bundles();
        Map<String, String> existing = new HashMap<>();
        Set<String> existingKeys = new HashSet<>();
        if (!names.isEmpty()) {
            em.createQuery("select d.name, e.key, t.lang, t.value from DictionaryEntry e join e.dictionary d "
                            + "join e.descs t where d.name in :names", Object[].class)
                    .setParameter("names", names)
                    .getResultList()
                    .forEach(r -> {
                        existing.putIfAbsent(triple((String) r[0], (String) r[1], (String) r[2]), (String) r[3]);
                        existingKeys.add(triple((String) r[0], (String) r[1], ""));
                    });
        }

        DefaultsLedger.Texts ledgered = ledger.loadTexts();

        List<DefaultsLedger.Row> rows = new ArrayList<>();
        int total = 0;
        for (String name : names) {
            long t0 = System.currentTimeMillis();
            Dictionary dictionary = null;
            int added = 0;
            int adopted = 0;
            for (String lang : layered.languages(name)) {
                for (Map.Entry<String, DefaultTexts.Text> text : layered.texts(name, lang).entrySet()) {
                    String key = text.getKey();
                    String value = text.getValue().value();
                    String current = existing.get(triple(name, key, lang));
                    if (current != null) {
                        if (!ledgered.has(name, key, lang)) {
                            String hash = current.equals(value) ? DefaultTexts.sha256(value) : null;
                            rows.add(DefaultsLedger.Row.text(name, key, lang, hash, DefaultsLedger.SOURCE_ADOPTED));
                            adopted++;
                        }
                        continue;
                    }
                    if (ledgered.deleted(name, key)
                            || ledgered.has(name, key, lang)
                            || (!existingKeys.contains(triple(name, key, "")) && ledgered.hasKey(name, key))) {
                        continue;
                    }
                    if (dictionary == null) {
                        dictionary = findOrCreateDictionary(name);
                    }
                    updateEntry(dictionary, lang, key, value);
                    rows.add(DefaultsLedger.Row.text(name, key, lang, DefaultTexts.sha256(value),
                            text.getValue().source()));
                    added++;
                }
            }
            if (dictionary != null) {
                cachedDictionaries.remove(name);
            }
            if (added > 0 || adopted > 0) {
                log.info(String.format("Added %d and adopted %d default '%s' dictionary texts in %d ms",
                        added, adopted, name, System.currentTimeMillis() - t0));
            }
            total += added;
        }

        // Write the added descriptions before their ledger rows, and before a caller's em.clear() could drop them
        em.flush();
        long t0 = System.currentTimeMillis();
        int written = ledger.insertAll(rows);
        if (written > 0) {
            log.info(String.format("Recorded %d default texts in the defaults ledger in %d ms",
                    written, System.currentTimeMillis() - t0));
        }

        // The ledger's in-memory hashes take the new rows only once they commit, but init() caches the dictionaries
        // before that, so a cached dictionary would lack this start's asAdded marks. Evict the dictionaries of the
        // new rows after the commit. Registered after insertAll, so it runs after the ledger's own after-commit
        // update: Spring runs synchronizations of the same order in the order they were registered.
        Set<String> rowDictionaries = rows.stream().map(DefaultsLedger.Row::dictionary).collect(Collectors.toSet());
        if (!rowDictionaries.isEmpty()) {
            DefaultsLedger.afterCommit(() -> rowDictionaries.forEach(cachedDictionaries::remove));
        }
        return total;
    }

    /** The lookup key of a (dictionary, key, language) */
    private static String triple(String name, String key, String lang) {
        return name + '\0' + key + '\0' + lang;
    }

    /** Returns the dictionary with the given name, creating it when it does not exist */
    private Dictionary findOrCreateDictionary(String name) {
        Dictionary dictionary = findByName(name);
        if (dictionary == null) {
            dictionary = new Dictionary();
            dictionary.setName(name);
            em.persist(dictionary);
        }
        return dictionary;
    }

    /*************************/
    /** Import               **/
    /*************************/

    /** A key of a dictionary, as the import's ticks name it */
    public record ImportKey(String dictionary, String key) {
    }

    /**
     * The sysadmin's ticks: the missing keys to add, or null for every missing key the defaults ledger does not record
     * as deleted; the existing keys to overwrite, each with the fingerprint of the row the preview showed (null when
     * the tick does not give one), or null for none
     */
    public record ImportChoice(Set<ImportKey> add, Map<ImportKey, String> overwrite) {

        /** What an import without ticks does: add the missing keys not deleted, overwrite none */
        public static final ImportChoice DEFAULT = new ImportChoice(null, null);
    }

    /**
     * What an import of one dictionary did; {@code changedSincePreview} names the ticked keys kept because their row
     * changed after the preview (counted in {@code kept}). Within the first bootstrap's rule ({@link BootstrapRun}),
     * for the keys an earlier archive of the run created: {@code languagesAdded} counts those that gained a language,
     * {@code replacedInRun} those whose value, long value or AtoN filter in a language the row had was replaced by the
     * later archive's (a key may count in both), and {@code sameRun} says, a line each, what was added and replaced,
     * and which key of a default text's dictionary a later archive of the run restates and start-up's rules keep
     */
    public record ImportResult(int added, int overwritten, int kept, int notAdded, int unchanged,
                               List<String> changedSincePreview, int languagesAdded, int replacedInRun,
                               List<String> sameRun) {
    }

    /**
     * Returns what importing the dictionaries would do (docs/code-data-config-plan.md §4.6). It only reads.
     *
     * <p>A key is one (dictionary, key). An entry the import refuses ({@link #importRefusal}) is listed with its
     * reasons. A key the database lacks is added, marked {@code deleted} when the defaults ledger records that an
     * operator deleted it (read from the database, {@link DefaultsLedger#deletedKeys}); a key whose AtoN filter and
     * every language the file carries, value and long value, equal the row's is unchanged; any other key, one the row
     * lacks a language of included, would be overwritten, with the fingerprint of the row's values
     * ({@link #fingerprint}). A long value the file lacks for a language it carries is a difference: the overwrite
     * clears it.
     *
     * @param dictionaries the import file's dictionaries
     * @return what the import would do, sorted by dictionary and key
     */
    @Transactional(readOnly = true)
    public DictionaryImportPreviewVo previewImport(List<ExportedDictionaryVo> dictionaries) {
        DictionaryImportPreviewVo preview = new DictionaryImportPreviewVo();
        Map<String, Optional<Dictionary>> rows = new HashMap<>();
        Map<String, Set<String>> deleted = new HashMap<>();
        Set<ImportKey> seen = new HashSet<>();

        for (ExportedDictionaryVo dictionary : dictionaries) {
            if (dictionary == null) {
                preview.getRefused().add(new DictionaryImportPreviewVo.Refused(null, null,
                        "the entry is not a dictionary"));
                continue;
            }
            String name = dictionary.getName();
            Dictionary row = StringUtils.isBlank(name) ? null
                    : rows.computeIfAbsent(name, n -> Optional.ofNullable(findByName(n))).orElse(null);
            List<DictionaryEntryVo> entries = dictionary.getEntries() != null ? dictionary.getEntries() : List.of();
            for (DictionaryEntryVo entry : entries) {
                List<String> reasons = importRefusal(name, entry, seen);
                if (!reasons.isEmpty()) {
                    preview.getRefused().add(new DictionaryImportPreviewVo.Refused(
                            name, entry == null ? null : entry.getKey(), String.join("; ", reasons)));
                    continue;
                }
                DictionaryImportPreviewVo.Values incoming = valuesOf(entry);
                DictionaryEntry current = row == null ? null : row.getEntries().get(entry.getKey());
                if (current == null) {
                    DictionaryImportPreviewVo.Add add = new DictionaryImportPreviewVo.Add();
                    add.setDictionary(name);
                    add.setKey(entry.getKey());
                    add.setAtonFilter(incoming.getAtonFilter());
                    add.setValues(incoming.getValues());
                    add.setLongValues(incoming.getLongValues());
                    add.setDeleted(deleted.computeIfAbsent(name, ledger::deletedKeys).contains(entry.getKey()));
                    preview.getAdd().add(add);
                    continue;
                }
                DictionaryImportPreviewVo.Values existing = valuesOf(current);
                if (sameValues(existing, incoming)) {
                    preview.setUnchanged(preview.getUnchanged() + 1);
                } else {
                    DictionaryImportPreviewVo.Overwrite overwrite = new DictionaryImportPreviewVo.Overwrite();
                    overwrite.setDictionary(name);
                    overwrite.setKey(entry.getKey());
                    overwrite.setCurrent(existing);
                    overwrite.setIncoming(incoming);
                    overwrite.setBasedOn(fingerprint(existing));
                    preview.getOverwrite().add(overwrite);
                }
            }
        }

        Comparator<String> nullsFirst = Comparator.nullsFirst(Comparator.naturalOrder());
        preview.getAdd().sort(Comparator.comparing(DictionaryImportPreviewVo.Add::getDictionary, nullsFirst)
                .thenComparing(DictionaryImportPreviewVo.Add::getKey, nullsFirst));
        preview.getOverwrite().sort(Comparator.comparing(DictionaryImportPreviewVo.Overwrite::getDictionary, nullsFirst)
                .thenComparing(DictionaryImportPreviewVo.Overwrite::getKey, nullsFirst));
        preview.getRefused().sort(Comparator.comparing(DictionaryImportPreviewVo.Refused::getDictionary, nullsFirst)
                .thenComparing(DictionaryImportPreviewVo.Refused::getKey, nullsFirst));
        return preview;
    }

    /**
     * Returns why the import refuses the entry: a dictionary without a name, a blank key, an entry without a
     * translation, a key, AtoN filter, value or long value longer than its column, a translation without a language
     * or a value, a control character other than tab and newline, or a key that appears earlier in the same
     * dictionary of the import. Records the key as seen. A blank value is accepted: the base product has one
     * ({@code navwarn.date.time_interval_prefix} in the template texts), so its own texts file could not be imported
     * otherwise.
     *
     * @param dictionary the dictionary's name
     * @param entry      the entry
     * @param seen       the keys of the import seen so far; updated
     * @return the reasons, empty when the entry is accepted
     */
    public static List<String> importRefusal(String dictionary, DictionaryEntryVo entry, Set<ImportKey> seen) {
        if (entry == null) {
            return List.of("the entry is not a dictionary entry");
        }
        List<String> reasons = new ArrayList<>();
        if (StringUtils.isBlank(dictionary)) {
            reasons.add("the dictionary has no name");
        }
        String key = entry.getKey();
        if (StringUtils.isBlank(key)) {
            reasons.add("the key is blank");
        } else {
            checkText("the key", key, MAX_KEY_LENGTH, reasons);
        }
        if (entry.getAtonFilter() != null) {
            checkText("the AtoN filter", entry.getAtonFilter(), MAX_ATON_FILTER_LENGTH, reasons);
        }
        if (entry.getDescs() == null || entry.getDescs().isEmpty()) {
            reasons.add("the entry has no translation");
        } else {
            for (DictionaryEntryDescVo desc : entry.getDescs()) {
                if (desc == null || StringUtils.isBlank(desc.getLang())) {
                    reasons.add("a translation has no language");
                    continue;
                }
                if (desc.getValue() == null) {
                    reasons.add("the '" + desc.getLang() + "' translation has no value");
                } else {
                    checkText("the '" + desc.getLang() + "' value", desc.getValue(), MAX_VALUE_LENGTH, reasons);
                }
                if (desc.getLongValue() != null) {
                    checkText("the '" + desc.getLang() + "' long value", desc.getLongValue(), MAX_VALUE_LENGTH,
                            reasons);
                }
            }
        }
        if (StringUtils.isNotBlank(dictionary) && StringUtils.isNotBlank(key)
                && !seen.add(new ImportKey(dictionary, key))) {
            reasons.add("the key appears earlier in the dictionary");
        }
        return reasons;
    }

    /** Adds a reason when the text is longer than its column or has a control character other than tab and newline */
    private static void checkText(String what, String text, int maxLength, List<String> reasons) {
        int length = text.codePointCount(0, text.length());
        if (length > maxLength) {
            reasons.add(what + " is longer than " + maxLength + " characters (" + length + ")");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n') || c == 0x7F) {
                reasons.add(what + " has the control character " + String.format("U+%04X", (int) c)
                        + "; only tab and newline are allowed");
                break;
            }
        }
    }

    /**
     * Reads the {@value #IMPORT_ADD} or {@value #IMPORT_OVERWRITE} job property or form field
     *
     * @param name  the property's name, used in the message
     * @param value the property: a JSON array of {@code {"dictionary": ..., "key": ...}} as a string, a collection of
     *              such maps, or null
     * @return the keys, or null when the property is absent
     * @throws IllegalArgumentException when it is not such an array
     */
    public static Set<ImportKey> importKeys(String name, Object value) {
        Map<ImportKey, String> ticks = importTicks(name, value);
        return ticks == null ? null : new LinkedHashSet<>(ticks.keySet());
    }

    /**
     * Reads the {@value #IMPORT_OVERWRITE} job property or form field with the fingerprints its elements may carry:
     * {@code {"dictionary": ..., "key": ..., "basedOn": ...}}, {@code basedOn} being the preview's fingerprint of the
     * row ({@link DictionaryImportPreviewVo.Overwrite#getBasedOn()})
     *
     * @param name  the property's name, used in the message
     * @param value the property, as {@link #importKeys} reads it
     * @return each key with its {@code basedOn}, null where the element gives none; null when the property is absent
     * @throws IllegalArgumentException when it is not such an array
     */
    public static Map<ImportKey, String> importTicks(String name, Object value) {
        if (value == null) {
            return null;
        }
        Collection<?> items;
        if (value instanceof String json) {
            try {
                items = JsonUtils.fromJson(json, new TypeReference<List<Object>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("The field '" + name + "' is not a JSON array of dictionary keys: "
                        + e.getMessage());
            }
            if (items == null) {
                throw new IllegalArgumentException("The field '" + name + "' is not a JSON array of dictionary keys");
            }
        } else if (value instanceof Collection<?> collection) {
            items = collection;
        } else {
            throw new IllegalArgumentException("The field '" + name + "' is not a JSON array of dictionary keys");
        }
        Map<ImportKey, String> keys = new LinkedHashMap<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("dictionary") instanceof String dictionary)
                    || !(map.get("key") instanceof String key)
                    || (map.get("basedOn") != null && !(map.get("basedOn") instanceof String))) {
                throw new IllegalArgumentException("The field '" + name + "' holds " + item
                        + ", which is not a {\"dictionary\": ..., \"key\": ...}");
            }
            keys.put(new ImportKey(dictionary, key), (String) map.get("basedOn"));
        }
        return keys;
    }

    /**
     * The fingerprint of a row's values, as the preview gives it ({@code basedOn}) and the job compares it: the
     * sha-256 of the AtoN filter and of each language, in order, with its value and long value
     *
     * @param values the values, as {@link #valuesOf} reads them
     * @return {@code sha256:<64 hex>}
     */
    public static String fingerprint(DictionaryImportPreviewVo.Values values) {
        StringBuilder canonical = new StringBuilder("aton\0").append(StringUtils.defaultString(values.getAtonFilter()));
        Set<String> langs = new TreeSet<>(values.getValues().keySet());
        langs.addAll(values.getLongValues().keySet());
        for (String lang : langs) {
            canonical.append('\n').append(lang)
                    .append('\0').append(StringUtils.defaultString(values.getValues().get(lang)))
                    .append('\0').append(StringUtils.defaultString(values.getLongValues().get(lang)));
        }
        return "sha256:" + DefaultTexts.sha256(canonical.toString());
    }

    /** A file entry's values as the import compares and shows them */
    static DictionaryImportPreviewVo.Values valuesOf(DictionaryEntryVo entry) {
        DictionaryImportPreviewVo.Values values = new DictionaryImportPreviewVo.Values();
        values.setAtonFilter(StringUtils.defaultIfEmpty(entry.getAtonFilter(), null));
        if (entry.getDescs() != null) {
            entry.getDescs().forEach(desc -> putValues(values, desc.getLang(), desc.getValue(), desc.getLongValue()));
        }
        return values;
    }

    /** A row's values as the import compares and shows them */
    static DictionaryImportPreviewVo.Values valuesOf(DictionaryEntry entry) {
        DictionaryImportPreviewVo.Values values = new DictionaryImportPreviewVo.Values();
        values.setAtonFilter(StringUtils.defaultIfEmpty(entry.getAtonFilter(), null));
        entry.getDescs().forEach(desc -> putValues(values, desc.getLang(), desc.getValue(), desc.getLongValue()));
        return values;
    }

    private static void putValues(DictionaryImportPreviewVo.Values values, String lang, String value,
                                  String longValue) {
        values.getValues().put(lang, StringUtils.defaultString(value));
        if (StringUtils.isNotEmpty(longValue)) {
            values.getLongValues().put(lang, longValue);
        }
    }

    /** Whether the row's values equal the file's: the AtoN filter, and each language the file carries */
    static boolean sameValues(DictionaryImportPreviewVo.Values current, DictionaryImportPreviewVo.Values incoming) {
        if (!Objects.equals(current.getAtonFilter(), incoming.getAtonFilter())) {
            return false;
        }
        for (Map.Entry<String, String> value : incoming.getValues().entrySet()) {
            String lang = value.getKey();
            if (!current.getValues().containsKey(lang)
                    || !Objects.equals(current.getValues().get(lang), value.getValue())
                    || !Objects.equals(current.getLongValues().get(lang), incoming.getLongValues().get(lang))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Imports a dictionary (docs/code-data-config-plan.md §4.6): adds the missing keys the choice names (without a
     * choice, every missing key the defaults ledger does not record as deleted, read from the database), and
     * overwrites an existing key only when the choice names it, writing each language the file carries whole, value
     * and long value (a long value the file lacks is cleared), keeping the other languages and replacing the AtoN
     * filter. A ticked key whose row has changed since the preview (its fingerprint is not the tick's
     * {@code basedOn}) is kept. A key whose values equal the file's is left as it is. The dictionary is created when
     * a key is added to it.
     *
     * <p>A deleted key restored here keeps its deleted mark in the ledger, which only ever inserts: start-up never
     * adds it again, also not in a language added to the model later, so such a language is completed by import.
     *
     * <p>Kept on purpose as the entry outside a bootstrap run ({@link BootstrapRun.Scope#NONE}), as
     * {@code CategoryService.importCategory(Category, boolean, boolean)} is: a published library method.
     *
     * @param dictionary the dictionary to import, its entries already checked ({@link #importRefusal})
     * @param choice     the sysadmin's ticks
     * @return what the import did
     */
    @Transactional
    public ImportResult importDictionary(Dictionary dictionary, ImportChoice choice) {
        return importDictionary(dictionary, choice, BootstrapRun.Scope.NONE);
    }

    /**
     * Imports a dictionary as {@link #importDictionary(Dictionary, ImportChoice)} does, within the scope of a bootstrap
     * job. Within the first bootstrap's rule ({@link BootstrapRun}), for a key an earlier archive of the run created,
     * the later archive's values replace ({@link #replaceLanguages}): a language the row lacks is added, value and long
     * value; in a language the row has, the value is replaced, and the long value when the file gives one; a language
     * the file leaves out, or gives with an empty value, is kept; an AtoN filter the file gives replaces the row's, and one it leaves out is kept.
     * Each change is noted for the job log. A key whose only differences are a language spelt in another case with
     * equal values, or an AtoN filter or long value the file leaves out, is counted unchanged. A key of a default
     * text's dictionary ({@link #DEFAULT_BUNDLES}) that start-up created and a later archive of the run restates with
     * other values is kept by start-up's rules, and noted for the job log. Outside a run
     * ({@link BootstrapRun.Scope#NONE}) it is that method.
     *
     * @param dictionary the dictionary to import, its entries already checked ({@link #importRefusal})
     * @param choice     the sysadmin's ticks
     * @param scope      the bootstrap run's scope of the job
     * @return what the import did
     */
    @Transactional
    public ImportResult importDictionary(Dictionary dictionary, ImportChoice choice, BootstrapRun.Scope scope) {
        long t0 = System.currentTimeMillis();
        String name = dictionary.getName();

        // One writer of the dictionary at a time (DICT5): the dictionary and its entries are read after the lock, so
        // a key a create committed meanwhile is found and not added a second time
        lockDictionary(name);
        Set<String> deleted = choice.add() == null ? ledger.deletedKeys(name) : Set.of();

        Dictionary original = findByName(name);
        int added = 0;
        int overwritten = 0;
        int kept = 0;
        int notAdded = 0;
        int unchanged = 0;
        int languagesAdded = 0;
        int replacedInRun = 0;
        List<String> changedSincePreview = new ArrayList<>();
        List<String> sameRun = new ArrayList<>();
        for (DictionaryEntry entry : dictionary.getEntries().values()) {
            ImportKey id = new ImportKey(name, entry.getKey());
            DictionaryEntry current = original == null ? null : original.getEntries().get(entry.getKey());
            if (current == null) {
                boolean tick = choice.add() == null
                        ? !deleted.contains(entry.getKey())
                        : choice.add().contains(id);
                if (!tick) {
                    notAdded++;
                    continue;
                }
                if (original == null) {
                    original = new Dictionary();
                    original.setName(name);
                    em.persist(original);
                }
                original.createEntry(entry.getKey()).updateDictionaryEntry(entry);
                scope.created(BootstrapRun.Kind.DICTIONARY_ENTRY, BootstrapRun.dictionaryKey(name, entry.getKey()));
                added++;
            } else if (sameValues(valuesOf(current), valuesOf(entry))) {
                unchanged++;
            } else if (scope.createdEarlier(BootstrapRun.Kind.DICTIONARY_ENTRY,
                    BootstrapRun.dictionaryKey(name, entry.getKey()))) {
                Replacement replacement = replaceLanguages(current, entry, sameRun);
                languagesAdded += replacement.added() ? 1 : 0;
                replacedInRun += replacement.replaced() ? 1 : 0;
                unchanged += !replacement.added() && !replacement.replaced() ? 1 : 0;
            } else if (choice.overwrite() != null && choice.overwrite().containsKey(id)) {
                String basedOn = choice.overwrite().get(id);
                if (basedOn != null && !basedOn.equals(fingerprint(valuesOf(current)))) {
                    changedSincePreview.add(entry.getKey());
                    kept++;
                    continue;
                }
                current.updateDictionaryEntry(entry);
                overwritten++;
            } else {
                kept++;
                // A default text no archive of the run created: start-up wrote it before the run began
                if (scope.archive() > 0 && List.of(DEFAULT_BUNDLES).contains(name) && scope.createdBy(
                        BootstrapRun.Kind.DICTIONARY_ENTRY, BootstrapRun.dictionaryKey(name, entry.getKey())) < 0) {
                    sameRun.add("Kept " + entry.getKey() + " (start-up created it, not this bootstrap run: "
                            + "start-up's rules apply)");
                }
            }
        }

        if (added > 0 || overwritten > 0 || languagesAdded > 0 || replacedInRun > 0) {
            saveEntity(original);

            // When the import runs inside a caller's transaction, the second removal waits for that commit
            evictCachedDictionary(name);
        }

        log.info(String.format("Imported the '%s' dictionary in %d ms: added %d, overwritten %d, kept %d, "
                        + "not added %d, unchanged %d, languages added to %d, replaced in the run %d", name,
                System.currentTimeMillis() - t0, added, overwritten, kept, notAdded, unchanged, languagesAdded,
                replacedInRun));
        return new ImportResult(added, overwritten, kept, notAdded, unchanged, changedSincePreview, languagesAdded,
                replacedInRun, sameRun);
    }

    /** What {@link #replaceLanguages} did to a key: whether it added a language, and whether it replaced a value */
    private record Replacement(boolean added, boolean replaced) {
    }

    /**
     * Replaces a row's values with the file's entry's within the first bootstrap's rule (docs/code-data-config-plan.md
     * §7.2): a language the row lacks is added, value and long value; in a language the row has, the value is set
     * when it differs, and the long value when the file gives a non-empty one that differs; a language the file leaves
     * out, or gives with an empty value, is kept (and one the row lacks is not added); an AtoN filter the file gives non-empty and different replaces the row's, and one it leaves out or
     * gives empty is kept. Languages are compared as {@code getDesc} finds them, ignoring case. The row's descriptions
     * are changed in place, and a field is written only when its value differs.
     *
     * @param current the row
     * @param entry   the file's entry
     * @param notes   receives a line for each language added and each value replaced
     * @return whether a language was added, and whether a value was replaced
     */
    private static Replacement replaceLanguages(DictionaryEntry current, DictionaryEntry entry, List<String> notes) {
        String createdIt = " (an earlier archive of this bootstrap run created it)";
        boolean addedOne = false;
        boolean replaced = false;
        for (DictionaryEntryDesc desc : entry.getDescs()) {
            // An empty value counts as left out: a bootstrap never clears a value, nor adds an empty language
            if (StringUtils.isBlank(desc.getLang()) || StringUtils.isEmpty(desc.getValue())) {
                continue;
            }
            DictionaryEntryDesc existing = current.getDesc(desc.getLang());
            if (existing == null) {
                DictionaryEntryDesc created = current.checkCreateDesc(desc.getLang());
                created.setValue(desc.getValue());
                created.setLongValue(desc.getLongValue());
                notes.add("Added " + desc.getLang() + " to " + entry.getKey() + createdIt);
                addedOne = true;
                continue;
            }
            String lang = existing.getLang();
            if (!Objects.equals(StringUtils.defaultString(existing.getValue()),
                    StringUtils.defaultString(desc.getValue()))) {
                notes.add(BootstrapRun.Additions.change("Replaced the " + lang + " of " + entry.getKey() + createdIt
                        + ":", existing.getValue(), desc.getValue()));
                existing.setValue(desc.getValue());
                replaced = true;
            }
            if (StringUtils.isNotEmpty(desc.getLongValue())
                    && !Objects.equals(existing.getLongValue(), desc.getLongValue())) {
                notes.add(BootstrapRun.Additions.change("Replaced the " + lang + " long value of " + entry.getKey()
                        + createdIt + ":", existing.getLongValue(), desc.getLongValue()));
                existing.setLongValue(desc.getLongValue());
                replaced = true;
            }
        }
        if (StringUtils.isNotEmpty(entry.getAtonFilter())
                && !Objects.equals(entry.getAtonFilter(), current.getAtonFilter())) {
            notes.add(BootstrapRun.Additions.change("Replaced the AtoN filter of " + entry.getKey() + createdIt + ":",
                    current.getAtonFilter(), entry.getAtonFilter()));
            current.setAtonFilter(entry.getAtonFilter());
            replaced = true;
        }
        return new Replacement(addedOne, replaced);
    }

    /**
     * Creates or updates the given dictionary entry
     * @param dict the dictionary
     * @param lang the language of the resource bundle
     * @param key the key
     * @param value the value
     */
    private void updateEntry(Dictionary dict, String lang, String key, String value) {
        DictionaryEntry entry = dict.getEntries().get(key);
        if (entry == null) {
            entry = dict.createEntry(key);
        }
        DictionaryEntryDesc desc = entry.checkCreateDesc(lang);
        desc.setValue(value);
    }

    /**
     * Returns the persisted dictionary entries of the given entries, read from the database, as
     * {@link #persistedList(List, List)} finds them with no stored values.
     *
     * @param entries the list of dictionary entries to look up persisted entities for
     * @return the list of corresponding persisted entries
     * @throws IllegalArgumentException when an entry given by its key alone has a key more than one dictionary holds
     */
    public List<DictionaryEntry> persistedList(List<DictionaryEntry> entries) {
        return persistedList(entries, List.of());
    }

    /**
     * Returns the persisted dictionary entries of the given entries, read from the database, not the cached
     * dictionaries; used for the values of a list parameter type. A value is found by its id; else, by its key,
     * among the stored values (the list type's values before the update: the entry of the type's own dictionary);
     * else as the entry of its key, when one dictionary holds the key (its row of the lowest id, should the dictionary
     * hold the key in more than one row). A value whose key more than one dictionary holds is refused, naming them
     * (DICT6: the first row found used to be taken, whichever dictionary it was in). A value found in none is dropped.
     *
     * @param entries the list of dictionary entries to look up persisted entities for
     * @param stored  the stored values the entries replace; empty for a new list
     * @return the list of corresponding persisted entries
     * @throws IllegalArgumentException when an entry given by its key alone has a key more than one dictionary holds
     *                                  and the stored values do not
     */
    public List<DictionaryEntry> persistedList(List<DictionaryEntry> entries, List<DictionaryEntry> stored) {
        List<DictionaryEntry> result = new ArrayList<>();
        for (DictionaryEntry entry : entries) {
            DictionaryEntry original = null;

            // First, look up the entry by ID
            if (entry.getId() != null) {
                original = getByPrimaryKey(DictionaryEntry.class, entry.getId());
            }

            // If not defined by ID, look up by key
            if (original == null && entry.getKey() != null) {
                original = persistedByKey(entry.getKey(), stored);
            }

            if (original != null) {
                result.add(original);
            }
        }
        return result;
    }

    /**
     * The stored value of the key, else the entry of the key in the one dictionary that holds it; refused when more
     * than one dictionary holds it. A dictionary may hold one key in more than one row (DICT5: there is no unique key
     * on the dictionary and the key); its row of the lowest id is taken, so the result is the same every time.
     */
    private DictionaryEntry persistedByKey(String key, List<DictionaryEntry> stored) {
        List<DictionaryEntry> held = stored.stream()
                .filter(e -> e != null && key.equals(e.getKey()))
                .distinct()
                .toList();
        if (held.size() == 1) {
            return held.getFirst();
        }
        List<DictionaryEntry> found = em.createNamedQuery("DictionaryEntry.findByKey", DictionaryEntry.class)
                .setParameter("key", key)
                .getResultList();
        if (found.stream().map(e -> e.getDictionary().getId()).distinct().count() > 1) {
            throw new IllegalArgumentException("The dictionary key " + key + " is in more than one dictionary ("
                    + found.stream().map(e -> e.getDictionary().getName()).distinct().sorted()
                            .collect(Collectors.joining(", "))
                    + "); give the value's id to say which");
        }
        return found.stream().min(Comparator.comparing(DictionaryEntry::getId)).orElse(null);
    }

    /**
     * *******************************************
     * AtoN matching
     * *******************************************
     */

    /**
     * Returns the first dictionary list entry that matches the AtoN
     * @param aton the AtoN to match
     * @param values the list of dictionary values to check
     * @return the first dictionary list entry that matches the AtoN
     */
    public DictionaryEntryVo matchesAton(AtonNodeVo aton, List<DictionaryEntryVo> values) {
        return values.stream()
                .filter(e -> StringUtils.isNotBlank(e.getAtonFilter()))
                .filter(e -> {
                    try {
                        return AtonFilter.getInstance(e.getAtonFilter()).matches(aton);
                    } catch (ScriptException e1) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(null);
    }
}
