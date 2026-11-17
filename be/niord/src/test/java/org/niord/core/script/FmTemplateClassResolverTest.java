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
package org.niord.core.script;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.DomainService;
import org.niord.test.CountryBuildGroup;
import org.niord.web.map.MapImageCredit;

import java.io.StringWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a template may reach through {@code ?new} and {@code ?api}.
 *
 * <p>A template is data an operator edits in admin, so the configuration every template renders with
 * ({@link FmTemplateService#newConfiguration}) admits {@code ?new} only for the directives of
 * {@code org.niord.core.script.directive} ({@link DirectiveClassResolver}) and keeps {@code ?api} off.
 * The templates here are inline; one case goes through the template builder, the way every caller renders.
 */
@CountryBuildGroup
class FmTemplateClassResolverTest {

    private static final String NOT_ALLOWED = "is not allowed in a template";

    /** Renders the inline template with the configuration the service uses */
    private static String render(String template, Map<String, Object> data) throws Exception {
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("t.ftl", template);
        Configuration cfg = FmTemplateService.newConfiguration(loader);
        StringWriter out = new StringWriter();
        cfg.getTemplate("t.ftl", Locale.ENGLISH).process(data, out);
        return out.toString();
    }

    private static String render(String template) throws Exception {
        return render(template, new HashMap<>());
    }

    private static void assertRefused(String className) {
        TemplateException ex = assertThrows(TemplateException.class,
                () -> render("<#assign x = \"" + className + "\"?new()>${x}"));
        assertTrue(ex.getMessage().contains(NOT_ALLOWED), ex.getMessage());
    }

    // ------------------------------------------------------------------ the configuration

    @Test
    void theConfigurationPinsTheResolverAndKeepsApiOff() {
        Configuration cfg = FmTemplateService.newConfiguration(new StringTemplateLoader());

        assertSame(DirectiveClassResolver.INSTANCE, cfg.getNewBuiltinClassResolver());
        assertFalse(cfg.isAPIBuiltinEnabled());
    }

    // ------------------------------------------------------------------ ?new

    @Test
    void executeIsRefused() {
        TemplateException ex = assertThrows(TemplateException.class,
                () -> render("<#assign ex = \"freemarker.template.utility.Execute\"?new()>${ex(\"echo hi\")}"));
        assertTrue(ex.getMessage().contains(NOT_ALLOWED), ex.getMessage());
    }

    @Test
    void objectConstructorIsRefused() {
        assertRefused("freemarker.template.utility.ObjectConstructor");
    }

    @Test
    void aDirectiveRenders() throws Exception {
        String out = render("<#assign q = \"org.niord.core.script.directive.QuoteDirective\"?new()><@q text=\"hi\"/>");

        assertEquals("\"hi\"", out);
    }

    @Test
    void aLookalikePackageIsRefused() {
        assertRefused("org.niord.core.script.directiveX.Foo");
    }

    @Test
    void aSubPackageIsRefused() {
        assertRefused("org.niord.core.script.directive.sub.Foo");
    }

    @Test
    void theNameIsJudgedBeforeAnyClassIsLoaded() {
        assertFalse(DirectiveClassResolver.isDirective("org.niord.core.script.directiveX.Foo"));
        assertFalse(DirectiveClassResolver.isDirective("org.niord.core.script.directive.sub.Foo"));
        assertFalse(DirectiveClassResolver.isDirective("org.niord.core.script.directive."));
        assertFalse(DirectiveClassResolver.isDirective("org.niord.core.script.NiordAppObjectWrapper"));
        assertFalse(DirectiveClassResolver.isDirective(null));
        assertTrue(DirectiveClassResolver.isDirective("org.niord.core.script.directive.QuoteDirective"));
    }

    @Test
    void anUnknownDirectiveIsATemplateError() {
        TemplateException ex = assertThrows(TemplateException.class,
                () -> render("<#assign x = \"org.niord.core.script.directive.NoSuchDirective\"?new()>${x}"));
        assertInstanceOf(ClassNotFoundException.class, ex.getCause(), String.valueOf(ex.getCause()));
    }

    // ------------------------------------------------------------------ ?api

    @Test
    void apiIsOff() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("m", new HashMap<>(Map.of("a", "b")));

        assertThrows(TemplateException.class, () -> render("${m?api.size()}", data));

        // The same template renders once ?api is switched on, so the refusal is the setting's doing
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("t.ftl", "${m?api.size()}");
        Configuration enabled = FmTemplateService.newConfiguration(loader);
        enabled.setAPIBuiltinEnabled(true);
        StringWriter out = new StringWriter();
        enabled.getTemplate("t.ftl", Locale.ENGLISH).process(data, out);
        assertEquals("1", out.toString());
    }

    // ------------------------------------------------------------------ through the template builder

    @Test
    void theTemplateBuilderRefusesExecute() {
        String path = "templates/it/execute-unit.ftl";

        NiordApp app = mock(NiordApp.class);
        when(app.getLocale(any())).thenReturn(Locale.ENGLISH);

        ScriptResourceService resources = mock(ScriptResourceService.class);
        ScriptResource template = new ScriptResource();
        template.setPath(path);
        template.setContent("<#assign ex = \"freemarker.template.utility.Execute\"?new()>${ex(\"echo hi\")}");
        template.setCreated(new Date());
        template.setUpdated(new Date());
        when(resources.findAllScriptResourcePaths()).thenReturn(Set.of(path));
        when(resources.findByPath(path)).thenReturn(template);

        FmTemplateService service = new FmTemplateService(mock(DictionaryService.class), mock(DomainService.class),
                resources, app, new MapImageCredit());

        TemplateException ex = assertThrows(TemplateException.class,
                () -> service.newFmTemplateBuilder().templatePath(path).timeZone("UTC").process());
        assertTrue(ex.getMessage().contains(NOT_ALLOWED), ex.getMessage());
    }
}
