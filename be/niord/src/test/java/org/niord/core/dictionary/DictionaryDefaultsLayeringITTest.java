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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layered add-missing step on the real class path (docs/code-data-config-plan.md §4.1): the country's texts,
 * then the base product's English or a language pack.
 *
 * <p>The test tree's {@code defaults/packs/da/} is a stand-in da pack, DK's Danish bundles verbatim, line endings
 * aside. It sits on the class path beside niord-base's {@code defaults/base/texts/en/} and is read as a country's
 * staged pack is (source {@code pack:da}); the test-jar leaves it out. The context's start-up has run the
 * add-missing step over this class path. Since C4b the fixture's texts are the base product's (the IT start adopted
 * them), so the two tests of a Danish database first give it the state such a database had, a missing base product
 * key and a DK wording, in committed rows, run the start-up's add-missing step again
 * ({@link DictionaryService#init()}) and read what it left; each puts the key's rows back afterwards.
 *
 * <p>The last case reads a {@code @TempDir} class path instead, whose root-level bundles add nothing.
 */
class DictionaryDefaultsLayeringITTest extends ITTest {

    @Autowired
    DictionaryService dictionaryService;

    @PersistenceContext
    EntityManager em;

    @TempDir
    Path classPathRoot;

    private final String prefix = "it-gl-" + System.nanoTime() + "-";

    private final List<String> synthetic = new ArrayList<>();

    @Test
    void theBaseProductKeyADanishMailBundleHidReachesTheSeededDatabase() {
        // A database built from DK's old files lacks the base product key, and its ledger has no row of the key:
        // its English description and the key's ledger rows are deleted here
        KeyState saved = saveKey("mail", "mail.safetynet.cancel", "en");
        try {
            Tx.requiringNew().run(() -> {
                deleteDescription("mail", "mail.safetynet.cancel", "en");
                deleteLedgerRows("mail", "mail.safetynet.cancel");
            });
            dictionaryService.init();

            assertEquals("CANCEL NAV WARN", value("mail", "mail.safetynet.cancel", "en"));
            assertEquals(DefaultTexts.SOURCE_BASE, ledgerSource("mail", "mail.safetynet.cancel", "en"));
        } finally {
            restoreKey(saved);
        }
    }

    @Test
    void aCountryRewordingAlreadyInTheDatabaseIsNeverChanged() {
        // A database built from DK's files holds DK's wording, with no ledger row of the key
        KeyState saved = saveKey("mail", "mail.active_nav_warnings", "en");
        try {
            Tx.requiringNew().run(() -> {
                setDescription("mail", "mail.active_nav_warnings", "en", "Active Danish Navigational Warnings");
                deleteLedgerRows("mail", "mail.active_nav_warnings");
            });
            dictionaryService.init();

            assertEquals("Active Danish Navigational Warnings", value("mail", "mail.active_nav_warnings", "en"));
            assertEquals(DefaultsLedger.SOURCE_ADOPTED, ledgerSource("mail", "mail.active_nav_warnings", "en"));
            assertNull(ledgerHash("mail", "mail.active_nav_warnings", "en"),
                    "the row differs from the layered default (the base product's wording), so no hash is recorded");
        } finally {
            restoreKey(saved);
        }
    }

    @Test
    void theTestPacksDanishIsReadKeyByKey() throws IOException {
        for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
            String name = "defaults/packs/da/" + bundle + ".properties";
            Properties da = packBundle(name);
            assertFalse(da.isEmpty(), name + " is on the test class path");
            for (String key : da.stringPropertyNames()) {
                assertNotNull(value(bundle, key, "da"), bundle + " " + key + " has a Danish row");
                assertTrue(Set.of(DefaultsLedger.SOURCE_ADOPTED, DefaultTexts.SOURCE_PACK_PREFIX + "da")
                                .contains(ledgerSource(bundle, key, "da")),
                        bundle + " " + key + " da is recorded as adopted or from the pack");
            }
        }
    }

    @Test
    void anotherStartChangesNoDictionaryOrLedgerRow() {
        List<Long> before = counts();

        dictionaryService.init();

        assertEquals(before, counts());
    }

    /**
     * GL's old shape, root-level {@code *_en} shadowing the base product's and its own language as {@code *_kl},
     * gives nothing now: only the country's texts and the pack are read beside the base product's folder.
     */
    @Test
    void rootLevelBundlesBesideACountryLayerAndAPackAddNothing() throws IOException {
        String[] bundles = new String[DictionaryService.DEFAULT_BUNDLES.length];
        for (int i = 0; i < bundles.length; i++) {
            String bundle = prefix + DictionaryService.DEFAULT_BUNDLES[i];
            bundles[i] = bundle;
            synthetic.add(bundle);
            write("defaults/base/texts/en/" + bundle + ".properties", "shared = Base\nbase.only = Base only\n");
            write("defaults/country/texts/en/" + bundle + ".properties", "gl.country = Country\n");
            write("defaults/packs/kl/" + bundle + ".properties", "shared = Kalaallisut\n");
            write(bundle + "_en.properties", "shared = Greenland\ngl.own = Own\n");
            write(bundle + "_kl.properties", "shared = Root\ngl.own = Nammineq\n");
        }
        DefaultTexts.Layered layered;
        try (URLClassLoader cl = new URLClassLoader(new URL[] {classPathRoot.toUri().toURL()}, null)) {
            layered = DefaultTexts.read(cl, bundles, new String[] {"kl", "en"}, "kl");
        }

        assertEquals(5 * 4, dictionaryService.addMissingDefaults(layered));

        for (String bundle : bundles) {
            assertEquals("Base", value(bundle, "shared", "en"), "the base product's value, not the root-level one");
            assertEquals("Base only", value(bundle, "base.only", "en"));
            assertEquals("Country", value(bundle, "gl.country", "en"));
            assertEquals(DefaultTexts.SOURCE_COUNTRY, ledgerSource(bundle, "gl.country", "en"));
            assertEquals("Kalaallisut", value(bundle, "shared", "kl"), "the pack's value, not the root-level one");
            assertEquals(DefaultTexts.SOURCE_PACK_PREFIX + "kl", ledgerSource(bundle, "shared", "kl"));
            assertNull(value(bundle, "gl.own", "en"), "a key only a root-level *_en has gets no row");
            assertNull(value(bundle, "gl.own", "kl"), "a key only a root-level *_kl has gets no row");
            assertNull(value(bundle, "base.only", "kl"), "a key no kl file has gets no kl row");
            assertEquals(DefaultTexts.SOURCE_BASE, ledgerSource(bundle, "base.only", "en"));
        }
    }

    @AfterEach
    void removeSyntheticDictionaries() {
        Tx.requiringNew().run(() -> {
            for (String name : synthetic) {
                em.createNativeQuery("DELETE FROM public.\"DictionaryEntryDesc\" WHERE entity_id IN (SELECT e.id "
                        + "FROM public.\"DictionaryEntry\" e JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                        + "WHERE d.name = :name)").setParameter("name", name).executeUpdate();
                em.createNativeQuery("DELETE FROM public.\"DictionaryEntry\" WHERE dictionary_id IN (SELECT id FROM "
                        + "public.\"Dictionary\" WHERE name = :name)").setParameter("name", name).executeUpdate();
                em.createNativeQuery("DELETE FROM public.\"Dictionary\" WHERE name = :name")
                        .setParameter("name", name).executeUpdate();
                em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE dictionary = :name")
                        .setParameter("name", name).executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------------------------------- //

    /** The ids of the entries of one key, with the parameters {@code name} and {@code key} */
    private static final String ENTRY_IDS = "SELECT e.id FROM public.\"DictionaryEntry\" e "
            + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id WHERE d.name = :name AND e.dict_key = :key";

    /** A key's committed description in one language and every ledger row of the key, to put back */
    private record KeyState(String dictionary, String key, String lang, String value, List<Object[]> ledger) {
    }

    private KeyState saveKey(String dictionary, String key, String lang) {
        List<Object[]> ledger = new ArrayList<>();
        Tx.requiringNew().call(() -> em.createNativeQuery(
                        "SELECT kind, \"key\", dictionary, lang, hash, source, \"baseVersion\", created "
                                + "FROM public.\"DefaultsLedger\" WHERE kind = 'TEXT' AND dictionary = :name "
                                + "AND \"key\" = :key")
                .setParameter("name", dictionary).setParameter("key", key)
                .getResultList()).forEach(row -> ledger.add((Object[]) row));
        String value = value(dictionary, key, lang);
        assertNotNull(value, "the fixture holds " + dictionary + " " + key + " " + lang);
        return new KeyState(dictionary, key, lang, value, ledger);
    }

    /** Puts the saved description and ledger rows back, and caches the dictionaries again */
    private void restoreKey(KeyState state) {
        boolean described = value(state.dictionary(), state.key(), state.lang()) != null;
        Tx.requiringNew().run(() -> {
            if (described) {
                setDescription(state.dictionary(), state.key(), state.lang(), state.value());
            } else {
                em.createNativeQuery("INSERT INTO public.\"DictionaryEntryDesc\" (id, lang, value, entity_id) "
                                + "SELECT nextval('public.\"DictionaryEntryDesc_SEQ\"'), :lang, :value, e.id "
                                + "FROM (" + ENTRY_IDS + ") e")
                        .setParameter("lang", state.lang()).setParameter("value", state.value())
                        .setParameter("name", state.dictionary()).setParameter("key", state.key())
                        .executeUpdate();
            }
            deleteLedgerRows(state.dictionary(), state.key());
            for (Object[] row : state.ledger()) {
                em.createNativeQuery("INSERT INTO public.\"DefaultsLedger\" (kind, \"key\", dictionary, lang, hash, "
                                + "source, \"baseVersion\", created) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)")
                        .setParameter(1, row[0]).setParameter(2, row[1]).setParameter(3, row[2])
                        .setParameter(4, row[3]).setParameter(5, row[4]).setParameter(6, row[5])
                        .setParameter(7, row[6]).setParameter(8, row[7])
                        .executeUpdate();
            }
        });
        dictionaryService.init();
    }

    private void setDescription(String dictionary, String key, String lang, String value) {
        em.createNativeQuery("UPDATE public.\"DictionaryEntryDesc\" SET value = :value WHERE lang = :lang "
                        + "AND entity_id IN (" + ENTRY_IDS + ")")
                .setParameter("value", value).setParameter("lang", lang)
                .setParameter("name", dictionary).setParameter("key", key)
                .executeUpdate();
    }

    private void deleteDescription(String dictionary, String key, String lang) {
        em.createNativeQuery("DELETE FROM public.\"DictionaryEntryDesc\" WHERE lang = :lang "
                        + "AND entity_id IN (" + ENTRY_IDS + ")")
                .setParameter("lang", lang).setParameter("name", dictionary).setParameter("key", key)
                .executeUpdate();
    }

    private void deleteLedgerRows(String dictionary, String key) {
        em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE kind = 'TEXT' AND dictionary = :name "
                        + "AND \"key\" = :key")
                .setParameter("name", dictionary).setParameter("key", key)
                .executeUpdate();
    }

    private void write(String name, String content) throws IOException {
        Path file = classPathRoot.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Properties packBundle(String name) throws IOException {
        Properties props = new Properties();
        try (InputStream in = DictionaryService.class.getClassLoader().getResourceAsStream(name)) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        return props;
    }

    /** The committed value of the triple, or null */
    private String value(String dictionary, String key, String lang) {
        List<?> rows = Tx.requiringNew().call(() -> em.createNativeQuery(
                        "SELECT t.value FROM public.\"DictionaryEntryDesc\" t "
                                + "JOIN public.\"DictionaryEntry\" e ON t.entity_id = e.id "
                                + "JOIN public.\"Dictionary\" d ON e.dictionary_id = d.id "
                                + "WHERE d.name = :name AND e.dict_key = :key AND t.lang = :lang")
                .setParameter("name", dictionary).setParameter("key", key).setParameter("lang", lang)
                .getResultList());
        return rows.isEmpty() ? null : (String) rows.getFirst();
    }

    private String ledgerSource(String dictionary, String key, String lang) {
        Object[] row = ledgerRow(dictionary, key, lang);
        return row == null ? null : (String) row[0];
    }

    private String ledgerHash(String dictionary, String key, String lang) {
        Object[] row = ledgerRow(dictionary, key, lang);
        assertNotNull(row, "a ledger row for " + dictionary + " " + key + " " + lang);
        return (String) row[1];
    }

    private Object[] ledgerRow(String dictionary, String key, String lang) {
        List<?> rows = Tx.requiringNew().call(() -> em.createNativeQuery(
                        "SELECT source, hash FROM public.\"DefaultsLedger\" WHERE kind = 'TEXT' "
                                + "AND dictionary = :name AND \"key\" = :key AND lang = :lang")
                .setParameter("name", dictionary).setParameter("key", key).setParameter("lang", lang)
                .getResultList());
        return rows.isEmpty() ? null : (Object[]) rows.getFirst();
    }

    /** The row counts of the dictionary tables and the ledger */
    private List<Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : new String[] {"Dictionary", "DictionaryEntry", "DictionaryEntryDesc", "DefaultsLedger"}) {
            counts.put(table, Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                    "SELECT count(*) FROM public.\"" + table + "\"").getSingleResult()).longValue()));
        }
        return new ArrayList<>(counts.values());
    }
}
