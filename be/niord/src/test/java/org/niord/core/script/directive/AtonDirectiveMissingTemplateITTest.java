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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;

/**
 * The two aton directives fall back to the raw value when their template is missing (a row renamed or deleted in
 * Sysadmin &gt; Script resources), and say so at WARN (TMPL19's review): the builder logs an unknown template name
 * at DEBUG only, and the directives' fallback was the only other place that saw it, without a line. A text then
 * printed "Fl(2) W 10s" for "2 white flashes every 10 seconds" with nothing in the log at the deployed level.
 */
class AtonDirectiveMissingTemplateITTest extends ITTest {

    private static final String LIGHT_TEMPLATE = "templates/aton/light-character.ftl";
    private static final String TELEPHONY_TEMPLATE = "templates/aton/telephony-codes.ftl";
    private static final String TEMPLATE = "<@l light=\"Fl(2) W 10s\" format=\"verbose\" lang=\"en\"/>|"
            + "<@c callSign=\"OXYZ\" format=\"verbose\"/>";

    @PersistenceContext
    EntityManager em;

    @Test
    void aMissingAtonTemplateFallsBackToTheRawValueAndSaysSoAtWarn() throws Exception {
        // The right path: the templates turn the light and the call sign into words
        assertEquals("2 white flashes every 10 seconds|Oscar-Xray-Yankee-Zulu", renderBoth());

        assertEquals(2, renamePaths(".gone", ""), "both template rows are renamed");
        try {
            Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            root.addAppender(appender);
            String text;
            try {
                text = renderBoth();
            } finally {
                root.detachAppender(appender);
            }

            // The fallback stays: the raw value, nothing broken
            assertEquals("Fl(2) W 10s|OXYZ", text);

            // ... and a WARN names each value and the template it misses
            List<String> warnings = appender.list.stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(1, warnings.stream()
                    .filter(m -> m.contains("Fl(2) W 10s") && m.contains("light-character.ftl")).count(), warnings.toString());
            assertEquals(1, warnings.stream()
                    .filter(m -> m.contains("OXYZ") && m.contains("telephony-codes.ftl")).count(), warnings.toString());
        } finally {
            renamePaths("", ".gone");
        }
        assertEquals("2 white flashes every 10 seconds|Oscar-Xray-Yankee-Zulu", renderBoth(), "the rows are back");
    }

    private String renderBoth() throws Exception {
        Map<String, Object> data = data();
        data.put("l", new LightCharacterDirective());
        data.put("c", new CallSignDirective());
        return render(TEMPLATE, data);
    }

    /** Renames the two template rows from {@code path + from} to {@code path + to}; returns how many it renamed */
    private int renamePaths(String to, String from) {
        int renamed = 0;
        for (String path : List.of(LIGHT_TEMPLATE, TELEPHONY_TEMPLATE)) {
            renamed += Tx.requiringNew().call(() -> em.createNativeQuery(
                            "UPDATE public.\"ScriptResource\" SET path = :to WHERE path = :from")
                    .setParameter("to", path + to)
                    .setParameter("from", path + from)
                    .executeUpdate());
        }
        return renamed;
    }
}
