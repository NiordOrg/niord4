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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defaults ledger (docs/code-data-config-plan.md §4.2, R-CDC5): what start-up added or found is recorded, and a
 * key or translation an operator removed is not added again, in any language, a language added later included.
 *
 * <p>Every step commits, as start-up and the Dictionaries page do; each test drives
 * {@link DictionaryService#addMissingDefaults} with its own synthetic bundle ({@code it-ledger-<nanoTime>}, read
 * from a {@code @TempDir} class-path root), and the committed {@link #removeSyntheticDictionaries()} deletes the
 * bundle's dictionary and ledger rows. "A restart" is {@code init()} (the real bundles, all recorded since the
 * context started, plus a cache rebuild) followed by the synthetic bundle's add-missing step.
 */
class DefaultsLedgerITTest extends ITTest {

    private static final Logger log = LoggerFactory.getLogger(DefaultsLedgerITTest.class);

    @Autowired
    DictionaryService dictionaryService;

    @Autowired
    DefaultsLedger defaultsLedger;

    @PersistenceContext
    EntityManager em;

    @TempDir
    Path classPathRoot;

    private final String bundle = "it-ledger-" + System.nanoTime();

    @Test
    void aDeletedKeyStaysDeletedAfterARestart() throws IOException {
        DefaultTexts.Layered layered = layered(new String[] {"en", "da"},
                "gone = Gone\nkept = Kept\n", "gone = Væk\n");
        assertEquals(3, dictionaryService.addMissingDefaults(layered));

        assertTrue(dictionaryService.deleteEntry(bundle, "gone"));
        restart(layered);

        assertEquals(Map.of("kept/en", "Kept"), descs());
        assertEquals(DefaultsLedger.SOURCE_DELETED, ledger().get("gone/*"));
    }

    @Test
    void aClearedTranslationIsNotAddedAgain() throws IOException {
        DefaultTexts.Layered layered = layered(new String[] {"en", "da"}, "greeting = Hello\n", "greeting = Hej\n");
        dictionaryService.addMissingDefaults(layered);

        DictionaryEntry cleared = new DictionaryEntry();
        cleared.setKey("greeting");
        cleared.createDesc("en").setValue("Hello");
        cleared.createDesc("da").setValue("");
        dictionaryService.updateEntry(bundle, cleared);

        assertEquals(0, dictionaryService.addMissingDefaults(layered));
        assertEquals(Map.of("greeting/en", "Hello"), descs());
    }

    @Test
    void anExistingDefaultIsAdoptedWithItsHashOnlyWhenItEqualsTheDefault() throws IOException {
        Tx.requiringNew().run(() -> {
            Dictionary dict = new Dictionary();
            dict.setName(bundle);
            dict.createEntry("same").createDesc("en").setValue("Same");
            dict.createEntry("edited").createDesc("en").setValue("An operator's wording");
            em.persist(dict);
        });
        Map<String, String> before = descs();

        assertEquals(0, dictionaryService.addMissingDefaults(
                layered(new String[] {"en"}, "same = Same\nedited = The default wording\n", null)));

        assertEquals(before, descs(), "adoption writes no dictionary row");
        List<Object[]> rows = ledgerRows();
        assertEquals(2, rows.size());
        assertRow(rows.get(0), "edited", "en", DefaultsLedger.SOURCE_ADOPTED, null);
        assertRow(rows.get(1), "same", "en", DefaultsLedger.SOURCE_ADOPTED, DefaultTexts.sha256("Same"));
    }

    @Test
    void aLanguageAddedLaterGetsItsKeys() throws IOException {
        dictionaryService.addMissingDefaults(layered(new String[] {"en"}, "greeting = Hello\n", null));

        assertEquals(1, dictionaryService.addMissingDefaults(
                layered(new String[] {"en", "kl"}, "greeting = Hello\n", "greeting = Aluu\n")));

        assertEquals(Map.of("greeting/en", "Hello", "greeting/kl", "Aluu"), descs());
        assertEquals(DefaultTexts.SOURCE_PACK_PREFIX + "kl", ledger().get("greeting/kl"));
    }

    @Test
    void aKeyDeletedBeforeALanguageIsAddedStaysAbsentInThatLanguage() throws IOException {
        dictionaryService.addMissingDefaults(layered(new String[] {"en"}, "greeting = Hello\n", null));
        assertTrue(dictionaryService.deleteEntry(bundle, "greeting"));

        assertEquals(0, dictionaryService.addMissingDefaults(
                layered(new String[] {"en", "kl"}, "greeting = Hello\n", "greeting = Aluu\n")));

        assertEquals(Map.of(), descs());
    }

    @Test
    void aKeyRemovedSomeOtherWayIsNotAddedAgain() throws IOException {
        DefaultTexts.Layered layered = layered(new String[] {"en"}, "greeting = Hello\n", null);
        dictionaryService.addMissingDefaults(layered);
        Tx.requiringNew().run(() -> {
            deleteDescsAndEntries();
        });

        restart(layered);

        assertEquals(Map.of(), descs());
        assertNull(ledger().get("greeting/*"), "nothing recorded a delete; the ledger's own row keeps the key out");
    }

    /** The first start with the ledger on a running deployment: its rows are adopted in one batch. */
    @Test
    void aThousandTriplesAreAdoptedInOneBatch() throws IOException {
        StringBuilder en = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            en.append("key.").append(i).append(" = Value ").append(i).append('\n');
        }
        DefaultTexts.Layered layered = layered(new String[] {"en"}, en.toString(), null);
        assertEquals(1000, dictionaryService.addMissingDefaults(layered));
        Tx.requiringNew().run(() -> em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE dictionary = :name")
                .setParameter("name", bundle).executeUpdate());

        long t0 = System.currentTimeMillis();
        assertEquals(0, dictionaryService.addMissingDefaults(layered));
        long ms = System.currentTimeMillis() - t0;
        log.info("Adopted 1000 default texts in {} ms", ms);

        List<Object[]> rows = ledgerRows();
        assertEquals(1000, rows.size());
        assertTrue(rows.stream().allMatch(r -> DefaultsLedger.SOURCE_ADOPTED.equals(r[2]) && r[3] != null),
                "every row is adopted with the hash of its unchanged value");
    }

    /** A setting row has no dictionary and no language, and sits beside a text row of the same key */
    @Test
    void aSettingRowHasNoDictionaryOrLanguageAndCoexistsWithATextRowOfItsKey() {
        String key = bundle + ".key";
        Tx.requiringNew().run(() -> defaultsLedger.insertAll(List.of(
                DefaultsLedger.Row.setting(key, "hash", DefaultsLedger.SOURCE_BASE),
                DefaultsLedger.Row.text(bundle, key, "en", null, DefaultTexts.SOURCE_BASE))));

        @SuppressWarnings("unchecked")
        List<Object[]> rows = Tx.requiringNew().call(() -> em.createNativeQuery(
                        "SELECT kind, dictionary, lang, source FROM public.\"DefaultsLedger\" WHERE \"key\" = :key "
                                + "ORDER BY kind")
                .setParameter("key", key)
                .getResultList());
        assertEquals(2, rows.size(), "a TEXT and a SETTING row of the same key");
        assertEquals(List.of("SETTING", "", "", DefaultsLedger.SOURCE_BASE), List.of(rows.get(0)));
        assertEquals("TEXT", rows.get(1)[0]);

        Set<String> settingKeys = Tx.requiringNew().call(() -> defaultsLedger.loadSettingKeys());
        assertTrue(settingKeys.contains(key), "the setting row's key");
        assertFalse(settingKeys.stream().anyMatch(k -> k.startsWith(bundle) && !k.equals(key)),
                "no TEXT row's key");
        Tx.requiringNew().run(() -> em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE \"key\" = :key")
                .setParameter("key", key).executeUpdate());
    }

    @AfterEach
    void removeSyntheticDictionaries() {
        Tx.requiringNew().run(() -> {
            deleteDescsAndEntries();
            em.createNativeQuery("DELETE FROM public.\"Dictionary\" WHERE name = :name")
                    .setParameter("name", bundle).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE dictionary = :name")
                    .setParameter("name", bundle).executeUpdate();
        });
    }

    // ------------------------------------------------------------------------------------------- //

    /** A restart: the start-up step on the real bundles (and the cache rebuild), then on the synthetic bundle */
    private void restart(DefaultTexts.Layered layered) {
        dictionaryService.init();
        dictionaryService.addMissingDefaults(layered);
    }

    /**
     * The synthetic bundle's layered defaults: en from the base product's folder, the second language (when given)
     * from its language pack, {@code defaults/packs/<lang>/}.
     */
    private DefaultTexts.Layered layered(String[] languages, String en, String other) throws IOException {
        write("defaults/base/texts/en/" + bundle + ".properties", en);
        if (other != null) {
            write("defaults/packs/" + languages[1] + "/" + bundle + ".properties", other);
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

    /** Must run in a transaction */
    private void deleteDescsAndEntries() {
        em.createNativeQuery("DELETE FROM public.\"DictionaryEntryDesc\" WHERE entity_id IN (SELECT e.id FROM "
                + "public.\"DictionaryEntry\" e JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                + "WHERE d.name = :name)").setParameter("name", bundle).executeUpdate();
        em.createNativeQuery("DELETE FROM public.\"DictionaryEntry\" WHERE dictionary_id IN (SELECT id FROM "
                + "public.\"Dictionary\" WHERE name = :name)").setParameter("name", bundle).executeUpdate();
    }

    /** The committed descriptions of the synthetic bundle, as key/lang -> value */
    @SuppressWarnings("unchecked")
    private Map<String, String> descs() {
        List<Object[]> rows = Tx.requiringNew().call(() -> em.createNativeQuery(
                        "SELECT e.dict_key, t.lang, t.value FROM public.\"DictionaryEntryDesc\" t "
                                + "JOIN public.\"DictionaryEntry\" e ON t.entity_id = e.id "
                                + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id WHERE d.name = :name")
                .setParameter("name", bundle)
                .getResultList());
        Map<String, String> result = new TreeMap<>();
        rows.forEach(r -> result.put(r[0] + "/" + r[1], (String) r[2]));
        return result;
    }

    /** The committed ledger rows of the synthetic bundle, as key/lang -> source */
    private Map<String, String> ledger() {
        Map<String, String> result = new TreeMap<>();
        ledgerRows().forEach(r -> result.put(r[0] + "/" + r[1], (String) r[2]));
        return result;
    }

    /** The committed ledger rows of the synthetic bundle (key, lang, source, hash), ordered by key and language */
    @SuppressWarnings("unchecked")
    private List<Object[]> ledgerRows() {
        return new ArrayList<>(Tx.requiringNew().call(() -> em.createNativeQuery(
                        "SELECT \"key\", lang, source, hash FROM public.\"DefaultsLedger\" "
                                + "WHERE kind = 'TEXT' AND dictionary = :name ORDER BY \"key\" COLLATE \"C\", lang COLLATE \"C\"")
                .setParameter("name", bundle)
                .getResultList()));
    }

    private static void assertRow(Object[] row, String key, String lang, String source, String hash) {
        assertEquals(key, row[0]);
        assertEquals(lang, row[1]);
        assertEquals(source, row[2]);
        assertEquals(hash, row[3]);
    }
}
