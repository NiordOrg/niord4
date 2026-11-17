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
package org.niord.core.script;

import freemarker.template.TemplateException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.niord.core.script.FmTemplateService.ProcessFormat;
import org.niord.test.ITTest;
import org.niord.test.ScriptResourceFixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link FmTemplateService} — the full Freemarker rendering stack.
 *
 * <p>These tests render two test templates ({@code templates/it/script-it.ftl} and
 * {@code templates/it/script-it-text.ftl}) end to end, as database rows ({@link ScriptResourceFixtures}): since
 * C4.2a the {@link FmTemplateLoader} reads the database only, and a path with no row is not found, whatever the
 * class path holds. This exercises a lot of machinery that the Tier-1 directive unit tests cannot: the loader, the
 * directive {@code ?new()} instantiation path used by the real templates, the standard data
 * properties ({@code baseUri}/{@code country}/{@code draft}) injected by the service, the
 * dictionary &rarr; {@code ResourceBundle} &rarr; {@code text} model wiring (using the seeded
 * {@code template} dictionary), and PDF/text output generation.
 *
 * <p>The directive locale is forced to English via {@code language("en")} so the position
 * formatting (decimal separator) is deterministic regardless of the JVM default locale.
 *
 * <p>Framework-coupled (Quarkus DI + Hibernate + dictionary seed); see
 * {@code docs/framework-coupled-tests.md}.
 */
class FmTemplateServiceITTest extends ITTest {

    private static final String TEMPLATE = "templates/it/script-it.ftl";
    private static final String TEXT_TEMPLATE = "templates/it/script-it-text.ftl";
    private static final String EXECUTE_TEMPLATE = "templates/it/script-it-execute.ftl";

    @Autowired
    FmTemplateService templateService;

    @Autowired
    ScriptResourceService resourceService;

    @BeforeEach
    void addTheTestTemplates() {
        ScriptResourceFixtures.ensure(resourceService, TEMPLATE, TEXT_TEMPLATE, EXECUTE_TEMPLATE);
    }

    // ------------------------------------------------------------------ directive rendering

    @Test
    @Transactional
    void rendersDirectivesEndToEnd() throws Exception {
        String out = templateService.newFmTemplateBuilder()
                .templatePath(TEMPLATE)
                .language("en")
                .process();

        // LatLonDirective (dec, English -> period decimal separator)
        assertTrue(out.contains("POS=55° 00.000'N - 012° 00.000'E"), out);
        // QuoteDirective (plain double quotes)
        assertTrue(out.contains("QUOTE=\"hi\""), out);
        // TrailingDotDirective
        assertTrue(out.contains("DOT=foo."), out);
        // LineDirective (collapses the repeated whitespace)
        assertTrue(out.contains("hello world"), out);
        // Standard "draft" property defaults to false
        assertTrue(out.contains("DRAFT=false"), out);
    }

    /** A template is data an operator edits; through the real service it may not instantiate a class outside the directives */
    @Test
    @Transactional
    void newBuiltinRefusesClassesOutsideTheDirectivePackage() {
        TemplateException ex = assertThrows(TemplateException.class,
                () -> templateService.newFmTemplateBuilder()
                        .templatePath(EXECUTE_TEMPLATE)
                        .language("en")
                        .process());
        assertTrue(ex.getMessage().contains("is not allowed in a template"), ex.getMessage());
    }

    @Test
    @Transactional
    void draftPropertyOverrideIsHonoured() throws Exception {
        String out = templateService.newFmTemplateBuilder()
                .templatePath(TEMPLATE)
                .language("en")
                .data("draft", true)            // overrides the default false
                .process();
        assertTrue(out.contains("DRAFT=true"), out);
    }

    // ------------------------------------------------------------------ dictionary text model

    @Test
    @Transactional
    void rendersDictionaryBackedTextModel() throws Exception {
        // The seeded "template" dictionary supplies position.deg = "degrees".
        String out = templateService.newFmTemplateBuilder()
                .templatePath(TEXT_TEMPLATE)
                .dictionaryNames("template")
                .language("en")
                .process();
        assertTrue(out.contains("DEG=degrees"), out);
    }

    // ------------------------------------------------------------------ output formats

    @Test
    @Transactional
    void textFormatStreamsTheRenderedString() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        templateService.newFmTemplateBuilder()
                .templatePath(TEMPLATE)
                .language("en")
                .process(ProcessFormat.TEXT, baos);
        String out = baos.toString("UTF-8");
        assertTrue(out.contains("POS=55° 00.000'N - 012° 00.000'E"), out);
    }

    @Test
    @Transactional
    void pdfFormatProducesValidPdfBytes() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        templateService.newFmTemplateBuilder()
                .templatePath(TEMPLATE)
                .language("en")
                .process(ProcessFormat.PDF, baos);

        byte[] b = baos.toByteArray();
        assertTrue(b.length > 0);
        assertEquals('%', (char) b[0]);
        assertEquals('P', (char) b[1]);
        assertEquals('D', (char) b[2]);
        assertEquals('F', (char) b[3]);
    }

    // ------------------------------------------------------------------ error handling

    @Test
    @Transactional
    void blankTemplatePathRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> templateService.newFmTemplateBuilder().templatePath("  ").process());
        assertTrue(ex.getMessage().contains("Template path"), ex.getMessage());
    }

    @Test
    @Transactional
    void unknownTemplatePathThrowsIoException() {
        // Freemarker raises a TemplateNotFoundException (an IOException) when the template cannot be loaded.
        String path = "templates/it/no-such-template-" + System.nanoTime() + ".ftl";
        assertThrows(IOException.class,
                () -> templateService.newFmTemplateBuilder()
                        .templatePath(path)
                        .language("en")
                        .process());
        assertNull(resourceService.findByPath(path), "a failed render writes no row");
    }

    /** The test class path holds the file, but without its row the template is not found, and no row is written */
    @Test
    @Transactional
    void aTemplateWithoutARowIsNotReadFromTheClassPath() {
        ScriptResource row = resourceService.findByPath(EXECUTE_TEMPLATE);
        assertNotNull(row);
        resourceService.deleteScriptResource(row.getId());
        assertNotNull(getClass().getClassLoader().getResource(EXECUTE_TEMPLATE), "the file is on the class path");

        assertThrows(IOException.class,
                () -> templateService.newFmTemplateBuilder()
                        .templatePath(EXECUTE_TEMPLATE)
                        .language("en")
                        .process());
        assertNull(resourceService.findByPath(EXECUTE_TEMPLATE), "no copy-on-use: the render writes no row");
    }
}
