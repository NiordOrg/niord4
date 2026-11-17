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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.core.promulgation.vo.AudioMessagePromulgationVo;
import org.niord.core.promulgation.vo.NavtexMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.message.CategoryVo;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.core.script.ScriptResourceService;
import org.niord.test.ITTest;
import org.niord.test.ScriptResourceFixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link TemplateExecutionService#executeTemplate} and
 * {@link TemplateExecutionService#executeScriptResources} — the template-driven message-part
 * auto-generation pipeline (Freemarker render → {@link FieldTemplateProcessor} parse → Nashorn
 * field assignment), together with the JavaScript update path and the promulgation hooks.
 *
 * <p>Each test runs inside a {@link TestTransaction}. The Freemarker/JavaScript resources are
 * test-only fixtures under {@code src/test/resources/templates/it} and {@code src/test/resources/
 * test-scripts}; since C4.2a the {@code FmTemplateLoader} and {@code JsResourceService} read the database
 * only, so each test first adds them as {@code ScriptResource} rows ({@link ScriptResourceFixtures}, committed once).
 *
 * <p><b>Field-template fixtures.</b>
 * <ul>
 *   <li>{@code templates/it/template-exec-it.ftl} sets {@code part.getDesc('en').subject} (text,
 *       {@code "Light ${params.name!'X'} unlit"}) and {@code part.getDesc('en').details} (html).</li>
 *   <li>{@code test-scripts/template-exec-it.js} sets {@code part.getDesc('en').details} to
 *       {@code "JS-" + params.get('name')}.</li>
 *   <li>{@code templates/it/template-exec-append-it.ftl} appends {@code "appended-line"} to the
 *       subject (update="append").</li>
 *   <li>{@code templates/it/template-exec-msgprom-it.ftl} appends {@code "MSGPROMMARKER"} to
 *       {@code message.promulgation('navtex').text}.</li>
 *   <li>{@code templates/it/template-exec-typeprom-it.ftl} appends {@code "TYPEPROMMARKER"} to the
 *       per-type-loop {@code promulgation.text} binding.</li>
 * </ul>
 *
 * <p>The tests for the real Danish DGPS template pair (which ships only with the niord-dk
 * artifact) live in niord-dk's {@code DkDgpsTemplateITTest}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class TemplateExecutionServiceITTest extends ITTest {

    private static final String FM_PATH         = "templates/it/template-exec-it.ftl";
    private static final String JS_PATH         = "test-scripts/template-exec-it.js";
    private static final String FM_APPEND_PATH  = "templates/it/template-exec-append-it.ftl";
    private static final String FM_MSGPROM_PATH = "templates/it/template-exec-msgprom-it.ftl";
    private static final String FM_TYPEPROM_PATH = "templates/it/template-exec-typeprom-it.ftl";

    @Autowired
    TemplateExecutionService templateExecutionService;

    @Autowired
    ScriptExecutionService scriptExecutionService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @Autowired
    ScriptResourceService resourceService;

    @PersistenceContext
    EntityManager em;

    @BeforeEach
    void addTheTestTemplates() {
        ScriptResourceFixtures.ensure(resourceService, FM_PATH, JS_PATH, FM_APPEND_PATH, FM_MSGPROM_PATH,
                FM_TYPEPROM_PATH);
    }

    // --------------------------------------------------------------------------------------- //
    // helpers
    // --------------------------------------------------------------------------------------- //

    private SystemMessageVo newMessage() {
        SystemMessageVo m = new SystemMessageVo();
        m.setMainType(MainType.NW);
        m.setType(Type.LOCAL_WARNING);
        m.setStatus(Status.DRAFT);
        m.setAutoTitle(false);
        return m;
    }

    private MessagePartVo detailsPartWithEnDesc() {
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        part.checkCreateDesc("en");
        return part;
    }

    private Map<String, Object> contextFor(SystemMessageVo m, MessagePartVo part, String name) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("message", m);
        ctx.put("part", part);
        Map<String, Object> params = new HashMap<>();
        if (name != null) {
            params.put("name", name);
        }
        ctx.put("params", params);
        return ctx;
    }

    /** Persists a TEMPLATE category referencing the given script resource path. */
    private Category persistTemplateCategory(String name, String scriptPath) {
        Category cat = new Category();
        cat.setType(CategoryType.TEMPLATE);
        cat.checkCreateDesc("en").setName(name);
        if (scriptPath != null) {
            cat.getScriptResourcePaths().add(scriptPath);
        }
        Category created = categoryService.createCategory(cat, null);
        em.flush();
        return created;
    }

    private NavtexMessagePromulgationVo navtexPromulgation(boolean promulgate) {
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId("navtex");
        NavtexMessagePromulgationVo navtex = new NavtexMessagePromulgationVo(type);
        navtex.setPromulgate(promulgate);
        return navtex;
    }

    private AudioMessagePromulgationVo audioPromulgation(boolean promulgate, String text) {
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId("audio");
        AudioMessagePromulgationVo audio = new AudioMessagePromulgationVo(type);
        audio.setPromulgate(promulgate);
        audio.setText(text);
        return audio;
    }

    // --------------------------------------------------------------------------------------- //
    // executeScriptResources — Freemarker path
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void executeScriptResources_freemarkerSetsSubjectAndDetails() throws Exception {
        SystemMessageVo m = newMessage();
        MessagePartVo part = detailsPartWithEnDesc();
        m.checkCreateParts().add(part);

        scriptExecutionService.executeScriptResources(List.of(FM_PATH), contextFor(m, part, "Alpha"));

        // text format -> whitespace compressed/trimmed to a clean single line.
        assertEquals("Light Alpha unlit", part.getDesc("en").getSubject());
        // html format -> markup preserved.
        assertTrue(part.getDesc("en").getDetails().contains("<p>The light is unlit.</p>"),
                "actual details=" + part.getDesc("en").getDetails());
    }

    @Test
    @Transactional
    void executeScriptResources_freemarkerUsesDefaultWhenParamMissing() throws Exception {
        SystemMessageVo m = newMessage();
        MessagePartVo part = detailsPartWithEnDesc();
        m.checkCreateParts().add(part);

        // No "name" param -> the template's ${params.name!'X'} default applies.
        scriptExecutionService.executeScriptResources(List.of(FM_PATH), contextFor(m, part, null));

        assertEquals("Light X unlit", part.getDesc("en").getSubject());
    }

    @Test
    @Transactional
    void executeScriptResources_appendsToExistingField() throws Exception {
        SystemMessageVo m = newMessage();
        MessagePartVo part = detailsPartWithEnDesc();
        m.checkCreateParts().add(part);
        Map<String, Object> ctx = contextFor(m, part, "Alpha");

        scriptExecutionService.executeScriptResources(List.of(FM_PATH), ctx);      // subject = "Light Alpha unlit"
        scriptExecutionService.executeScriptResources(List.of(FM_APPEND_PATH), ctx); // append "appended-line"

        String subject = part.getDesc("en").getSubject();
        assertEquals("Light Alpha unlit\nappended-line", subject);
    }

    // --------------------------------------------------------------------------------------- //
    // executeScriptResources — JavaScript path
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void executeScriptResources_javaScriptMutatesPart() throws Exception {
        SystemMessageVo m = newMessage();
        MessagePartVo part = detailsPartWithEnDesc();
        m.checkCreateParts().add(part);

        scriptExecutionService.executeScriptResources(List.of(JS_PATH), contextFor(m, part, "Beta"));

        assertEquals("JS-Beta", part.getDesc("en").getDetails());
    }

    @Test
    @Transactional
    void executeScriptResources_runsFreemarkerThenJavaScriptInOrder() throws Exception {
        SystemMessageVo m = newMessage();
        MessagePartVo part = detailsPartWithEnDesc();
        m.checkCreateParts().add(part);

        // FM sets subject + details; the subsequent JS overwrites details only.
        scriptExecutionService.executeScriptResources(List.of(FM_PATH, JS_PATH), contextFor(m, part, "Gamma"));

        assertEquals("Light Gamma unlit", part.getDesc("en").getSubject());
        assertEquals("JS-Gamma", part.getDesc("en").getDetails());
    }

    // --------------------------------------------------------------------------------------- //
    // executeScriptResources — dispatch / robustness
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void executeScriptResources_ignoresPathsThatAreNeitherFmNorJs() throws Exception {
        SystemMessageVo m = newMessage();
        MessagePartVo part = detailsPartWithEnDesc();
        m.checkCreateParts().add(part);

        // ScriptResource.path2type returns null for a ".txt" path => silently skipped; the FM still runs.
        scriptExecutionService.executeScriptResources(
                List.of("templates/it/not-a-script.txt", FM_PATH), contextFor(m, part, "Delta"));

        assertEquals("Light Delta unlit", part.getDesc("en").getSubject());
    }

    @Test
    @Transactional
    void executeScriptResources_unknownFreemarkerTemplateThrows() {
        SystemMessageVo m = newMessage();
        MessagePartVo part = detailsPartWithEnDesc();
        m.checkCreateParts().add(part);

        assertThrows(Exception.class, () -> scriptExecutionService.executeScriptResources(
                List.of("templates/it/does-not-exist.ftl"), contextFor(m, part, "x")));
    }

    @Test
    @Transactional
    void executeScriptResources_unknownJavaScriptResourceThrows() {
        SystemMessageVo m = newMessage();
        MessagePartVo part = detailsPartWithEnDesc();
        m.checkCreateParts().add(part);

        assertThrows(Exception.class, () -> scriptExecutionService.executeScriptResources(
                List.of("test-scripts/does-not-exist.js"), contextFor(m, part, "x")));
    }

    @Test
    @Transactional
    void executeScriptResources_fieldTemplateForMissingDescIsSwallowed() throws Exception {
        SystemMessageVo m = newMessage();
        // A DETAILS part with NO descriptions: the field path part.getDesc('en') resolves to null,
        // so the Nashorn assignment fails. TemplateExecutionService catches and logs ScriptException
        // ("Prefer robustness over correctness"), so no exception escapes and nothing is set.
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        m.checkCreateParts().add(part);

        scriptExecutionService.executeScriptResources(List.of(FM_PATH), contextFor(m, part, "Alpha"));

        assertNull(part.getDescs(), "no description should have been created by the failed assignment");
    }

    // --------------------------------------------------------------------------------------- //
    // executeTemplate — orchestration with an explicit template category
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void executeTemplate_singleTemplateCreatesDetailPartAndSetsFields() throws Exception {
        Category template = new Category();
        template.setType(CategoryType.TEMPLATE);
        template.getScriptResourcePaths().add(FM_PATH);

        SystemMessageVo m = newMessage();
        List<Object> params = new ArrayList<>();
        Map<String, Object> p = new HashMap<>();
        p.put("name", "Echo");
        params.add(p);

        SystemMessageVo result = templateExecutionService.executeTemplate(template, m, params);

        assertSame(m, result, "executeTemplate mutates and returns the same message instance");
        List<MessagePartVo> details = result.partsOfType(MessagePartType.DETAILS);
        assertEquals(1, details.size(), "exactly one DETAILS part is auto-created");
        assertEquals("Light Echo unlit", details.get(0).getDesc("en").getSubject());
        assertEquals(Boolean.TRUE, details.get(0).getHideSubject(),
                "a single DETAILS part hides its subject");
    }

    @Test
    @Transactional
    void executeTemplate_nullParamsUsesTemplateDefault() throws Exception {
        Category template = new Category();
        template.setType(CategoryType.TEMPLATE);
        template.getScriptResourcePaths().add(FM_PATH);

        SystemMessageVo result = templateExecutionService.executeTemplate(template, newMessage(), null);

        assertEquals("Light X unlit",
                result.partsOfType(MessagePartType.DETAILS).get(0).getDesc("en").getSubject());
    }

    @Test
    @Transactional
    void executeTemplate_reusesExistingSingleDetailPart() throws Exception {
        Category template = new Category();
        template.setType(CategoryType.TEMPLATE);
        template.getScriptResourcePaths().add(FM_PATH);

        SystemMessageVo m = newMessage();
        m.checkCreateParts().add(detailsPartWithEnDesc());

        List<Object> params = new ArrayList<>();
        params.add(Map.of("name", "Foxtrot"));

        SystemMessageVo result = templateExecutionService.executeTemplate(template, m, params);

        List<MessagePartVo> details = result.partsOfType(MessagePartType.DETAILS);
        assertEquals(1, details.size(), "the existing DETAILS part is reused, not duplicated");
        assertEquals("Light Foxtrot unlit", details.get(0).getDesc("en").getSubject());
        assertEquals(Boolean.TRUE, details.get(0).getHideSubject());
    }

    @Test
    @Transactional
    void executeTemplate_withTwoDetailPartsDoesNotHideSubject() throws Exception {
        Category template = new Category();
        template.setType(CategoryType.TEMPLATE);
        template.getScriptResourcePaths().add(FM_PATH);

        SystemMessageVo m = newMessage();
        m.checkCreateParts().add(detailsPartWithEnDesc()); // part 0
        m.checkCreateParts().add(detailsPartWithEnDesc()); // part 1

        List<Object> params = new ArrayList<>();
        params.add(Map.of("name", "Golf"));

        SystemMessageVo result = templateExecutionService.executeTemplate(template, m, params);

        List<MessagePartVo> details = result.partsOfType(MessagePartType.DETAILS);
        assertEquals(2, details.size());
        // The template applies to the first DETAILS part; hideSubject is false because there are 2 parts.
        assertEquals("Light Golf unlit", details.get(0).getDesc("en").getSubject());
        assertEquals(Boolean.FALSE, details.get(0).getHideSubject());
    }

    @Test
    @Transactional
    void executeTemplate_nonTemplateCategoryThrows() {
        Category notTemplate = new Category();
        notTemplate.setType(CategoryType.CATEGORY);
        assertThrows(IllegalArgumentException.class,
                () -> templateExecutionService.executeTemplate(notTemplate, newMessage(), null));
    }

    // --------------------------------------------------------------------------------------- //
    // executeTemplate — resolving template categories from the message itself
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void executeTemplate_nullCategoryUsesMessageTemplateCategories() throws Exception {
        Category template = persistTemplateCategory("IT-from-message", FM_PATH);

        SystemMessageVo m = newMessage();
        CategoryVo cv = new CategoryVo();
        cv.setId(template.getId());
        List<CategoryVo> cats = new ArrayList<>();
        cats.add(cv);
        m.setCategories(cats);

        List<Object> params = new ArrayList<>();
        params.add(Map.of("name", "Hotel"));

        SystemMessageVo result = templateExecutionService.executeTemplate(null, m, params);

        assertEquals("Light Hotel unlit",
                result.partsOfType(MessagePartType.DETAILS).get(0).getDesc("en").getSubject());
    }

    @Test
    @Transactional
    void executeTemplate_nullCategoryIgnoresNonTemplateMessageCategories() throws Exception {
        // A plain CATEGORY-type category referenced by the message is filtered out, so no template runs.
        Category plain = new Category();
        plain.setType(CategoryType.CATEGORY);
        plain.checkCreateDesc("en").setName("IT-plain-cat");
        plain = categoryService.createCategory(plain, null);
        em.flush();

        SystemMessageVo m = newMessage();
        CategoryVo cv = new CategoryVo();
        cv.setId(plain.getId());
        List<CategoryVo> cats = new ArrayList<>();
        cats.add(cv);
        m.setCategories(cats);

        SystemMessageVo result = templateExecutionService.executeTemplate(null, m, null);

        assertTrue(result.partsOfType(MessagePartType.DETAILS).isEmpty(),
                "no DETAILS part is created when there are no TEMPLATE categories");
    }

    @Test
    @Transactional
    void executeTemplate_multipleTemplateCategoriesCreateDistinctDetailParts() throws Exception {
        // TMPL1 (fixed): checkCreateDetailParts creates one DETAILS part per template category, so
        // executing 2 template categories against a message with no detail parts yields 2 distinct
        // DETAILS parts (previously it created only one and tripped an IndexOutOfBoundsException).
        Category t1 = persistTemplateCategory("IT-multi-1", FM_PATH);
        Category t2 = persistTemplateCategory("IT-multi-2", FM_PATH);

        SystemMessageVo m = newMessage();
        List<CategoryVo> cats = new ArrayList<>();
        CategoryVo c1 = new CategoryVo();
        c1.setId(t1.getId());
        CategoryVo c2 = new CategoryVo();
        c2.setId(t2.getId());
        cats.add(c1);
        cats.add(c2);
        m.setCategories(cats);

        SystemMessageVo result = templateExecutionService.executeTemplate(null, m, null);

        List<MessagePartVo> details = result.partsOfType(MessagePartType.DETAILS);
        assertEquals(2, details.size(), "one DETAILS part is created per template category");
        assertEquals("Light X unlit", details.get(0).getDesc("en").getSubject());
        assertEquals("Light X unlit", details.get(1).getDesc("en").getSubject());
        assertEquals(Boolean.FALSE, details.get(0).getHideSubject(),
                "with two DETAILS parts the first part's subject is not hidden");
    }

    // --------------------------------------------------------------------------------------- //
    // executeTemplate — promulgation generation
    // --------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void executeTemplate_mainTemplatePopulatesPromulgationField() throws Exception {
        Category template = new Category();
        template.setType(CategoryType.TEMPLATE);
        template.getScriptResourcePaths().add(FM_MSGPROM_PATH);

        SystemMessageVo m = newMessage();
        m.checkCreatePromulgations().add(navtexPromulgation(false));

        SystemMessageVo result = templateExecutionService.executeTemplate(template, m, null);

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo) result.promulgation("navtex");
        assertNotNull(navtex.getText(), "the template should populate the navtex promulgation text");
        // NavtexPromulgationService.messagePromulgationGenerated upper-cases the text.
        assertTrue(navtex.getText().contains("MSGPROMMARKER"), "actual=[" + navtex.getText() + "]");
    }

    @Test
    @Transactional
    void executeTemplate_runsPerPromulgationTypeScripts() throws Exception {
        // Attach a script resource to the seeded navtex promulgation type (rolled back with the test).
        PromulgationType navtexType = promulgationTypeService.getPromulgationType("navtex");
        navtexType.getScriptResourcePaths().add(FM_TYPEPROM_PATH);
        em.flush();

        Category template = new Category();
        template.setType(CategoryType.TEMPLATE);
        template.getScriptResourcePaths().add(FM_PATH); // main template does not touch promulgations

        SystemMessageVo m = newMessage();
        // promulgate=true so getPromulgationTypes(message, true) includes navtex in the per-type loop.
        m.checkCreatePromulgations().add(navtexPromulgation(true));

        SystemMessageVo result = templateExecutionService.executeTemplate(template, m, null);

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo) result.promulgation("navtex");
        assertNotNull(navtex.getText(), "the per-type promulgation script should set the navtex text");
        assertTrue(navtex.getText().contains("TYPEPROMMARKER"), "actual=[" + navtex.getText() + "]");
    }

    /**
     * PROM20: a template run resets every promulgation's text and keeps its tick, and a template that writes
     * no audio text (none of the base product's does) left a ticked Audio with no text; the save keeps only a
     * promulgation with data, so the tick was dropped without a word. The run now gives the ticked Audio the text of
     * the English details (the seeded Audio type's language), and the message the save builds keeps it ticked.
     */
    @Test
    @Transactional
    void executeTemplate_tickedAudio_getsATextFromTheDetails_andTheSaveKeepsIt() throws Exception {
        Category template = new Category();
        template.setType(CategoryType.TEMPLATE);
        template.getScriptResourcePaths().add(FM_PATH); // writes the English details, no audio text

        SystemMessageVo m = newMessage();
        m.checkCreatePromulgations().add(audioPromulgation(true, "a text of an earlier run"));

        SystemMessageVo result = templateExecutionService.executeTemplate(template, m, null);

        AudioMessagePromulgationVo audio = (AudioMessagePromulgationVo) result.promulgation("audio");
        assertTrue(audio.isPromulgate(), "the run keeps the tick");
        assertEquals("The light is unlit.", audio.getText(),
                "the run's own text, made from the details; the earlier run's text is gone");

        // What the save stores: the entity it builds from the editor's message (MessageRestService, new Message(vo))
        Message saved = new Message(result);
        assertTrue(saved.getPromulgations().stream()
                        .anyMatch(p -> "audio".equals(p.getType().getTypeId()) && p.isPromulgate()),
                "the save keeps the ticked Audio");
    }

    /** PROM20's right path: an Audio that is not ticked is not given a text by the run, and the save leaves it out. */
    @Test
    @Transactional
    void executeTemplate_untickedAudio_getsNoText() throws Exception {
        Category template = new Category();
        template.setType(CategoryType.TEMPLATE);
        template.getScriptResourcePaths().add(FM_PATH);

        SystemMessageVo m = newMessage();
        m.checkCreatePromulgations().add(audioPromulgation(false, null));

        SystemMessageVo result = templateExecutionService.executeTemplate(template, m, null);

        AudioMessagePromulgationVo audio = (AudioMessagePromulgationVo) result.promulgation("audio");
        assertFalse(audio.isPromulgate());
        assertNull(audio.getText());
        assertTrue(new Message(result).getPromulgations().stream()
                .noneMatch(p -> "audio".equals(p.getType().getTypeId())));
    }
}
