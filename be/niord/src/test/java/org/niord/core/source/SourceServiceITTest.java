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
package org.niord.core.source;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SourceService} persistence and search behaviour, run against the
 * seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent. Test sources use an {@code IT-} name
 * prefix that does not occur in the seed data, so searches and name lookups stay isolated from the
 * seeded Danish sources.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SourceServiceITTest extends ITTest {

    @Autowired
    SourceService sourceService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached, new (null-id) Source with a single localized description. */
    private Source newSource(String lang, String name, String abbreviation) {
        Source source = new Source();
        SourceDesc desc = source.createDesc(lang);
        desc.setName(name);
        desc.setAbbreviation(abbreviation);
        return source;
    }

    // ------------------------------------------------------------------------------------------- //
    // createSource
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createSourcePersistsRowWithGeneratedIdAndDefaults() {
        Source created = sourceService.createSource(newSource("en", "IT-Create-Name", "IT-CR"));
        Integer id = created.getId();
        assertNotNull(id, "createSource must assign a generated id");

        em.flush();
        em.clear();

        Source reloaded = em.find(Source.class, id);
        assertNotNull(reloaded, "the source row must exist in the database");
        assertTrue(reloaded.isActive(), "a new source defaults to active");
        assertEquals(1, reloaded.getDescs().size());
        assertEquals("IT-Create-Name", reloaded.getDesc("en").getName());
        assertEquals("IT-CR", reloaded.getDesc("en").getAbbreviation());
    }

    @Test
    @Transactional
    void createSourcePersistsMultipleLanguageDescriptions() {
        Source source = newSource("en", "IT-Multi-EN", "IT-M-EN");
        SourceDesc da = source.createDesc("da");
        da.setName("IT-Multi-DA");
        da.setAbbreviation("IT-M-DA");

        Source created = sourceService.createSource(source);
        Integer id = created.getId();

        em.flush();
        em.clear();

        Source reloaded = em.find(Source.class, id);
        assertEquals(2, reloaded.getDescs().size());
        assertEquals("IT-Multi-EN", reloaded.getDesc("en").getName());
        assertEquals("IT-Multi-DA", reloaded.getDesc("da").getName());
    }

    @Test
    @Transactional
    void createSourceRejectsSourceWithExistingId() {
        Source created = sourceService.createSource(newSource("en", "IT-Existing-Id", "IT-EI"));
        em.flush();

        // The source now has a generated ID, so a second create must be rejected.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sourceService.createSource(created));
        assertTrue(ex.getMessage().contains("existing ID"),
                "the rejection must mention the existing id");
    }

    @Test
    @Transactional
    void createSourceRejectsNullDescriptionName() {
        // After the MySQL -> PostgreSQL migration the SourceDesc.name column is NOT NULL
        // (`name character varying(255) NOT NULL`), so the database now enforces the @NotNull on
        // SourceDesc.name. Under MySQL the migration's prior lenient behaviour silently accepted a
        // null name (documented historically as a bug); Postgres correctly rejects it on flush.
        //
        // Happy path: a description WITH a valid name persists without error.
        Source valid = new Source();
        SourceDesc validDesc = valid.createDesc("en");
        validDesc.setName("IT-NotNullName");
        validDesc.setAbbreviation("IT-NN-OK");
        Source createdValid = sourceService.createSource(valid);
        Integer validId = createdValid.getId();
        em.flush();
        em.clear();
        assertEquals("IT-NotNullName", em.find(Source.class, validId).getDesc("en").getName(),
                "a description with a valid name is persisted");

        // Negative path: a description whose only field is the abbreviation (name left null) must be
        // rejected by the NOT NULL constraint when it reaches the database on flush.
        Source invalid = new Source();
        SourceDesc nullNameDesc = invalid.createDesc("en"); // name left null
        nullNameDesc.setAbbreviation("IT-NULLNAME"); // keep the desc "defined" so it is not pruned as blank

        sourceService.createSource(invalid);
        PersistenceException ex = assertThrows(PersistenceException.class, () -> em.flush(),
                "SourceDesc.name is NOT NULL in PostgreSQL; a null name must be rejected on flush");
        assertNotNull(ex, "a constraint violation must be raised for a null SourceDesc.name");
    }

    @Test
    @Transactional
    void createSourceWithDuplicateLanguageViolatesUniqueConstraint() {
        Source source = newSource("en", "IT-Dup-1", "IT-D1");
        SourceDesc dup = source.createDesc("en"); // a second "en" desc on the same source
        dup.setName("IT-Dup-2");
        dup.setAbbreviation("IT-D2");

        // The (lang, entity_id) unique constraint is only observable at the database layer.
        assertThrows(PersistenceException.class, () -> {
            sourceService.createSource(source);
            em.flush();
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // findById / findByIds
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByIdReturnsPersistedSourceAndNullForUnknown() {
        Source created = sourceService.createSource(newSource("en", "IT-ById", "IT-BI"));
        em.flush();
        em.clear();

        Source found = sourceService.findById(created.getId());
        assertNotNull(found);
        assertEquals("IT-ById", found.getDesc("en").getName());

        assertNull(sourceService.findById(-999_999), "an unknown id must return null");
    }

    @Test
    @Transactional
    void findByIdsReturnsMatchingSourcesOnly() {
        Source a = sourceService.createSource(newSource("en", "IT-Ids-A", "IT-IA"));
        Source b = sourceService.createSource(newSource("en", "IT-Ids-B", "IT-IB"));
        em.flush();

        List<Source> result = sourceService.findByIds(Set.of(a.getId(), b.getId(), -999_999));
        Set<Integer> ids = new java.util.HashSet<>();
        result.forEach(s -> ids.add(s.getId()));
        assertEquals(2, result.size(), "only the two existing ids must be returned");
        assertTrue(ids.contains(a.getId()));
        assertTrue(ids.contains(b.getId()));
    }

    @Test
    @Transactional
    void findByIdsReturnsEmptyForNullOrEmptyInput() {
        assertTrue(sourceService.findByIds(null).isEmpty(), "null ids must return an empty list");
        assertTrue(sourceService.findByIds(Set.of()).isEmpty(), "empty ids must return an empty list");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByName
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByNameMatchesExactlyAndCaseInsensitively() {
        Source created = sourceService.createSource(newSource("en", "IT-FindName", "IT-FN"));
        em.flush();

        assertEquals(created.getId(), sourceService.findByName("en", "IT-FindName").getId());
        assertEquals(created.getId(), sourceService.findByName("en", "it-findname").getId(),
                "findByName must be case-insensitive on the stored name");
    }

    @Test
    @Transactional
    void findByNameIsExactNotSubstring() {
        sourceService.createSource(newSource("en", "IT-FindName-Exact", "IT-FNE"));
        em.flush();

        assertNull(sourceService.findByName("en", "IT-FindName"),
                "findByName matches the full name, not a substring");
    }

    @Test
    @Transactional
    void findByNameIsLanguageScoped() {
        sourceService.createSource(newSource("en", "IT-LangScoped", "IT-LS"));
        em.flush();

        assertNotNull(sourceService.findByName("en", "IT-LangScoped"));
        assertNull(sourceService.findByName("da", "IT-LangScoped"),
                "a name defined for 'en' must not be found under 'da'");
    }

    @Test
    @Transactional
    void findByNameDoesNotMatchAbbreviation() {
        sourceService.createSource(newSource("en", "IT-AbbrName", "IT-ABBR-ONLY"));
        em.flush();

        assertNull(sourceService.findByName("en", "IT-ABBR-ONLY"),
                "findByName must match the name field, not the abbreviation");
    }

    @Test
    @Transactional
    void findByNameReturnsNullForBlankOrNull() {
        assertNull(sourceService.findByName("en", ""));
        assertNull(sourceService.findByName("en", "   "));
        assertNull(sourceService.findByName("en", null));
    }

    @Test
    @Transactional
    void findByNameReturnsNullWhenMultipleMatch() {
        // Two sources sharing the same name make getSingleResult throw, which findByName swallows.
        sourceService.createSource(newSource("en", "IT-Ambiguous", "IT-AMB-1"));
        sourceService.createSource(newSource("en", "IT-Ambiguous", "IT-AMB-2"));
        em.flush();

        assertNull(sourceService.findByName("en", "IT-Ambiguous"),
                "an ambiguous (non-unique) name lookup must return null, not throw");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchSources
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchSourcesMatchesNameSubstringCaseInsensitively() {
        sourceService.createSource(newSource("en", "IT-Search-Alpha", "IT-SA"));
        sourceService.createSource(newSource("en", "IT-Search-Beta", "IT-SB"));
        em.flush();

        List<Source> result = sourceService.searchSources("en", "it-search", false, 100);
        assertEquals(2, result.size(), "both name matches must be returned, case-insensitively");
    }

    @Test
    @Transactional
    void searchSourcesMatchesAbbreviationSubstring() {
        sourceService.createSource(newSource("en", "IT-Abbr-Search", "IT-UNIQUE-ABBR"));
        em.flush();

        List<Source> result = sourceService.searchSources("en", "it-unique-abbr", false, 100);
        assertEquals(1, result.size(), "search must also match the abbreviation field");
        assertEquals("IT-Abbr-Search", result.get(0).getDesc("en").getName());
    }

    @Test
    @Transactional
    void searchSourcesExcludesInactiveByDefaultButIncludesWhenRequested() {
        Source inactive = newSource("en", "IT-Inactive-Search", "IT-IS");
        inactive.setActive(false);
        sourceService.createSource(inactive);
        em.flush();

        assertTrue(sourceService.searchSources("en", "IT-Inactive-Search", false, 100).isEmpty(),
                "inactive sources are excluded by default");
        assertEquals(1, sourceService.searchSources("en", "IT-Inactive-Search", true, 100).size(),
                "inactive sources are included when requested");
    }

    @Test
    @Transactional
    void searchSourcesIsLanguageScoped() {
        sourceService.createSource(newSource("en", "IT-OnlyEnglish", "IT-OE"));
        em.flush();

        assertEquals(1, sourceService.searchSources("en", "IT-OnlyEnglish", false, 100).size());
        assertTrue(sourceService.searchSources("da", "IT-OnlyEnglish", false, 100).isEmpty(),
                "a source described only in 'en' must not match a 'da' search");
    }

    @Test
    @Transactional
    void searchSourcesRespectsLimit() {
        sourceService.createSource(newSource("en", "IT-Limit-1", "IT-L1"));
        sourceService.createSource(newSource("en", "IT-Limit-2", "IT-L2"));
        sourceService.createSource(newSource("en", "IT-Limit-3", "IT-L3"));
        em.flush();

        assertEquals(3, sourceService.searchSources("en", "IT-Limit", false, 100).size());
        assertEquals(2, sourceService.searchSources("en", "IT-Limit", false, 2).size(),
                "the limit must cap the number of returned sources");
    }

    @Test
    @Transactional
    void searchSourcesWithBlankTermMatchesActiveSourcesForLanguage() {
        // A blank term becomes "%%", matching any name for the language. Use a unique
        // language so the result count is deterministic and isolated from seed data.
        sourceService.createSource(newSource("it-lang", "IT-Blank-Term", "IT-BT"));
        em.flush();

        List<Source> result = sourceService.searchSources("it-lang", "", false, 100);
        assertEquals(1, result.size(),
                "a blank term must match every source defined for the (unique) language");
        assertEquals("IT-Blank-Term", result.get(0).getDesc("it-lang").getName());
    }

    @Test
    @Transactional
    void searchSourcesReturnsEmptyForNonMatchingTerm() {
        sourceService.createSource(newSource("en", "IT-NoMatch-Source", "IT-NMS"));
        em.flush();

        assertTrue(sourceService.searchSources("en", "IT-NoSuchTermXyz", true, 100).isEmpty(),
                "a term that matches nothing must return an empty list");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateSource
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateSourcePersistsChangedFields() {
        // FIXED (see docs/potential-errors.md, SRC1): SourceService.updateSource no longer clear()s the
        // descs before copying. copyDescsAndRemoveBlanks() updates them in place by language, so even
        // when the caller passes the MANAGED entity it just loaded (original == source, the normal
        // service flow), the existing English description is updated rather than orphan-removed.
        Source created = sourceService.createSource(newSource("en", "IT-Upd-Orig", "IT-UO"));
        Integer id = created.getId();
        em.flush();
        em.clear();

        Source template = sourceService.findById(id); // managed instance == update target
        template.setActive(false);
        template.getDesc("en").setName("IT-Upd-Renamed");
        template.getDesc("en").setAbbreviation("IT-UR");

        sourceService.updateSource(template);
        em.flush();
        em.clear();

        Source reloaded = em.find(Source.class, id);
        // The non-desc field is persisted...
        assertFalse(reloaded.isActive(), "the active flag is updated");
        // ...and the English description is updated in place (not deleted), carrying the new values.
        assertEquals(1, reloaded.getDescs().size(),
                "the single English description survives the update");
        assertNotNull(reloaded.getDesc("en"), "the English description is retained and updated");
        assertEquals("IT-Upd-Renamed", reloaded.getDesc("en").getName(), "the rename is persisted");
        assertEquals("IT-UR", reloaded.getDesc("en").getAbbreviation(), "the new abbreviation is persisted");
    }

    @Test
    @Transactional
    void updateSourceRemovesBlankDescriptions() {
        // FIXED (see docs/potential-errors.md, SRC1): with the clear() removed, copyDescsAndRemoveBlanks
        // keeps the English description and drops only the blanked Danish one (removeIf !descDefined()),
        // so the update ends with exactly the retained English description.
        Source source = newSource("en", "IT-UpdBlank-EN", "IT-UB-EN");
        SourceDesc da = source.createDesc("da");
        da.setName("IT-UpdBlank-DA");
        da.setAbbreviation("IT-UB-DA");
        Source created = sourceService.createSource(source);
        Integer id = created.getId();
        em.flush();
        em.clear();

        Source template = sourceService.findById(id);
        // Blank out the Danish description; the English one is meant to be retained.
        template.getDesc("da").setName(null);
        template.getDesc("da").setAbbreviation(null);

        sourceService.updateSource(template);
        em.flush();
        em.clear();

        Source reloaded = em.find(Source.class, id);
        // The blank Danish desc is removed; the English one survives with its original values.
        assertEquals(1, reloaded.getDescs().size(),
                "only the blanked Danish description is removed; English is retained");
        assertNotNull(reloaded.getDesc("en"), "the English description survives the update");
        assertEquals("IT-UpdBlank-EN", reloaded.getDesc("en").getName(), "the English name is unchanged");
        assertNull(reloaded.getDesc("da"), "the blanked Danish description is gone");
    }

    @Test
    @Transactional
    void updateSourceAddsNewLanguageDescription() {
        // FIXED (see docs/potential-errors.md, SRC1): adding a Danish description while keeping the
        // English one now ends with TWO descriptions - copyDescsAndRemoveBlanks updates "en" in place
        // and creates "da", instead of the old clear() wiping both.
        Source created = sourceService.createSource(newSource("en", "IT-AddLang-EN", "IT-AL-EN"));
        Integer id = created.getId();
        em.flush();
        em.clear();

        Source template = sourceService.findById(id);
        SourceDesc da = template.createDesc("da");
        da.setName("IT-AddLang-DA");
        da.setAbbreviation("IT-AL-DA");

        sourceService.updateSource(template);
        em.flush();
        em.clear();

        Source reloaded = em.find(Source.class, id);
        assertEquals(2, reloaded.getDescs().size(),
                "adding a language ends with both the existing and the new description");
        assertNotNull(reloaded.getDesc("da"), "the newly added Danish description is persisted");
        assertEquals("IT-AddLang-DA", reloaded.getDesc("da").getName(), "the Danish name is persisted");
        assertNotNull(reloaded.getDesc("en"), "the existing English description is retained");
        assertEquals("IT-AddLang-EN", reloaded.getDesc("en").getName(), "the English name is unchanged");
    }

    @Test
    @Transactional
    void updateSourceRejectsNonExistingSource() {
        Source source = newSource("en", "IT-NonExisting", "IT-NE");
        source.setId(-999_999); // an id that does not exist

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sourceService.updateSource(source));
        assertTrue(ex.getMessage().contains("non-existing"),
                "updating an unknown source must be rejected");
    }

    // ------------------------------------------------------------------------------------------- //
    // findOrCreateSource
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findOrCreateSourceReturnsNullForNullTemplate() {
        assertNull(sourceService.findOrCreateSource(null, true));
    }

    @Test
    @Transactional
    void findOrCreateSourceReturnsExistingMatchByNameWithoutCreating() {
        Source existing = sourceService.createSource(newSource("en", "IT-FOC-Existing", "IT-FOCE"));
        Integer existingId = existing.getId();
        em.flush();

        long before = sourceService.searchSources("en", "IT-FOC-Existing", true, 100).size();

        Source template = newSource("en", "IT-FOC-Existing", "IT-OTHER-ABBR");
        Source result = sourceService.findOrCreateSource(template, true);

        assertEquals(existingId, result.getId(),
                "an existing source must be reused, not recreated");
        em.flush();
        long after = sourceService.searchSources("en", "IT-FOC-Existing", true, 100).size();
        assertEquals(before, after, "no new source row must be created when a match exists");
    }

    @Test
    @Transactional
    void findOrCreateSourceCreatesWhenMissingAndCreateTrue() {
        Source template = newSource("en", "IT-FOC-New", "IT-FOCN");

        Source result = sourceService.findOrCreateSource(template, true);
        assertNotNull(result.getId(), "a missing source must be created and assigned an id");

        em.flush();
        em.clear();

        Source reloaded = em.find(Source.class, result.getId());
        assertNotNull(reloaded, "the created source must be persisted");
        assertEquals("IT-FOC-New", reloaded.getDesc("en").getName());
    }

    @Test
    @Transactional
    void findOrCreateSourceReturnsNullWhenMissingAndCreateFalse() {
        Source template = newSource("en", "IT-FOC-NoCreate", "IT-FOCNC");

        Source result = sourceService.findOrCreateSource(template, false);
        assertNull(result, "a missing source must not be created when create is false");

        em.flush();
        assertNull(sourceService.findByName("en", "IT-FOC-NoCreate"),
                "no source row must exist after a non-creating lookup miss");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteSource
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteSourceRemovesRowAndReturnsTrue() {
        Source created = sourceService.createSource(newSource("en", "IT-Delete-Me", "IT-DM"));
        Integer id = created.getId();
        em.flush();

        assertTrue(sourceService.deleteSource(id));
        em.flush();
        em.clear();

        assertNull(em.find(Source.class, id), "the deleted source must be gone from the database");
    }

    @Test
    @Transactional
    void deleteSourceCascadesToDescriptions() {
        Source source = newSource("en", "IT-DelCascade-EN", "IT-DC-EN");
        SourceDesc da = source.createDesc("da");
        da.setName("IT-DelCascade-DA");
        da.setAbbreviation("IT-DC-DA");
        Source created = sourceService.createSource(source);
        Integer descId = created.getDesc("en").getId();
        assertNotNull(descId);
        em.flush();

        assertTrue(sourceService.deleteSource(created.getId()));
        em.flush();
        em.clear();

        assertNull(em.find(SourceDesc.class, descId),
                "deleting a source must cascade-delete its descriptions");
    }

    @Test
    @Transactional
    void deleteSourceReturnsFalseForUnknownId() {
        assertFalse(sourceService.deleteSource(-999_999),
                "deleting an unknown source must return false");
    }
}
