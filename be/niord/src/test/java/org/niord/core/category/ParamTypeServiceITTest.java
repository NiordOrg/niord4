/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.core.category;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.dictionary.Dictionary;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.web.NiordWebException;
import org.niord.test.ITTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the parameter-type CRUD of {@link TemplateExecutionService}
 * (getParamTypes/getParamType/getParamTypeByName/createParamType/updateParamType/deleteParamType),
 * run against the seeded MySQL container.
 *
 * <p>The fixture holds 34 {@code ParamType} rows in a single-table hierarchy: 20 {@link ListParamType} and 14
 * {@link CompositeParamType}, the base product bootstrap's 32 and the sample's {@code dgps} pair. Since C4.1 the
 * standard types (text/number/boolean/date/aton_name/light_character/call_sign) are code, held in memory by the
 * service with no id (docs/code-data-config-plan.md §6.2), and a bootstrapped database holds no row of them; an
 * older deployment still holds the 7 rows start-up created until then, which are ignored and never deleted (no
 * start-up step touches a {@code ParamType} row since C4.1; the test of them plants the rows itself). Every
 * test runs inside a {@link TestTransaction} so all writes roll back, and new types use an {@code IT-} name prefix
 * that does not collide with the fixture.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ParamTypeServiceITTest extends ITTest {

    @Autowired
    TemplateExecutionService templateExecutionService;

    @Autowired
    CategoryService categoryService;

    @PersistenceContext
    EntityManager em;

    // --------------------------------------------------------------------------------------- //
    // reads
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getParamTypes_returnsTheStandardTypesFromMemoryThenTheRowsSortedByName() {
        List<ParamType> types = templateExecutionService.getParamTypes();
        // The 7 standard types in memory and the fixture's 34 rows (the base product's 32, the sample's dgps pair)
        assertTrue(types.size() >= 41, "7 standard types and the fixture's 34 others, found " + types.size());

        List<String> standard = List.of(StandardParamType.STANDARD_PARAM_TYPES);
        assertEquals(standard, types.subList(0, 7).stream().map(ParamType::getName).toList(),
                "the standard types come first, in the code's order");
        types.subList(0, 7).forEach(t -> {
            assertInstanceOf(StandardParamType.class, t);
            assertNull(t.getId(), "a standard type is held in memory and has no id: " + t.getName());
        });

        List<ParamType> rows = types.subList(7, types.size());
        assertTrue(rows.stream().noneMatch(t -> standard.contains(t.getName())),
                "a row with a standard type's name is ignored: the in-memory type wins");
        // ParamType.findAll orders by name asc.
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).getName().compareTo(rows.get(i).getName()) <= 0,
                    "the database's parameter types must be returned sorted by name");
        }
    }

    @Test
    @Transactional
    void legacyStandardRowsAreIgnoredAndListedOnce() {
        // The rows start-up created until C4.1 stay in an older deployment's database (R-CDC1); nothing reads
        // them. The fixture is bootstrapped, so it has none: the test plants them, and the rollback takes them.
        // That they are never deleted needs no start-up run here: since C4.1 no start-up step touches a ParamType
        // row (TemplateExecutionService holds the standard types in memory), so this test proves what is left to
        // prove, that the rows are ignored and each standard type is listed once
        for (String name : StandardParamType.STANDARD_PARAM_TYPES) {
            em.persist(new StandardParamType(name));
        }
        em.flush();
        Long rows = em.createQuery("select count(p) from StandardParamType p where p.name in :names", Long.class)
                .setParameter("names", List.of(StandardParamType.STANDARD_PARAM_TYPES))
                .getSingleResult();
        assertEquals(7L, rows, "the 7 legacy standard rows are there");
        assertEquals(1, templateExecutionService.getParamTypes().stream()
                .filter(t -> "text".equals(t.getName())).count(), "each standard type is listed once");
    }

    @Test
    @Transactional
    void getParamTypes_includesAllThreeSubtypes() {
        List<ParamType> types = templateExecutionService.getParamTypes();
        assertTrue(types.stream().anyMatch(t -> t instanceof StandardParamType));
        assertTrue(types.stream().anyMatch(t -> t instanceof ListParamType));
        assertTrue(types.stream().anyMatch(t -> t instanceof CompositeParamType));
    }

    @Test
    @Transactional
    void getParamTypeByName_findsTheInMemoryStandardTypeAndIsNullForUnknown() {
        ParamType text = templateExecutionService.getParamTypeByName("text");
        assertNotNull(text, "the standard 'text' parameter type is code");
        assertInstanceOf(StandardParamType.class, text);
        assertEquals("text", text.getName());
        assertNull(text.getId(), "the in-memory type, not the seeded row");

        assertNull(templateExecutionService.getParamTypeByName("IT-no-such-type"));
    }

    @Test
    @Transactional
    void getParamType_byIdReturnsTypeAndNullForUnknownId() {
        ParamType list = templateExecutionService.getParamTypeByName("buoy_type_list");
        assertEquals(list.getId(), templateExecutionService.getParamType(list.getId()).getId());
        assertNull(templateExecutionService.getParamType(-999_999));
    }

    // --------------------------------------------------------------------------------------- //
    // createParamType
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createParamType_blankNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.createParamType(new StandardParamType("")));
        assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.createParamType(new StandardParamType((String) null)));
    }

    @Test
    @Transactional
    void createParamType_refusesAStandardTypesName() {
        // A row with that name would never be read: the in-memory type wins by name
        assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.createParamType(new StandardParamType("text")));
        ListParamType list = new ListParamType();
        list.setName("number");
        assertThrows(IllegalArgumentException.class, () -> templateExecutionService.createParamType(list));
    }

    @Test
    @Transactional
    void createParamType_standardTypePersists() {
        ParamType created = templateExecutionService.createParamType(new StandardParamType("IT-std-type"));
        Integer id = created.getId();
        assertNotNull(id);

        em.flush();
        em.clear();

        ParamType reloaded = em.find(ParamType.class, id);
        assertInstanceOf(StandardParamType.class, reloaded);
        assertEquals("IT-std-type", reloaded.getName());
    }

    @Test
    @Transactional
    void createParamType_compositeTypePersistsTemplateParams() {
        CompositeParamType composite = new CompositeParamType();
        composite.setName("IT-composite-type");
        TemplateParam tp = new TemplateParam();
        tp.setParamId("position");
        tp.setType("text");
        tp.setMandatory(true);
        composite.getTemplateParams().add(tp);

        ParamType created = templateExecutionService.createParamType(composite);
        Integer id = created.getId();

        em.flush();
        em.clear();

        ParamType reloaded = em.find(ParamType.class, id);
        assertInstanceOf(CompositeParamType.class, reloaded);
        CompositeParamType c = (CompositeParamType) reloaded;
        assertEquals(1, c.getTemplateParams().size());
        assertEquals("position", c.getTemplateParams().get(0).getParamId());
        assertEquals("text", c.getTemplateParams().get(0).getType());
        assertTrue(c.getTemplateParams().get(0).isMandatory());
    }

    @Test
    @Transactional
    void createParamType_listTypeResolvesValuesByKey() {
        // Pick a seeded ListParamType that actually has values, and reference its first entry by key.
        ListParamType seeded = templateExecutionService.getParamTypes().stream()
                .filter(t -> t instanceof ListParamType)
                .map(t -> (ListParamType) t)
                .filter(t -> !t.getValues().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a seeded ListParamType with values"));

        DictionaryEntry seededEntry = seeded.getValues().get(0);
        String key = seededEntry.getKey();
        Integer seededEntryId = seededEntry.getId();

        ListParamType listType = new ListParamType();
        listType.setName("IT-list-type");
        DictionaryEntry ref = new DictionaryEntry();
        ref.setKey(key); // transient entry, no id -> persistedList resolves it by key
        listType.getValues().add(ref);

        ParamType created = templateExecutionService.createParamType(listType);
        Integer id = created.getId();

        em.flush();
        em.clear();

        ListParamType reloaded = (ListParamType) em.find(ParamType.class, id);
        assertEquals(1, reloaded.getValues().size(), "the value must be resolved to the persisted entry");
        assertEquals(seededEntryId, reloaded.getValues().get(0).getId(),
                "createParamType must reuse the existing persisted dictionary entry, not create a new one");
    }

    @Test
    @Transactional
    void createParamType_listTypeDropsUnresolvableValues() {
        ListParamType listType = new ListParamType();
        listType.setName("IT-list-empty");
        DictionaryEntry ghost = new DictionaryEntry();
        ghost.setKey("IT-key-that-does-not-exist");
        listType.getValues().add(ghost);

        ParamType created = templateExecutionService.createParamType(listType);
        em.flush();
        em.clear();

        ListParamType reloaded = (ListParamType) em.find(ParamType.class, created.getId());
        assertTrue(reloaded.getValues().isEmpty(),
                "unresolvable dictionary entries are dropped by DictionaryService.persistedList");
    }

    /**
     * DICT6: a list value given by its key alone, whose key two dictionaries hold, is refused with a sentence naming
     * them (400 over REST); the first row found used to be bound, whichever dictionary it was in. Given by its id,
     * the value is bound to that entry.
     */
    @Test
    @Transactional
    void createParamType_aListValueKeyTwoDictionariesHoldIsRefusedNamingThem() {
        dictionaryEntry("it-dict6-b", "it.dict6.shared");
        DictionaryEntry a = dictionaryEntry("it-dict6-a", "it.dict6.shared");

        ListParamType byKey = new ListParamType();
        byKey.setName("IT-list-shared");
        byKey.getValues().add(valueRef(null, "it.dict6.shared"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.createParamType(byKey));
        assertEquals("The dictionary key it.dict6.shared is in more than one dictionary (it-dict6-a, it-dict6-b); "
                + "give the value's id to say which", ex.getMessage());

        // The right path: the value given by its id
        ListParamType byId = new ListParamType();
        byId.setName("IT-list-shared-by-id");
        byId.getValues().add(valueRef(a.getId(), "it.dict6.shared"));
        Integer id = templateExecutionService.createParamType(byId).getId();
        em.flush();
        em.clear();
        assertEquals(List.of(a.getId()), ((ListParamType) em.find(ParamType.class, id)).getValues().stream()
                .map(DictionaryEntry::getId).toList());
    }

    /**
     * DICT6: on an update, a list value given by its key alone is the stored type's value of that key, the entry of
     * the type's own dictionary, though another dictionary holds the key too (and was created first, so a lookup by
     * the key alone would find its row first)
     */
    @Test
    @Transactional
    void updateParamType_aListValueGivenByItsKeyKeepsTheStoredEntry() {
        dictionaryEntry("it-dict6-other", "it.dict6.kept");
        DictionaryEntry own = dictionaryEntry("it-dict6-own", "it.dict6.kept");
        ListParamType list = new ListParamType();
        list.setName("IT-list-own");
        list.getValues().add(valueRef(own.getId(), "it.dict6.kept"));
        Integer id = templateExecutionService.createParamType(list).getId();
        em.flush();

        ListParamType update = new ListParamType();
        update.setId(id);
        update.setName("IT-list-own");
        update.getValues().add(valueRef(null, "it.dict6.kept"));
        templateExecutionService.updateParamType(update);
        em.flush();
        em.clear();

        assertEquals(List.of(own.getId()), ((ListParamType) em.find(ParamType.class, id)).getValues().stream()
                .map(DictionaryEntry::getId).toList());
    }

    /** Persists a dictionary of the name holding the key, and returns the key's entry */
    private DictionaryEntry dictionaryEntry(String dictionaryName, String key) {
        Dictionary dictionary = new Dictionary();
        dictionary.setName(dictionaryName);
        DictionaryEntry entry = dictionary.createEntry(key);
        entry.checkCreateDesc("en").setValue(key);
        em.persist(dictionary);
        em.flush();
        return entry;
    }

    /** A list value as a request names it: by its id, or by its key alone */
    private static DictionaryEntry valueRef(Integer id, String key) {
        DictionaryEntry ref = new DictionaryEntry();
        ref.setId(id);
        ref.setKey(key);
        return ref;
    }

    @Test
    @Transactional
    void createParamType_aTakenNameIsRefusedBeforeTheUniqueKey() {
        templateExecutionService.createParamType(new StandardParamType("IT-dup-name"));
        em.flush();

        // ERR4: refused with a sentence (400 over REST), not left to the unique key at the flush (500)
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.createParamType(new StandardParamType("IT-dup-name")));
        // The same spelling: the message names it once, as it always has
        assertEquals("Another parameter type has the name IT-dup-name", ex.getMessage());
    }

    @Test
    @Transactional
    void createParamType_aNameTakenInOtherCapitalLettersIsRefused() {
        ListParamType lights = new ListParamType();
        lights.setName("IT-Lights");
        templateExecutionService.createParamType(lights);
        em.flush();

        ListParamType twin = new ListParamType();
        twin.setName("it-lights");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.createParamType(twin),
                "a name another type has in other capital letters is taken");
        assertEquals("Another parameter type has the name \"IT-Lights\" (given: \"it-lights\")", ex.getMessage(),
                "both spellings are named, so the type in the way can be told from the name refused");
    }

    @Test
    @Transactional
    void createParamType_refusesAStandardTypesNameInOtherCapitalLetters() {
        ListParamType list = new ListParamType();
        list.setName("Text");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.createParamType(list));
        assertEquals("Parameter type Text is a standard type", ex.getMessage());
        assertTrue(TemplateExecutionService.isStandardParamType("NUMBER"), "the standard-type check ignores case");
        assertInstanceOf(StandardParamType.class, templateExecutionService.getParamTypeByName("text"));
        assertNull(templateExecutionService.getParamTypeByName("TEXT"),
                "which type a name reads stays exact: 'TEXT' is no type's name");
    }

    @Test
    @Transactional
    void updateParamType_aRenameToANameTakenInOtherCapitalLettersIsRefusedAndAnotherSpellingOfItsOwnIsAllowed() {
        ListParamType lights = new ListParamType();
        lights.setName("IT-Lights");
        Integer lightsId = templateExecutionService.createParamType(lights).getId();
        ListParamType buoys = new ListParamType();
        buoys.setName("IT-Buoys");
        Integer buoysId = templateExecutionService.createParamType(buoys).getId();
        em.flush();

        ListParamType rename = new ListParamType();
        rename.setId(buoysId);
        rename.setName("it-LIGHTS");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.updateParamType(rename));
        assertEquals("Another parameter type has the name \"IT-Lights\" (given: \"it-LIGHTS\")", ex.getMessage());

        ListParamType ownSpelling = new ListParamType();
        ownSpelling.setId(lightsId);
        ownSpelling.setName("IT-LIGHTS");
        templateExecutionService.updateParamType(ownSpelling);
        em.flush();
        em.clear();
        assertEquals("IT-LIGHTS", em.find(ParamType.class, lightsId).getName(),
                "a type may be renamed to another spelling of its own name");
        assertEquals("IT-Buoys", em.find(ParamType.class, buoysId).getName(), "the refused rename changed nothing");
    }

    @Test
    @Transactional
    void updateParamType_typesWhoseNamesDifferOnlyInCaseCanStillBeSavedUnderTheirNames() {
        // Rows an earlier version let be made: the names differ only in case
        ListParamType upper = new ListParamType();
        upper.setName("IT-Twin");
        em.persist(upper);
        ListParamType lower = new ListParamType();
        lower.setName("it-twin");
        em.persist(lower);
        em.flush();

        ListParamType keep = new ListParamType();
        keep.setId(upper.getId());
        keep.setName("IT-Twin");
        templateExecutionService.updateParamType(keep);
        em.flush();
        em.clear();
        assertEquals("IT-Twin", em.find(ParamType.class, upper.getId()).getName(),
                "a type keeping its name is saved, though another has it in other capital letters");
        assertEquals(upper.getId(), templateExecutionService.getParamTypeByName("IT-Twin").getId(),
                "the look-up by name is exact");
        assertEquals(lower.getId(), templateExecutionService.getParamTypeByName("it-twin").getId(),
                "the look-up by name is exact");
    }

    /**
     * A composite type's parameters are keyed by their id when a template is executed, so two parameters with one id
     * would share one value. The editor's create and update (TemplateExecutionRestService) refuse a body that holds
     * an id twice with a sentence naming the id (400 over REST, RequiredFieldRefusalsITTest); ids that differ, if
     * only in case, pass, and a type that is not composite has nothing to check
     */
    @Test
    void refuseRepeatedTemplateParamIds_namesTheFirstRepeatedId() {
        CompositeParamType composite = new CompositeParamType();
        composite.setName("IT-dup-param");
        composite.getTemplateParams().add(templateParam("depth", "number"));
        composite.getTemplateParams().add(templateParam("kind", "text"));
        composite.getTemplateParams().add(templateParam("kind", "number"));
        composite.getTemplateParams().add(templateParam("depth", "text"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TemplateExecutionService.refuseRepeatedTemplateParamIds(composite));
        assertEquals("Parameter type IT-dup-param has more than one parameter with the id kind", ex.getMessage());

        // The right path: distinct ids, in any order and if only in case
        CompositeParamType distinct = new CompositeParamType();
        distinct.setName("IT-distinct-params");
        distinct.getTemplateParams().add(templateParam("kind", "text"));
        distinct.getTemplateParams().add(templateParam("depth", "number"));
        distinct.getTemplateParams().add(templateParam("Kind", "number"));
        TemplateExecutionService.refuseRepeatedTemplateParamIds(distinct);
        TemplateExecutionService.refuseRepeatedTemplateParamIds(new CompositeParamType());
        ListParamType list = new ListParamType();
        list.setName("IT-list");
        TemplateExecutionService.refuseRepeatedTemplateParamIds(list);
    }

    // --------------------------------------------------------------------------------------- //
    // updateParamType
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateParamType_renamesExistingType() {
        ParamType created = templateExecutionService.createParamType(new StandardParamType("IT-upd-before"));
        Integer id = created.getId();
        em.flush();

        StandardParamType update = new StandardParamType("IT-upd-after");
        update.setId(id);
        templateExecutionService.updateParamType(update);

        em.flush();
        em.clear();

        assertEquals("IT-upd-after", em.find(ParamType.class, id).getName());
    }

    @Test
    @Transactional
    void updateParamType_compositeReplacesTemplateParams() {
        CompositeParamType composite = new CompositeParamType();
        composite.setName("IT-comp-upd");
        TemplateParam original = new TemplateParam();
        original.setParamId("old");
        original.setType("text");
        composite.getTemplateParams().add(original);
        ParamType created = templateExecutionService.createParamType(composite);
        Integer id = created.getId();
        em.flush();

        CompositeParamType update = new CompositeParamType();
        update.setId(id);
        update.setName("IT-comp-upd");
        TemplateParam replacement = new TemplateParam();
        replacement.setParamId("new");
        replacement.setType("number");
        update.getTemplateParams().add(replacement);

        templateExecutionService.updateParamType(update);
        em.flush();
        em.clear();

        CompositeParamType reloaded = (CompositeParamType) em.find(ParamType.class, id);
        assertEquals(1, reloaded.getTemplateParams().size());
        assertEquals("new", reloaded.getTemplateParams().get(0).getParamId());
        assertEquals("number", reloaded.getTemplateParams().get(0).getType());
    }

    @Test
    @Transactional
    void updateParamType_unknownIdThrows() {
        StandardParamType orphan = new StandardParamType("IT-orphan");
        orphan.setId(-999_999);
        // TMPL5 (fixed): getParamType(id) returns null for an unknown id, and updateParamType now
        // reports it with a meaningful IllegalArgumentException instead of dereferencing null (NPE).
        assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.updateParamType(orphan));
    }

    // --------------------------------------------------------------------------------------- //
    // deleteParamType
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteParamType_removesExistingTypeAndReturnsTrue() {
        ParamType created = templateExecutionService.createParamType(new StandardParamType("IT-del-type"));
        Integer id = created.getId();
        em.flush();

        assertTrue(templateExecutionService.deleteParamType(id));
        em.flush();
        em.clear();

        assertNull(em.find(ParamType.class, id), "the parameter type must be removed from the database");
    }

    @Test
    @Transactional
    void deleteParamType_returnsFalseForUnknownId() {
        assertFalse(templateExecutionService.deleteParamType(-999_999));
    }

    /**
     * TMPL16: a type a template category or another composite type names is refused with a 409 that names them, and
     * stays. The uses are by name, with no foreign key, so the delete used to go through and leave them naming a type
     * that did not exist.
     */
    @Test
    @Transactional
    void deleteParamType_aTypeInUseIsRefusedWithA409NamingItsUsers() {
        ListParamType list = new ListParamType();
        list.setName("IT-used-list");
        Integer listId = templateExecutionService.createParamType(list).getId();
        CompositeParamType composite = new CompositeParamType();
        composite.setName("IT-using-composite");
        composite.getTemplateParams().add(templateParam("kind", "IT-used-list"));
        composite.getTemplateParams().add(templateParam("depth", "number"));
        Integer compositeId = templateExecutionService.createParamType(composite).getId();
        Category category = templateCategory("IT-using-category", templateParam("kind", "IT-used-list"),
                templateParam("depth", "IT-using-composite"));
        em.flush();

        NiordWebException listRefusal = assertThrows(NiordWebException.class,
                () -> templateExecutionService.deleteParamType(listId));
        assertEquals(409, listRefusal.getStatusCode().value());
        assertEquals("The parameter type 'IT-used-list' is used by the template category 'IT-using-category' and the"
                + " composite type 'IT-using-composite'; remove it from them first", listRefusal.getReason());

        NiordWebException compositeRefusal = assertThrows(NiordWebException.class,
                () -> templateExecutionService.deleteParamType(compositeId));
        assertEquals(409, compositeRefusal.getStatusCode().value());
        assertEquals("The parameter type 'IT-using-composite' is used by the template category 'IT-using-category';"
                + " remove it from them first", compositeRefusal.getReason());

        em.flush();
        em.clear();
        assertNotNull(em.find(ParamType.class, listId), "the list type stays");
        assertNotNull(em.find(ParamType.class, compositeId), "the composite type stays");
        assertNotNull(em.find(Category.class, category.getId()), "the category stays");
    }

    /**
     * TMPL16, the right path: once the category and the composite no longer name it, the type is deleted; and a
     * composite type that names only itself is deleted too
     */
    @Test
    @Transactional
    void deleteParamType_aTypeNoLongerUsedIsDeleted() {
        ListParamType list = new ListParamType();
        list.setName("IT-freed-list");
        Integer listId = templateExecutionService.createParamType(list).getId();
        CompositeParamType composite = new CompositeParamType();
        composite.setName("IT-freeing-composite");
        composite.getTemplateParams().add(templateParam("kind", "IT-freed-list"));
        Integer compositeId = templateExecutionService.createParamType(composite).getId();
        Category category = templateCategory("IT-freeing-category", templateParam("kind", "IT-freed-list"));
        em.flush();
        assertThrows(NiordWebException.class, () -> templateExecutionService.deleteParamType(listId));

        // The users take it out: they name another type now
        em.find(Category.class, category.getId()).getTemplateParams().getFirst().setType("text");
        em.find(CompositeParamType.class, compositeId).getTemplateParams().getFirst().setType("text");
        em.flush();

        assertTrue(templateExecutionService.deleteParamType(listId));
        em.flush();
        em.clear();
        assertNull(em.find(ParamType.class, listId), "the list type is deleted");
        assertNotNull(em.find(ParamType.class, compositeId), "the composite type stays");

        // A composite that names itself is no use by another type
        CompositeParamType self = new CompositeParamType();
        self.setName("IT-self-composite");
        self.getTemplateParams().add(templateParam("again", "IT-self-composite"));
        Integer selfId = templateExecutionService.createParamType(self).getId();
        // As a delete request finds it: loaded afresh. Removed in the context that created it, the composite's
        // template parameter row is deleted before its join row at the flush, which the foreign key refuses
        em.flush();
        em.clear();
        assertTrue(templateExecutionService.deleteParamType(selfId));
        em.flush();
        em.clear();
        assertNull(em.find(ParamType.class, selfId), "the composite naming itself is deleted");
    }

    private static TemplateParam templateParam(String paramId, String type) {
        TemplateParam param = new TemplateParam();
        param.setParamId(paramId);
        param.setType(type);
        return param;
    }

    private Category templateCategory(String name, TemplateParam... params) {
        Category category = new Category();
        category.setType(CategoryType.TEMPLATE);
        category.checkCreateDesc("en").setName(name);
        category.getTemplateParams().addAll(List.of(params));
        return categoryService.createCategory(category, null);
    }

    /**
     * A create body must not carry an id: {@code ParamType(ParamTypeVo)} copies it and {@code saveEntity()}
     * merges a persisted entity, so a "create" with a fresh name would silently overwrite the parameter type
     * holding that id.
     */
    @Test
    @Transactional
    void createParamType_rejectsTypeWithExistingId() {
        ParamType seeded = templateExecutionService.getParamTypeByName("buoy_type_list");
        assertNotNull(seeded, "the 'buoy_type_list' parameter type is seeded");

        StandardParamType forged = new StandardParamType("IT-forged-type");
        forged.setId(seeded.getId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.createParamType(forged));
        assertTrue(ex.getMessage().contains("existing ID"),
                "the rejection must mention the existing id");
    }

}
