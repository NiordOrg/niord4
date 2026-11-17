/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.script.directive;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.TemplateException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Framework-neutral (Tier-1) unit tests for {@link MultiResourceBundleModel}.
 * <p>
 * {@code MultiResourceBundleModel} is a Freemarker hash / method model that wraps a list of
 * {@link ResourceBundle}s keyed by their language. The bundle actually used is resolved from the
 * locale of the <em>current Freemarker environment</em> (set in templates via {@code <#setting locale="xx">}),
 * falling back to the default language {@code "en"} when no matching bundle exists.
 * <p>
 * Because {@code getResourceBundle()} relies on {@link freemarker.core.Environment#getCurrentEnvironment()},
 * which only exists while a template is being processed, every assertion below drives the model through a
 * rendered Freemarker template rather than calling its methods directly. The tests exercise:
 * <ul>
 *   <li>method-style lookup {@code text("key")} resolving to the locale-matched bundle (exec),</li>
 *   <li>fallback to the default {@code "en"} bundle for an unknown locale,</li>
 *   <li>hash-style access {@code text.key} (invokeGenericGet),</li>
 *   <li>{@link java.text.MessageFormat} argument substitution {@code text("key", arg0, ...)},</li>
 *   <li>error behaviour for a missing key, and</li>
 *   <li>bundles built with a {@code null} locale mapping to the default language.</li>
 * </ul>
 * Only Freemarker is involved, so the tests are expected to survive a later Quarkus-to-Spring migration.
 */
public class MultiResourceBundleModelTest {

    /** A {@link java.util.ListResourceBundle} whose locale and contents are fully controllable. */
    static class TestBundle extends java.util.ListResourceBundle {
        private final java.util.Locale loc;
        private final Object[][] contents;
        TestBundle(java.util.Locale loc, Object[][] contents) { this.loc = loc; this.contents = contents; }
        protected Object[][] getContents() { return contents; }
        public java.util.Locale getLocale() { return loc; }
    }

    private final ResourceBundle en = new TestBundle(Locale.ENGLISH,
            new Object[][] { { "k", "english" }, { "greet", "Hello {0}" } });
    private final ResourceBundle da = new TestBundle(new Locale("da"),
            new Object[][] { { "k", "dansk" } });

    private final MultiResourceBundleModel model = new MultiResourceBundleModel(
            Arrays.asList(en, da),
            new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build());

    /** Renders the given template body, exposing the model under "text", using the English config locale. */
    private static String render(String body, Map<String, Object> data) throws Exception {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("t", body);
        cfg.setTemplateLoader(loader);
        cfg.setLocale(Locale.ENGLISH);

        StringWriter out = new StringWriter();
        cfg.getTemplate("t").process(data, out);
        return out.toString();
    }

    private Map<String, Object> data() {
        Map<String, Object> m = new HashMap<>();
        m.put("text", model);
        return m;
    }

    @Test
    void fallbackUsesConfiguredDefaultBeforeCallerOrder() throws Exception {
        var configured = new MultiResourceBundleModel(Arrays.asList(en, da),
                new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build(), "da");
        Assertions.assertEquals("dansk", render("<#setting locale='kl'>${text('k')}", Map.of("text", configured)));
    }

    @Test
    void absentDefaultUsesCallerBundleOrder() throws Exception {
        for (var ordered : List.of(List.of(en, da), List.of(da, en))) {
            var configured = new MultiResourceBundleModel(ordered,
                    new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build(), "kl");
            Assertions.assertEquals(ordered.getFirst().getString("k"),
                    render("<#setting locale='fr'>${text('k')}", Map.of("text", configured)));
        }
    }

    @Test
    public void execResolvesToEnglishBundleWhenLocaleIsEn() throws Exception {
        // text("k") under locale "en" resolves to the English bundle value.
        Assertions.assertEquals("english", render("<#setting locale=\"en\">${text(\"k\")}", data()));
    }

    @Test
    public void execResolvesToDanishBundleWhenLocaleIsDa() throws Exception {
        // text("k") under locale "da" resolves to the Danish bundle value.
        Assertions.assertEquals("dansk", render("<#setting locale=\"da\">${text(\"k\")}", data()));
    }

    @Test
    public void execFallsBackToDefaultLanguageForUnknownLocale() throws Exception {
        // An unknown locale ("fr") with no matching bundle falls back to the default-language "en" bundle.
        Assertions.assertEquals("english", render("<#setting locale=\"fr\">${text(\"k\")}", data()));
    }

    @Test
    public void hashAccessResolvesViaInvokeGenericGet() throws Exception {
        // Hash-style access text.k (invokeGenericGet) resolves the key from the locale-matched bundle.
        Assertions.assertEquals("english", render("<#setting locale=\"en\">${text.k}", data()));
    }

    @Test
    public void execAppliesMessageFormatWithOneArgument() throws Exception {
        // text("greet", "World") applies MessageFormat substitution of {0} with the supplied argument.
        Assertions.assertEquals("Hello World", render("<#setting locale=\"en\">${text(\"greet\", \"World\")}", data()));
    }

    @Test
    public void execThrowsTemplateExceptionForMissingKey() throws Exception {
        // Looking up an absent key wraps the MissingResourceException as a Freemarker TemplateException.
        try {
            render("<#setting locale=\"en\">${text(\"nope\")}", data());
            Assertions.fail("Expected a TemplateException for a missing resource key");
        } catch (TemplateException expected) {
            // expected
        }
    }

    @Test
    public void hashAccessThrowsTemplateExceptionForMissingKey() throws Exception {
        // Hash-style access of an absent key (invokeGenericGet) also surfaces as a Freemarker TemplateException.
        try {
            render("<#setting locale=\"en\">${text.nope}", data());
            Assertions.fail("Expected a TemplateException for a missing resource key");
        } catch (TemplateException expected) {
            // expected
        }
    }

    @Test
    public void bundleWithNullLocaleMapsToDefaultLanguage() throws Exception {
        // A bundle constructed with a null locale is keyed under the default language "en" and is resolved there.
        ResourceBundle nullLocaleBundle = new TestBundle(null, new Object[][] { { "k", "fallbackval" } });
        List<ResourceBundle> bundles = Collections.singletonList(nullLocaleBundle);
        MultiResourceBundleModel model2 = new MultiResourceBundleModel(
                bundles,
                new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build());

        Map<String, Object> data = new HashMap<>();
        data.put("text", model2);

        Assertions.assertEquals("fallbackval", render("<#setting locale=\"en\">${text(\"k\")}", data));
    }
}
