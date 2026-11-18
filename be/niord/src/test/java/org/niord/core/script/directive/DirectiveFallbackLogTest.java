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
package org.niord.core.script.directive;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.script.FmTemplateService;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.core.script.directive.DirectiveTestSupport.EN_BUNDLE;
import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;
import static org.niord.core.script.directive.DirectiveTestSupport.utc;

/**
 * The template helpers keep printing nothing when they fail, so a render never breaks on a helper, but each failure
 * now leaves one WARN line that names what was left out, the template and the reason; an ordinary render logs none.
 * The renders go through {@link DirectiveTestSupport}, whose one template is named {@code t}.
 */
public class DirectiveFallbackLogTest {

    @Test
    public void anAudioPositionWithoutItsPositionWordPrintsNothingAndSaysSo() throws Exception {
        Map<String, Object> data = dataWithout("position.deg");
        data.put("p", new LatLonDirective());

        Run run = renderCapturing(LatLonDirective.class, "<@p lat=55.5 lon=12.5 format=\"audio\"/>", data);

        assertEquals("", run.output());
        String line = run.onlyWarning();
        assertTrue(line.contains("format \"audio\""), line);
        assertTrue(line.contains("template t:"), line);
        assertTrue(line.contains("position.deg"), line);
    }

    @Test
    public void aDaySuffixListTooShortForTheDayPrintsNoDateAndSaysSo() throws Exception {
        Map<String, Object> data = dataWith("navwarn.date.day_suffix", "th,st");
        data.put("d", new DateIntervalDirective());
        data.put("date", utc("2026-01-31 12:00"));

        Run run = renderCapturing(DateIntervalDirective.class, "<@d date=date/>", data);

        assertEquals("", run.output());
        String line = run.onlyWarning();
        assertTrue(line.startsWith("Date 2026-01-31T12:00:00Z not printed in template t:"), line);
        assertTrue(line.contains("31"), line);
    }

    @Test
    public void aDateFormatThatDoesNotParsePrintsNoDatesAndSaysSo() throws Exception {
        Map<String, Object> data = dataWith("msg.time.date_time_format", "d MMMM yyyy 'HH");
        data.put("d", new FromToDateDirective());
        data.put("from", utc("2026-01-31 12:00"));

        Run run = renderCapturing(FromToDateDirective.class, "<@d fromDate=from/>", data);

        assertEquals("", run.output());
        String line = run.onlyWarning();
        assertTrue(line.startsWith("Dates 2026-01-31T12:00:00Z - (none) not printed in template t:"), line);
    }

    @Test
    public void anAreaLineageThatCannotBeResolvedPrintsNothingAndSaysSo() throws Exception {
        // Without a Spring context the services cannot be looked up: the existing trigger of the catch
        SystemMessageVo message = new SystemMessageVo();
        message.setId("it-message-uid");
        Map<String, Object> data = data();
        data.put("a", new AreaLineageDirective());
        data.put("msg", message);

        Run run = renderCapturing(AreaLineageDirective.class, "<@a message=msg/>", data);

        assertEquals("", run.output());
        String line = run.onlyWarning();
        assertTrue(line.startsWith("Area lineage of message it-message-uid not printed in template t:"), line);
    }

    @Test
    public void ordinaryRendersLogNoWarning() throws Exception {
        Map<String, Object> data = data();
        data.put("p", new LatLonDirective());
        data.put("d", new DateIntervalDirective());
        data.put("f", new FromToDateDirective());
        data.put("n", new NavtexDateFormatDirective());
        data.put("t2h", new TextToHtmlDirective());
        data.put("h2t", new HtmlToTextDirective());
        data.put("date", utc("2026-01-31 12:00"));

        assertEquals("55° 30.000'N - 012° 30.000'E",
                renderCapturing(LatLonDirective.class, "<@p lat=55.5 lon=12.5/>", data).noWarning());
        assertEquals("55 degrees 30.0 min. North 12 degrees 30.0 min. East",
                renderCapturing(LatLonDirective.class, "<@p lat=55.5 lon=12.5 format=\"audio\"/>", data).noWarning());
        assertTrue(renderCapturing(DateIntervalDirective.class, "<@d date=date/>", data).noWarning()
                .contains("31st"));
        assertTrue(renderCapturing(FromToDateDirective.class, "<@f fromDate=date/>", data).noWarning()
                .contains("2026"));
        assertEquals("311200 UTC Jan 26",
                renderCapturing(NavtexDateFormatDirective.class, "<@n date=date/>", data).noWarning());
        // A missing date is no fault: nothing is printed and nothing is logged
        assertEquals("", renderCapturing(NavtexDateFormatDirective.class, "<@n/>", data).noWarning());
        assertEquals("a<br>b",
                renderCapturing(TextToHtmlDirective.class, "<@t2h text=\"a\nb\"/>", data).noWarning());
        assertEquals("a",
                renderCapturing(HtmlToTextDirective.class, "<@h2t html=\"<p>a</p>\"/>", data).noWarning().trim());
    }

    /** One render's output and the WARN lines the directive's own logger wrote during it */
    private record Run(String output, List<String> warnings) {

        String onlyWarning() {
            assertEquals(1, warnings.size(), "exactly one warning: " + warnings);
            return warnings.getFirst();
        }

        String noWarning() {
            assertEquals(List.of(), warnings, "an ordinary render logs no warning");
            return output;
        }
    }

    private static Run renderCapturing(Class<?> directive, String body, Map<String, Object> data) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(directive);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String output = render(body, data);
            return new Run(output, appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList());
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** A data model whose text bundle is the test bundle with one key given another value */
    private static Map<String, Object> dataWith(String key, String value) {
        return dataWithBundle(key, value);
    }

    /** A data model whose text bundle is the test bundle without one key, as if it was deleted on the Dictionaries page */
    private static Map<String, Object> dataWithout(String key) {
        return dataWithBundle(key, null);
    }

    private static Map<String, Object> dataWithBundle(String key, String value) {
        ResourceBundle bundle = new ResourceBundle() {
            @Override
            protected Object handleGetObject(String k) {
                if (k.equals(key)) {
                    return value;
                }
                return EN_BUNDLE.containsKey(k) ? EN_BUNDLE.getObject(k) : null;
            }

            @Override
            public Enumeration<String> getKeys() {
                return Collections.enumeration(EN_BUNDLE.keySet().stream()
                        .filter(k -> value != null || !k.equals(key))
                        .toList());
            }

            @Override
            public Locale getLocale() {
                return Locale.ENGLISH;
            }
        };
        Map<String, Object> m = data();
        m.put(FmTemplateService.BUNDLE_PROPERTY, new MultiResourceBundleModel(
                Collections.singletonList(bundle),
                new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build()));
        return m;
    }
}
