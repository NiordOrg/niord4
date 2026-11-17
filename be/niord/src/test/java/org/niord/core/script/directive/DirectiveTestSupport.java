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
package org.niord.core.script.directive;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import org.niord.core.script.FmTemplateService;
import org.niord.core.script.NiordAppObjectWrapper;

import java.io.StringWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TimeZone;

/**
 * Shared, framework-neutral helper for the Freemarker directive unit tests.
 * <p>
 * The directives are plain {@link freemarker.template.TemplateDirectiveModel}s. In production they
 * are wired up by {@link FmTemplateService} which builds a Freemarker {@link Configuration}, registers
 * a {@code text} resource-bundle model and a {@code timeZone} string in the data model, and processes
 * a template that invokes the directives (templates instantiate them via {@code "...Directive"?new()}).
 * <p>
 * This helper reproduces exactly that environment without Quarkus, a database or any application
 * dictionary: it renders a one-off template body through a real Freemarker {@link Configuration} using
 * the same {@link NiordAppObjectWrapper} as production, a fixed {@link Locale#ENGLISH} locale, and the
 * self-contained {@code fm-test-messages_en.properties} bundle for the {@code text} model. Because only
 * Freemarker is involved, the resulting tests survive a later Quarkus-to-Spring migration unchanged.
 */
public final class DirectiveTestSupport {

    private DirectiveTestSupport() {
    }

    /** Self-contained English bundle holding every key the directives look up (position.*, navwarn.date.*, msg.time.*). */
    public static final ResourceBundle EN_BUNDLE = ResourceBundle.getBundle("fm-test-messages", Locale.ENGLISH);

    /**
     * Builds the {@code text} data-model entry the date / audio-position directives read from
     * {@code env.getDataModel().get("text")}.
     */
    public static MultiResourceBundleModel textModel() {
        return new MultiResourceBundleModel(
                Collections.singletonList(EN_BUNDLE),
                new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build());
    }

    /**
     * Returns a fresh, mutable data model pre-loaded with the standard {@code text} bundle and a
     * {@code timeZone} of {@code "UTC"} — the two entries {@link FmTemplateService} always injects.
     * Tests add the directive instance plus any parameters on top.
     */
    public static Map<String, Object> data() {
        Map<String, Object> m = new HashMap<>();
        m.put(FmTemplateService.BUNDLE_PROPERTY, textModel());   // "text"
        m.put(FmTemplateService.TIME_ZONE_PROPERTY, "UTC");      // "timeZone"
        return m;
    }

    /** Renders the template body with the given data model, using the English locale. */
    public static String render(String body, Map<String, Object> data) throws Exception {
        return render(body, data, Locale.ENGLISH);
    }

    /**
     * Renders the template body with the given data model and locale through a Freemarker configuration
     * wired exactly like {@link FmTemplateService#constructFmTemplate}.
     */
    public static String render(String body, Map<String, Object> data, Locale locale) throws Exception {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate("t", body);
        cfg.setTemplateLoader(loader);
        cfg.setObjectWrapper(new NiordAppObjectWrapper(cfg.getIncompatibleImprovements()));
        cfg.setLocale(locale);

        StringWriter out = new StringWriter();
        cfg.getTemplate("t").process(data, out);
        return out.toString();
    }

    /** Builds a {@link Date} from a {@code "yyyy-MM-dd HH:mm"} string interpreted in UTC, for deterministic oracles. */
    public static Date utc(String dateTime) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(dateTime);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }
}
