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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.defaults.DefaultTexts;
import org.niord.core.defaults.DefaultsLedger;
import org.niord.test.Tx;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.dictionary.vo.DictionaryKeyUsersVo;
import org.niord.core.dictionary.vo.DictionaryVo;
import org.niord.core.script.ScriptResource;
import org.niord.test.ITTest;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link DictionaryService} persistence, caching and the start-up add-missing step, run
 * against the seeded PostgreSQL container of the {@link ITTest} infrastructure.
 *
 * <p>Each test is {@code @Transactional}, so all database writes, the {@code "DefaultsLedger"} rows included, are
 * rolled back, keeping the shared seed database pristine and the tests independent. The asAdded and keyUsers
 * sections commit their fixtures instead ({@link Tx#requiringNew()}): the ledger's in-memory hashes take rows only
 * once they commit. The committed {@link #removeSyntheticDictionaries()} removes whatever of the synthetic
 * dictionary and the script resource fixtures could outlive a test.
 *
 * <p><b>Cache caveat:</b> {@link DictionaryService} maintains an in-process {@code cachedDictionaries}
 * map that {@code createEntry}/{@code updateEntry}/{@code deleteEntry} invalidate by dictionary name, but
 * the rollback only reverts the database — not that cache. To keep cache state from leaking
 * between tests, every test that mutates a dictionary uses its own freshly-persisted dictionary with a
 * unique {@code it-test-dict-*} name (the seeded dictionaries web/message/pdf/template/mail/aton are never
 * mutated). See docs/framework-coupled-tests.md.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class DictionaryServiceITTest extends ITTest {

    @Autowired
    DictionaryService dictionaryService;

    @PersistenceContext
    EntityManager em;

    @TempDir
    Path classPathRoot;

    /** The synthetic default-text bundle of the add-missing section, unique per test */
    private final String bundle = "it-defaults-" + System.nanoTime();

    /**
     * Persists and flushes a fresh, empty dictionary with the given unique name so that the
     * service's name-based lookups and cache can operate on it without touching seed data.
     */
    private Dictionary newDictionary(String name) {
        Dictionary dict = new Dictionary();
        dict.setName(name);
        em.persist(dict);
        em.flush();
        return dict;
    }

    /** Builds a detached entry template with a single localized value. */
    private DictionaryEntry newEntry(String key, String lang, String value) {
        DictionaryEntry entry = new DictionaryEntry();
        entry.setKey(key);
        DictionaryEntryDesc desc = entry.createDesc(lang);
        desc.setValue(value);
        return entry;
    }

    /**
     * Looks up the generated id of a persisted dictionary entry by dictionary name and key.
     *
     * <p>Resolves the id independently of the value returned by {@code createEntry}, from the managed
     * entry reachable via {@code findByName} after flushing pending inserts to the DB. (Since DR1,
     * {@code createEntry} also assigns the generated id onto the returned instance — see
     * {@link #createEntryPersistsEntryAndDescAndReturnsManagedEntity} — but this helper keeps the DB
     * as the source of truth for the id used by callers that do not capture the return value.)
     */
    private Integer persistedEntryId(String name, String key) {
        em.flush();
        DictionaryEntry persisted = dictionaryService.findByName(name).getEntries().get(key);
        assertNotNull(persisted, "the entry must be reachable from the persisted dictionary");
        Integer id = persisted.getId();
        assertNotNull(id, "the persisted entry must have a generated id");
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // findByName / getDictionaryNames (stable seed reads)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByNameReturnsSeededWebDictionary() {
        Dictionary web = dictionaryService.findByName("web");
        assertNotNull(web, "the seeded 'web' dictionary must exist");
        assertEquals("web", web.getName());
        assertFalse(web.getEntries().isEmpty(), "the seeded 'web' dictionary must have entries");
    }

    @Test
    @Transactional
    void findByNameReturnsNullForUnknownDictionary() {
        assertNull(dictionaryService.findByName("it-test-dict-no-such-name"));
    }

    @Test
    @Transactional
    void getDictionaryNamesIsSortedAndContainsSeededNames() {
        List<String> names = dictionaryService.getDictionaryNames();
        assertTrue(names.contains("web"), "seeded dictionaries must be listed");
        assertTrue(names.contains("message"), "seeded dictionaries must be listed");

        // Verify the documented sorted order.
        List<String> sorted = names.stream().sorted().collect(java.util.stream.Collectors.toList());
        assertEquals(sorted, names, "getDictionaryNames must return names in sorted order");
    }

    // ------------------------------------------------------------------------------------------- //
    // createEntry
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createEntryPersistsEntryAndDescAndReturnsManagedEntity() {
        Dictionary dict = newDictionary("it-test-dict-create");

        DictionaryEntry returned = dictionaryService.createEntry(
                "it-test-dict-create", newEntry("it.key.create", "en", "Created value"));

        // DR1: createEntry now assigns the generated id onto the returned instance.
        assertNotNull(returned.getId(), "createEntry must return an entry carrying its generated id");

        // The generated id is also independently resolvable from the DB, and must match.
        Integer id = persistedEntryId("it-test-dict-create", "it.key.create");
        assertEquals(returned.getId(), id, "returned id must match the persisted row id");
        em.clear();

        DictionaryEntry reloaded = em.find(DictionaryEntry.class, id);
        assertNotNull(reloaded, "the entry row must exist in the database");
        assertEquals("it.key.create", reloaded.getKey());
        assertEquals(dict.getId(), reloaded.getDictionary().getId());
        assertEquals("Created value", reloaded.getDesc("en").getValue());
    }

    @Test
    @Transactional
    void createEntryIsVisibleViaCachedDictionaryAndValue() {
        newDictionary("it-test-dict-cache");
        dictionaryService.createEntry(
                "it-test-dict-cache", newEntry("it.key.cache", "en", "Cached value"));
        em.flush();

        // createEntry invalidates the cache, so getCachedDictionary rebuilds it from the DB.
        DictionaryVo vo = dictionaryService.getCachedDictionary("it-test-dict-cache");
        assertNotNull(vo);
        assertTrue(vo.getEntries().containsKey("it.key.cache"));
        assertEquals("Cached value", dictionaryService.value("it-test-dict-cache", "en", "it.key.cache"));
    }

    @Test
    @Transactional
    void createEntryPersistsMultipleLanguageDescs() {
        newDictionary("it-test-dict-multilang");

        DictionaryEntry entry = newEntry("it.key.multilang", "en", "Hello");
        entry.createDesc("da").setValue("Hej");

        dictionaryService.createEntry("it-test-dict-multilang", entry);
        Integer id = persistedEntryId("it-test-dict-multilang", "it.key.multilang");

        em.clear();

        DictionaryEntry reloaded = em.find(DictionaryEntry.class, id);
        assertEquals("Hello", reloaded.getDesc("en").getValue());
        assertEquals("Hej", reloaded.getDesc("da").getValue());
    }

    @Test
    @Transactional
    void createEntryDropsBlankDescsButKeepsDefinedOnes() {
        newDictionary("it-test-dict-blankdesc");

        DictionaryEntry entry = newEntry("it.key.blankdesc", "en", "Defined");
        entry.createDesc("da").setValue("");   // blank -> must be dropped before persist

        dictionaryService.createEntry("it-test-dict-blankdesc", entry);
        Integer id = persistedEntryId("it-test-dict-blankdesc", "it.key.blankdesc");

        em.clear();

        DictionaryEntry reloaded = em.find(DictionaryEntry.class, id);
        assertEquals(1, reloaded.getDescs().size(), "blank descriptions must be removed");
        assertEquals("Defined", reloaded.getDesc("en").getValue());
        assertNull(reloaded.getDesc("da"), "the blank 'da' description must not be persisted");
    }

    @Test
    @Transactional
    void createEntryRejectsUnknownDictionary() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.createEntry(
                        "it-test-dict-does-not-exist", newEntry("it.key", "en", "v")));
        assertTrue(ex.getMessage().contains("Non-existing dictionary"));
    }

    @Test
    @Transactional
    void createEntryRejectsDuplicateKey() {
        newDictionary("it-test-dict-dupkey");
        dictionaryService.createEntry("it-test-dict-dupkey", newEntry("it.key.dup", "en", "First"));
        em.flush();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.createEntry(
                        "it-test-dict-dupkey", newEntry("it.key.dup", "en", "Second")));
        assertTrue(ex.getMessage().contains("Entry already exists"));
    }

    /**
     * DICT5: whether the key exists is read from the database, after the dictionary's lock, and not from the
     * dictionary's entries as this persistence context loaded them. Here a row of the key is stored that the loaded
     * map does not hold (as a row another transaction committed would be): the create is refused, and no second row
     * is stored.
     */
    @Test
    @Transactional
    void createEntryRefusesAKeyTheDatabaseHoldsThoughTheLoadedDictionaryLacksIt() {
        Dictionary dict = newDictionary("it-test-dict-dbkey");
        assertTrue(dictionaryService.findByName("it-test-dict-dbkey").getEntries().isEmpty(), "loaded empty");
        DictionaryEntry stored = newEntry("it.key.db", "en", "Stored");
        stored.setDictionary(dict);
        stored.getDescs().forEach(desc -> desc.setEntity(stored));
        em.persist(stored);
        em.flush();
        assertFalse(dict.getEntries().containsKey("it.key.db"), "the loaded map does not hold the stored row");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.createEntry("it-test-dict-dbkey", newEntry("it.key.db", "en", "Second")));
        assertEquals("Entry already exists: it.key.db", ex.getMessage());
        em.flush();
        assertEquals(1L, em.createQuery("select count(e) from DictionaryEntry e where e.dictionary.name = :name "
                        + "and e.key = :key", Long.class)
                .setParameter("name", "it-test-dict-dbkey").setParameter("key", "it.key.db")
                .getSingleResult(), "one row for the key");
    }

    /**
     * DICT6: a value given by its key alone is found in the one dictionary that holds the key; a key that two
     * dictionaries hold is refused with a sentence naming them (400 over REST), where the first row found used to be
     * taken, whichever dictionary it was in. A value given by its id is found by its id.
     */
    @Test
    @Transactional
    void persistedListRefusesAKeyTwoDictionariesHoldAndFindsOneByIdOrInItsOnlyDictionary() {
        Dictionary other = newDictionary("it-test-dict6-other");
        Dictionary own = newDictionary("it-test-dict6-own");
        DictionaryEntry otherEntry = dictionaryService.createEntry(other.getName(), newEntry("it.dict6.shared", "en", "O"));
        DictionaryEntry ownEntry = dictionaryService.createEntry(own.getName(), newEntry("it.dict6.shared", "en", "M"));
        DictionaryEntry single = dictionaryService.createEntry(own.getName(), newEntry("it.dict6.single", "en", "S"));
        em.flush();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.persistedList(List.of(byKey("it.dict6.shared"))));
        assertEquals("The dictionary key it.dict6.shared is in more than one dictionary (it-test-dict6-other, "
                + "it-test-dict6-own); give the value's id to say which", ex.getMessage());

        DictionaryEntry byId = byKey("it.dict6.shared");
        byId.setId(ownEntry.getId());
        assertEquals(List.of(ownEntry.getId(), single.getId()),
                dictionaryService.persistedList(List.of(byId, byKey("it.dict6.single"))).stream()
                        .map(DictionaryEntry::getId).toList());
        assertTrue(dictionaryService.persistedList(List.of(byKey("it.dict6.none"))).isEmpty(),
                "a key no dictionary holds is dropped, as before");
        DictionaryEntry otherById = byKey("it.dict6.shared");
        otherById.setId(otherEntry.getId());
        assertEquals(List.of(otherEntry.getId()), dictionaryService.persistedList(List.of(otherById)).stream()
                .map(DictionaryEntry::getId).toList(), "either dictionary's entry, by its id");
    }

    /**
     * DICT5 and DICT6: a deployment can already hold one key twice in one dictionary (there is no unique key on the
     * dictionary and the key). A value given by that key alone is one dictionary's, so it is found, not refused: the
     * row of the lowest id, the same row every time.
     */
    @Test
    @Transactional
    void persistedListFindsAKeyOneDictionaryHoldsTwiceAsItsLowestRow() {
        Dictionary dict = newDictionary("it-test-dict6-twice");
        DictionaryEntry first = storedRow(dict, "it.dict6.twice", "First");
        DictionaryEntry second = storedRow(dict, "it.dict6.twice", "Second");
        em.flush();
        Integer lowest = Math.min(first.getId(), second.getId());

        assertEquals(List.of(lowest), dictionaryService.persistedList(List.of(byKey("it.dict6.twice"))).stream()
                .map(DictionaryEntry::getId).toList(), "the one dictionary's row of the lowest id");
        assertEquals(List.of(lowest), dictionaryService.persistedList(List.of(byKey("it.dict6.twice")),
                        List.of(first, second)).stream().map(DictionaryEntry::getId).toList(),
                "the same row when the stored values hold both rows");
    }

    /** Stores a row of the key in the dictionary directly, past createEntry, as an old deployment may hold it */
    private DictionaryEntry storedRow(Dictionary dict, String key, String value) {
        DictionaryEntry row = newEntry(key, "en", value);
        row.setDictionary(dict);
        row.getDescs().forEach(desc -> desc.setEntity(row));
        em.persist(row);
        return row;
    }

    /** A value as a request names it by its key alone */
    private static DictionaryEntry byKey(String key) {
        DictionaryEntry entry = new DictionaryEntry();
        entry.setKey(key);
        return entry;
    }

    @Test
    @Transactional
    void createEntryRejectsEntryWithoutLocalizedValues() {
        newDictionary("it-test-dict-novalues");

        DictionaryEntry entry = new DictionaryEntry();
        entry.setKey("it.key.novalues");
        // No descs at all -> "No localized values defined"
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.createEntry("it-test-dict-novalues", entry));
        assertTrue(ex.getMessage().contains("No localized values defined"));
    }

    @Test
    @Transactional
    void createEntryRejectsEntryWithOnlyBlankValues() {
        newDictionary("it-test-dict-onlyblank");

        DictionaryEntry entry = new DictionaryEntry();
        entry.setKey("it.key.onlyblank");
        entry.createDesc("en").setValue("");   // blank -> removed -> no values left
        entry.createDesc("da").setValue(null); // null -> removed -> no values left

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.createEntry("it-test-dict-onlyblank", entry));
        assertTrue(ex.getMessage().contains("No localized values defined"));
    }

    @Test
    @Transactional
    void createEntryPersistsAtonFilter() {
        newDictionary("it-test-dict-aton");

        DictionaryEntry entry = newEntry("it.key.aton", "en", "Buoy");
        entry.setAtonFilter("aton.tags['seamark:type'] == 'buoy_lateral'");

        dictionaryService.createEntry("it-test-dict-aton", entry);
        Integer id = persistedEntryId("it-test-dict-aton", "it.key.aton");

        em.clear();

        assertEquals("aton.tags['seamark:type'] == 'buoy_lateral'",
                em.find(DictionaryEntry.class, id).getAtonFilter());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateEntry
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateEntryPersistsChangedValueAndKeepsSameRow() {
        newDictionary("it-test-dict-update");
        dictionaryService.createEntry(
                "it-test-dict-update", newEntry("it.key.update", "en", "Original"));
        // The create-returned template has no id; resolve the persisted row's generated id.
        Integer id = persistedEntryId("it-test-dict-update", "it.key.update");

        DictionaryEntry updated = dictionaryService.updateEntry(
                "it-test-dict-update", newEntry("it.key.update", "en", "Changed"));
        assertEquals(id, updated.getId(), "updateEntry must update the existing row, not create a new one");

        em.flush();
        em.clear();

        assertEquals("Changed", em.find(DictionaryEntry.class, id).getDesc("en").getValue());
    }

    @Test
    @Transactional
    void updateEntryAddsNewLanguageWhileKeepingExistingOnes() {
        newDictionary("it-test-dict-addlang");
        dictionaryService.createEntry(
                "it-test-dict-addlang", newEntry("it.key.addlang", "en", "Hello"));
        Integer id = persistedEntryId("it-test-dict-addlang", "it.key.addlang");

        DictionaryEntry template = newEntry("it.key.addlang", "en", "Hello");
        template.createDesc("da").setValue("Hej");
        dictionaryService.updateEntry("it-test-dict-addlang", template);

        em.flush();
        em.clear();

        DictionaryEntry reloaded = em.find(DictionaryEntry.class, id);
        assertEquals("Hello", reloaded.getDesc("en").getValue());
        assertEquals("Hej", reloaded.getDesc("da").getValue());
    }

    @Test
    @Transactional
    void updateEntryRemovesDescBlankedOut() {
        newDictionary("it-test-dict-removelang");
        DictionaryEntry entry = newEntry("it.key.removelang", "en", "Hello");
        entry.createDesc("da").setValue("Hej");
        dictionaryService.createEntry("it-test-dict-removelang", entry);
        Integer id = persistedEntryId("it-test-dict-removelang", "it.key.removelang");

        DictionaryEntry template = newEntry("it.key.removelang", "en", "Hello");
        template.createDesc("da").setValue("");  // blank -> da desc must be removed
        dictionaryService.updateEntry("it-test-dict-removelang", template);

        em.flush();
        em.clear();

        DictionaryEntry reloaded = em.find(DictionaryEntry.class, id);
        assertEquals("Hello", reloaded.getDesc("en").getValue());
        assertNull(reloaded.getDesc("da"), "the blanked-out description must be removed");
    }

    /**
     * An edit on the Dictionaries page may blank every value of an entry (the page warns, and saves): the entry
     * stays, with no text in any language. Its dictionary is still read as the resource bundle a template and the
     * audio text load, and the entry contributes no value to it.
     */
    @Test
    @Transactional
    void anEntryEditedToHaveNoTextLeavesItsDictionaryReadable() {
        String name = "it-test-dict-notext";
        newDictionary(name);
        DictionaryEntry entry = newEntry("it.key.notext", "en", "Hello");
        entry.createDesc("da").setValue("Hej");
        dictionaryService.createEntry(name, entry);
        dictionaryService.createEntry(name, newEntry("it.key.kept", "en", "Kept"));
        Integer id = persistedEntryId(name, "it.key.notext");

        DictionaryEntry template = newEntry("it.key.notext", "en", "");
        template.createDesc("da").setValue("");
        dictionaryService.updateEntry(name, template);
        em.flush();
        em.clear();

        assertTrue(em.find(DictionaryEntry.class, id).getDescs().isEmpty(), "the entry is kept without a text");
        ResourceBundle bundle = dictionaryService.getDictionariesAsResourceBundle(new String[] { name }, "da");
        assertFalse(bundle.containsKey("it.key.notext"));
        assertEquals("Kept", bundle.getString("it.key.kept"));
        assertNull(dictionaryService.value(name, "da", "it.key.notext"));
    }

    /**
     * Stores an entry whose English value is empty on purpose, as the bootstrap texts store
     * {@code navwarn.date.time_interval_prefix}: created with a placeholder, then emptied on the managed row
     * (createEntry and the import both refuse to add an empty value).
     */
    private Integer entryWithAnEmptyEnglishValue(String name, String key, String danish) {
        newDictionary(name);
        DictionaryEntry entry = newEntry(key, "en", "placeholder");
        if (danish != null) {
            entry.createDesc("da").setValue(danish);
        }
        dictionaryService.createEntry(name, entry);
        Integer id = persistedEntryId(name, key);
        em.find(DictionaryEntry.class, id).getDesc("en").setValue("");
        em.flush();
        em.clear();
        return id;
    }

    /**
     * R1-1 (DICT4): a save from the admin page sends every language, the empty English one as "". The stored empty
     * English value is that language's text on purpose; the save must keep it, or the English text falls back to
     * the Danish "kl." and start-up never adds the row again (the ledger has it).
     */
    @Test
    @Transactional
    void updateEntryKeepsAStoredEmptyValueTheRequestLeavesEmpty() {
        String name = "it-test-dict-emptyonpurpose";
        String key = "it.key.time_interval_prefix";
        Integer id = entryWithAnEmptyEnglishValue(name, key, "kl.");

        DictionaryEntry template = newEntry(key, "en", "");
        template.createDesc("da").setValue("kl");
        template.setAtonFilter("aton.tags['seamark:type'] == 'light_major'");
        dictionaryService.updateEntry(name, template);

        em.flush();
        em.clear();

        DictionaryEntry reloaded = em.find(DictionaryEntry.class, id);
        assertNotNull(reloaded.getDesc("en"), "the stored empty English value must survive the save");
        assertEquals("", reloaded.getDesc("en").getValue());
        assertEquals("kl", reloaded.getDesc("da").getValue());
        assertEquals("", dictionaryService.value(name, "en", key),
                "the English text must stay empty, not fall back to the Danish one");

        // A request that sends null for the language still clears it
        DictionaryEntry clearing = newEntry(key, "en", null);
        clearing.createDesc("da").setValue("kl");
        dictionaryService.updateEntry(name, clearing);
        em.flush();
        em.clear();
        assertNull(em.find(DictionaryEntry.class, id).getDesc("en"));
    }

    /**
     * R1-1 (DICT4), English-only: the entry's only description is the empty English one. A save of the AtoN filter
     * must leave it, or the key leaves the template texts and every template that prints a within-day interval fails.
     */
    @Test
    @Transactional
    void updateEntryKeepsTheOnlyStoredEmptyValue() {
        String name = "it-test-dict-emptyonly";
        String key = "it.key.time_interval_prefix";
        Integer id = entryWithAnEmptyEnglishValue(name, key, null);

        DictionaryEntry template = newEntry(key, "en", "");
        template.setAtonFilter("aton.tags['seamark:type'] == 'light_minor'");
        dictionaryService.updateEntry(name, template);

        em.flush();
        em.clear();

        DictionaryEntry reloaded = em.find(DictionaryEntry.class, id);
        assertEquals(1, reloaded.getDescs().size());
        assertEquals("", reloaded.getDesc("en").getValue());
        assertEquals("", dictionaryService.getDictionariesAsProperties(new String[] { name }, "en").getProperty(key),
                "the key must stay in the texts the templates get");
    }

    /** R1-1: a language the entry has no row for, sent empty by the admin page's padded form, is still not stored. */
    @Test
    @Transactional
    void updateEntryStoresNoNewEmptyValue() {
        newDictionary("it-test-dict-nonewempty");
        dictionaryService.createEntry("it-test-dict-nonewempty", newEntry("it.key.nonewempty", "en", "Hello"));
        Integer id = persistedEntryId("it-test-dict-nonewempty", "it.key.nonewempty");

        DictionaryEntry template = newEntry("it.key.nonewempty", "en", "Hello");
        template.createDesc("da").setValue("");
        dictionaryService.updateEntry("it-test-dict-nonewempty", template);

        em.flush();
        em.clear();

        assertNull(em.find(DictionaryEntry.class, id).getDesc("da"));
    }

    @Test
    @Transactional
    void updateEntryUpdatesAtonFilter() {
        newDictionary("it-test-dict-updaton");
        dictionaryService.createEntry(
                "it-test-dict-updaton", newEntry("it.key.updaton", "en", "v"));
        Integer id = persistedEntryId("it-test-dict-updaton", "it.key.updaton");

        DictionaryEntry template = newEntry("it.key.updaton", "en", "v");
        template.setAtonFilter("aton.tags['seamark:type'] == 'light_major'");
        dictionaryService.updateEntry("it-test-dict-updaton", template);

        em.flush();
        em.clear();

        assertEquals("aton.tags['seamark:type'] == 'light_major'",
                em.find(DictionaryEntry.class, id).getAtonFilter());
    }

    @Test
    @Transactional
    void updateEntryReflectedInCachedDictionary() {
        newDictionary("it-test-dict-updcache");
        dictionaryService.createEntry(
                "it-test-dict-updcache", newEntry("it.key.updcache", "en", "Before"));
        em.flush();

        // Prime the cache with the pre-update value.
        assertEquals("Before",
                dictionaryService.value("it-test-dict-updcache", "en", "it.key.updcache"));

        dictionaryService.updateEntry(
                "it-test-dict-updcache", newEntry("it.key.updcache", "en", "After"));
        em.flush();

        // updateEntry invalidates the cache, so the new value must be visible.
        assertEquals("After",
                dictionaryService.value("it-test-dict-updcache", "en", "it.key.updcache"));
    }

    @Test
    @Transactional
    void updateEntryRejectsUnknownDictionary() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.updateEntry(
                        "it-test-dict-missing", newEntry("it.key", "en", "v")));
        assertTrue(ex.getMessage().contains("Non-existing dictionary"));
    }

    @Test
    @Transactional
    void updateEntryRejectsUnknownKey() {
        newDictionary("it-test-dict-updmissing");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.updateEntry(
                        "it-test-dict-updmissing", newEntry("it.key.unknown", "en", "v")));
        assertTrue(ex.getMessage().contains("Non-existing dictionary entry"));
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteEntry
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteEntryRemovesRowAndReturnsTrue() {
        newDictionary("it-test-dict-delete");
        dictionaryService.createEntry(
                "it-test-dict-delete", newEntry("it.key.delete", "en", "v"));
        Integer id = persistedEntryId("it-test-dict-delete", "it.key.delete");

        assertTrue(dictionaryService.deleteEntry("it-test-dict-delete", "it.key.delete"));

        em.flush();
        em.clear();

        assertNull(em.find(DictionaryEntry.class, id), "the deleted entry row must be gone from the DB");
    }

    @Test
    @Transactional
    void deleteEntryRemovesEntryFromCachedDictionary() {
        newDictionary("it-test-dict-delcache");
        dictionaryService.createEntry(
                "it-test-dict-delcache", newEntry("it.key.delcache", "en", "v"));
        em.flush();

        // Prime the cache so the entry is present before deletion.
        assertNotNull(dictionaryService.value("it-test-dict-delcache", "en", "it.key.delcache"));

        dictionaryService.deleteEntry("it-test-dict-delcache", "it.key.delcache");
        em.flush();

        // deleteEntry invalidates the cache, so the entry must no longer resolve.
        assertNull(dictionaryService.value("it-test-dict-delcache", "en", "it.key.delcache"));
    }

    @Test
    @Transactional
    void deleteEntryReturnsFalseForUnknownKey() {
        newDictionary("it-test-dict-delmissing");
        assertFalse(dictionaryService.deleteEntry("it-test-dict-delmissing", "it.key.unknown"));
    }

    @Test
    @Transactional
    void deleteEntryRejectsUnknownDictionary() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.deleteEntry("it-test-dict-deldict", "it.key"));
        assertTrue(ex.getMessage().contains("Non-existing dictionary"));
    }

    // ------------------------------------------------------------------------------------------- //
    // getCachedDictionary / value
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getCachedDictionaryReturnsNullForUnknownName() {
        assertNull(dictionaryService.getCachedDictionary("it-test-dict-unknown-cache"));
    }

    @Test
    @Transactional
    void valueReturnsNullForUnknownDictionary() {
        assertNull(dictionaryService.value("it-test-dict-unknown-value", "en", "any.key"));
    }

    @Test
    @Transactional
    void valueReturnsNullForUnknownKeyInExistingDictionary() {
        newDictionary("it-test-dict-valuekey");
        dictionaryService.createEntry(
                "it-test-dict-valuekey", newEntry("it.key.present", "en", "v"));
        em.flush();

        assertNull(dictionaryService.value("it-test-dict-valuekey", "en", "it.key.absent"),
                "an unknown key must resolve to null");
    }

    @Test
    @Transactional
    void valueFallsBackToAnExistingValueForUnknownLanguage() {
        newDictionary("it-test-dict-valuelang");
        dictionaryService.createEntry(
                "it-test-dict-valuelang", newEntry("it.key.lang", "en", "English only"));
        em.flush();

        assertEquals("English only",
                dictionaryService.value("it-test-dict-valuelang", "en", "it.key.lang"));
        // A missing translation falls back to the default language and then to the first non-blank
        // value (the precedence itself is covered by DictionaryVoTest); only an unknown key or
        // dictionary resolves to null.
        assertEquals("English only",
                dictionaryService.value("it-test-dict-valuelang", "fr", "it.key.lang"),
                "a language without a value must fall back to an existing value");
    }

    // ------------------------------------------------------------------------------------------- //
    // createEntry - client-supplied id (the root cause: MASSASSIGN-1 in be/docs/potential-errors.md)
    // ------------------------------------------------------------------------------------------- //

    /**
     * A create body must not carry an id. {@code DictionaryEntry(DictionaryEntryVo)} copies the id straight
     * off the request body and {@code saveEntity()} merges a persisted entity, so before the guard a
     * "create" in one dictionary silently overwrote an entry of another dictionary - the duplicate-key
     * check only looks inside the target dictionary and never noticed.
     */
    @Test
    @Transactional
    void createEntryRejectsEntryWithExistingId() {
        Dictionary victimDict = newDictionary("it-test-dict-victim-" + System.nanoTime());
        dictionaryService.createEntry(victimDict.getName(), newEntry("victim.key", "en", "victim value"));
        Integer victimId = persistedEntryId(victimDict.getName(), "victim.key");

        Dictionary otherDict = newDictionary("it-test-dict-other-" + System.nanoTime());
        DictionaryEntry forged = newEntry("forged.key", "en", "hijacked");
        forged.setId(victimId);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dictionaryService.createEntry(otherDict.getName(), forged));
        assertTrue(ex.getMessage().contains("existing ID"),
                "the rejection must mention the existing id");
    }

    // ------------------------------------------------------------------------------------------- //
    // addMissingDefaults (the start-up step, driven with a synthetic bundle)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void addMissingDefaultsAddsAMissingKeyWithItsLedgerRow() throws IOException {
        DefaultTexts.Layered layered = layered("greeting = Hello\n", "greeting = Hej\n");

        assertEquals(2, dictionaryService.addMissingDefaults(layered));

        assertEquals("Hello", dictionaryService.value(bundle, "en", "greeting"));
        assertEquals("Hej", dictionaryService.value(bundle, "da", "greeting"));
        List<Object[]> rows = ledgerRows();
        assertEquals(2, rows.size());
        assertLedgerRow(rows.get(0), "greeting", "da", DefaultTexts.SOURCE_PACK_PREFIX + "da",
                DefaultTexts.sha256("Hej"));
        assertLedgerRow(rows.get(1), "greeting", "en", DefaultTexts.SOURCE_BASE, DefaultTexts.sha256("Hello"));
    }

    @Test
    @Transactional
    void addMissingDefaultsNeverChangesAnExistingValue() throws IOException {
        newDictionary(bundle);
        dictionaryService.createEntry(bundle, newEntry("greeting", "en", "An operator's greeting"));
        em.flush();

        assertEquals(0, dictionaryService.addMissingDefaults(layered("greeting = Hello\n", null)));

        em.clear();
        assertEquals("An operator's greeting", dictionaryService.findByName(bundle).getEntries().get("greeting")
                .getDesc("en").getValue());
        List<Object[]> rows = ledgerRows();
        assertEquals(1, rows.size());
        assertLedgerRow(rows.get(0), "greeting", "en", DefaultsLedger.SOURCE_ADOPTED, null);
    }

    @Test
    @Transactional
    void aSecondRunAddsNothingAndWritesNoLedgerRow() throws IOException {
        DefaultTexts.Layered layered = layered("greeting = Hello\nfarewell = Bye\n", null);
        assertEquals(2, dictionaryService.addMissingDefaults(layered));
        long descs = countDescs();

        assertEquals(0, dictionaryService.addMissingDefaults(layered));

        assertEquals(descs, countDescs());
        assertEquals(2, ledgerRows().size());
    }

    @Test
    @Transactional
    void deleteEntryRecordsTheKeyAsDeletedInEveryLanguage() throws IOException {
        DefaultTexts.Layered layered = layered("greeting = Hello\n", "greeting = Hej\n");
        dictionaryService.addMissingDefaults(layered);
        // A delete comes in a later request, with the dictionary read afresh
        em.flush();
        em.clear();

        assertTrue(dictionaryService.deleteEntry(bundle, "greeting"));
        em.flush();

        List<Object[]> rows = ledgerRows();
        assertEquals(3, rows.size());
        assertLedgerRow(rows.get(0), "greeting", DefaultsLedger.LANG_DELETED, DefaultsLedger.SOURCE_DELETED, null);
        assertEquals(0, dictionaryService.addMissingDefaults(layered), "a deleted key is not added again");

        // The '*' row is written on the transaction's own connection, so a rolled-back delete leaves none
        TestTransaction.flagForRollback();
        TestTransaction.end();
        long[] counts = Tx.requiringNew().call(() -> new long[] {
                ((Number) em.createNativeQuery("SELECT count(*) FROM public.\"DefaultsLedger\" "
                                + "WHERE dictionary = :name AND lang = :lang")
                        .setParameter("name", bundle)
                        .setParameter("lang", DefaultsLedger.LANG_DELETED)
                        .getSingleResult()).longValue(),
                ((Number) em.createNativeQuery("SELECT count(*) FROM public.\"DictionaryEntry\" e "
                                + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id WHERE d.name = :name")
                        .setParameter("name", bundle)
                        .getSingleResult()).longValue()});
        assertEquals(0, counts[0], "the rolled-back delete left no '*' row");
        assertEquals(0, counts[1], "the rolled-back transaction left no entry");
    }

    // ------------------------------------------------------------------------------------------- //
    // asAdded (committed: the ledger's in-memory hashes take rows only once they commit)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anAddedTextIsAsAddedOnceTheAddHasCommitted() throws IOException {
        DefaultTexts.Layered layered = layered("greeting = Hello\n", "greeting = Hej\n");

        Tx.requiringNew().run(() -> {
            dictionaryService.addMissingDefaults(layered);
            // Cached inside the add's transaction, as init() does, before the ledger's hashes take the new rows
            assertNull(asAdded("greeting", "en"));
        });

        assertEquals(Boolean.TRUE, asAdded("greeting", "en"), "the commit evicted the cached dictionary");
        assertEquals(Boolean.TRUE, asAdded("greeting", "da"));
    }

    @Test
    void anEditedTextIsNotAsAddedUntilItIsEditedBackToTheDefault() throws IOException {
        DefaultTexts.Layered layered = layered("greeting = Hello\n", null);
        Tx.requiringNew().run(() -> dictionaryService.addMissingDefaults(layered));

        Tx.requiringNew().run(() -> dictionaryService.updateEntry(bundle, newEntry("greeting", "en", "Hi there")));
        assertNull(asAdded("greeting", "en"));

        Tx.requiringNew().run(() -> dictionaryService.updateEntry(bundle, newEntry("greeting", "en", "Hello")));
        assertEquals(Boolean.TRUE, asAdded("greeting", "en"));
    }

    @Test
    void anAdoptedTextIsAsAddedOnlyWhileItEqualsTheDefault() throws IOException {
        Tx.requiringNew().run(() -> {
            newDictionary(bundle);
            dictionaryService.createEntry(bundle, newEntry("same", "en", "Hello"));
            dictionaryService.createEntry(bundle, newEntry("differs", "en", "An operator's text"));
        });

        DefaultTexts.Layered layered = layered("same = Hello\ndiffers = The default text\n", null);
        Tx.requiringNew().run(() -> assertEquals(0, dictionaryService.addMissingDefaults(layered)));

        assertEquals(Boolean.TRUE, asAdded("same", "en"));
        assertNull(asAdded("differs", "en"));
    }

    @Test
    void anOperatorsTextIsNotAsAdded() {
        Tx.requiringNew().run(() -> {
            newDictionary(bundle);
            dictionaryService.createEntry(bundle, newEntry("own", "en", "An operator's own text"));
        });

        assertNull(asAdded("own", "en"), "a text with no ledger row");
    }

    @Test
    void aDeletedTextCreatedAgainWithTheDefaultValueIsNotAsAdded() throws IOException {
        DefaultTexts.Layered layered = layered("greeting = Hello\n", null);
        Tx.requiringNew().run(() -> dictionaryService.addMissingDefaults(layered));
        Tx.requiringNew().run(() -> dictionaryService.deleteEntry(bundle, "greeting"));

        Tx.requiringNew().run(() -> dictionaryService.createEntry(bundle, newEntry("greeting", "en", "Hello")));

        assertNull(asAdded("greeting", "en"), "an operator wrote it after the delete");
    }

    /** The asAdded mark of the cached translation */
    private Boolean asAdded(String key, String lang) {
        DictionaryVo dict = dictionaryService.getCachedDictionary(bundle);
        assertNotNull(dict);
        return dict.getEntries().get(key).getDesc(lang).getAsAdded();
    }

    // ------------------------------------------------------------------------------------------- //
    // keyUsers (committed script resources)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void keyUsersListsTheScriptResourcesThatNameTheKeyInEitherQuote() {
        String key = "it.key-users." + bundle;
        scriptResource("single.ftl", "${text('" + key + "')}");
        scriptResource("double.ftl", "<#assign t = text(\"" + key + "\", 1)>");
        scriptResource("longer.ftl", "${text('" + key + ".longer')}");
        scriptResource("unquoted.ftl", "${" + key + "}");

        DictionaryKeyUsersVo users = dictionaryService.keyUsers("web", key);

        assertEquals(List.of(scriptPath("double.ftl"), scriptPath("single.ftl")), fixtures(users));
        assertNull(users.getPrefix());
        assertEquals(List.of(), users.getReaders());
    }

    @Test
    void keyUsersDoesNotMatchAKeyThatTheKeyStarts() {
        scriptResource("time.ftl", "${text('msg.time.time_format')}");

        assertEquals(List.of(), fixtures(dictionaryService.keyUsers("message", "msg.time")));
        assertEquals(List.of(scriptPath("time.ftl")),
                fixtures(dictionaryService.keyUsers("message", "msg.time.time_format")));
    }

    @Test
    void keyUsersTreatsAnUnderscoreOrAPercentInTheKeyAsItself() {
        String id = Long.toString(System.nanoTime());
        scriptResource("wildcard.ftl", "${text('it.aXb" + id + "')} ${text('it.cXXd" + id + "')}");
        scriptResource("literal.ftl", "${text('it.a_b" + id + "')} ${text('it.c%d" + id + "')}");

        assertEquals(List.of(scriptPath("literal.ftl")), fixtures(dictionaryService.keyUsers("web", "it.a_b" + id)));
        assertEquals(List.of(scriptPath("literal.ftl")), fixtures(dictionaryService.keyUsers("web", "it.c%d" + id)));
    }

    @Test
    void keyUsersMatchesTheRuntimePrefixOfAKeyNoTemplateNamesInFull() {
        scriptResource("status.ftl", "<span>${text(\"msg.status.\" + msg.status)}</span>");

        DictionaryKeyUsersVo users = dictionaryService.keyUsers("message", "msg.status.DRAFT");

        assertEquals(List.of(scriptPath("status.ftl")), fixtures(users));
        assertEquals("msg.status.", users.getPrefix());
    }

    @Test
    void keyUsersNamesTheJavaReadersOfTheKeyInItsDictionaryOnly() {
        assertEquals(List.of(RequiredTexts.Reader.AUDIO_TEXT.name(), RequiredTexts.Reader.LAT_LON_DIRECTIVE.name()),
                dictionaryService.keyUsers("template", "position.deg").getReaders());
        assertEquals(List.of(), dictionaryService.keyUsers("web", "position.deg").getReaders());
    }

    /** The path of a script resource fixture of this test */
    private String scriptPath(String name) {
        return bundle + "/" + name;
    }

    /** Commits a script resource fixture */
    private void scriptResource(String name, String content) {
        Tx.requiringNew().run(() -> {
            ScriptResource resource = new ScriptResource();
            resource.setPath(scriptPath(name));
            resource.setContent(content);
            resource.updateType();
            em.persist(resource);
        });
    }

    /** The script resources of this test's fixtures that the users list */
    private List<String> fixtures(DictionaryKeyUsersVo users) {
        return users.getTemplates().stream().filter(p -> p.startsWith(bundle + "/")).toList();
    }

    /**
     * Commits the removal of the synthetic dictionary, its ledger rows and the script resource fixtures, after the
     * test transaction ended.
     */
    @AfterEach
    void removeSyntheticDictionaries() {
        if (TestTransaction.isActive()) {
            TestTransaction.end();
        }
        Tx.requiringNew().run(() -> em.createQuery("delete from ScriptResource s where s.path like :path")
                .setParameter("path", bundle + "/%").executeUpdate());
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

    /**
     * The layered defaults of the synthetic bundle in en (the base product's folder) and, when given, in da (the
     * language pack, {@code defaults/packs/da/}).
     */
    private DefaultTexts.Layered layered(String en, String da) throws IOException {
        write("defaults/base/texts/en/" + bundle + ".properties", en);
        String[] languages = {"en"};
        if (da != null) {
            write("defaults/packs/da/" + bundle + ".properties", da);
            languages = new String[] {"en", "da"};
        }
        try (URLClassLoader cl = new URLClassLoader(new URL[] {classPathRoot.toUri().toURL()}, null)) {
            return DefaultTexts.read(cl, new String[] {bundle}, languages, "en");
        }
    }

    private void write(String name, String content) throws IOException {
        Path file = classPathRoot.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** The synthetic bundle's ledger rows (key, lang, source, hash), ordered by key and language */
    @SuppressWarnings("unchecked")
    private List<Object[]> ledgerRows() {
        return em.createNativeQuery("SELECT \"key\", lang, source, hash FROM public.\"DefaultsLedger\" "
                        + "WHERE kind = 'TEXT' AND dictionary = :name ORDER BY \"key\", lang COLLATE \"C\"")
                .setParameter("name", bundle)
                .getResultList();
    }

    private long countDescs() {
        em.flush();
        return ((Number) em.createNativeQuery("SELECT count(*) FROM public.\"DictionaryEntryDesc\" t "
                        + "JOIN public.\"DictionaryEntry\" e ON t.entity_id = e.id "
                        + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id WHERE d.name = :name")
                .setParameter("name", bundle)
                .getSingleResult()).longValue();
    }

    private static void assertLedgerRow(Object[] row, String key, String lang, String source, String hash) {
        assertEquals(key, row[0]);
        assertEquals(lang, row[1]);
        assertEquals(source, row[2]);
        assertEquals(hash, row[3]);
    }
}
