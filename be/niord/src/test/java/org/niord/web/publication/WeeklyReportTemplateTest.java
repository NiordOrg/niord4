/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.web.publication;

import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.StringTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapper;
import freemarker.template.SimpleScalar;
import freemarker.template.Template;
import freemarker.template.TemplateHashModel;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModel;
import org.junit.jupiter.api.Test;
import org.niord.model.message.MessageVo;
import org.niord.model.message.Type;

import java.io.File;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weekly cover line: which year the volume is counted from.
 *
 * ONE CELL of one table, and it read the CLOCK. The volume is the number of
 * years the publication has run, so it is a fact about the edition and not about
 * the day somebody pressed print -- and the weekly is printed again whenever it
 * is amended. A December issue corrected in January, and any re-render of an
 * archived edition, stamped the printing year's volume on a document of another
 * year, with nothing anywhere to notice it by.
 *
 * The render injects {@code yearNumber}, the year the numbering derived, for
 * exactly this kind of arithmetic; the printed year beside it is free text and is
 * not arithmetic to do. The clock remains the answer for the one caller that has
 * no issue behind it -- the legacy print dialog renders this same template -- and
 * that fallback is asserted rather than assumed, because losing it would fail the
 * whole render rather than one cell.
 *
 * Rendered through FreeMarker rather than through the application, with the two
 * substitutions {@link AccumulatedReportTemplateTest} uses: the shared
 * {@code message-support.ftl} is stubbed, and {@code text()} answers with the key
 * it was asked for and its arguments, so the volume can be read off the document.
 */
public class WeeklyReportTemplateTest {

    private static final String TEMPLATE = "nm-report-pdf.ftl";

    /** The text the report templates date a document by, and its English value */
    private static final String DATE_FORMAT_KEY = "msg.time.date_format";
    private static final String ENGLISH_DATE_FORMAT = "d MMMM yyyy";

    /** The shared macros, stood in for: this is a test about the cover line. */
    private static final String MESSAGE_SUPPORT_STUB = """
            <#macro pageSizeStyle frontPage=true></#macro>
            <#macro renderDefaultHeaderAndFooter headerText frontPage=true>
            <div class="header">${headerText}</div>
            </#macro>
            <#macro renderMessageList messages areaHeadings=true prefix="" draft=false link=false>
            <#list messages as msg>
            <div class="msg">${prefix}:${msg.id}</div>
            </#list>
            </#macro>
            """;

    /** Every label answers with its own key, and with its arguments in brackets. */
    private static class KeyText implements TemplateMethodModelEx, TemplateHashModel {
        /** The pattern the language's date text holds, which the templates date the document by */
        private final String dateFormat;

        KeyText(String dateFormat) {
            this.dateFormat = dateFormat;
        }

        @Override
        public Object exec(List arguments) {
            if (arguments.isEmpty()) {
                return "";
            }
            if (arguments.size() == 1 && DATE_FORMAT_KEY.equals(String.valueOf(arguments.get(0)))) {
                return dateFormat;
            }
            StringBuilder out = new StringBuilder(String.valueOf(arguments.get(0)));
            for (int i = 1; i < arguments.size(); i++) {
                out.append(i == 1 ? "(" : ",").append(String.valueOf(arguments.get(i)));
            }
            return arguments.size() > 1 ? out.append(")").toString() : out.toString();
        }

        /** Read as a hash too, {@code text['key']}, as the dictionary model is */
        @Override
        public TemplateModel get(String key) {
            return new SimpleScalar(key);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }

    private String render(Map<String, Object> model) throws Exception {
        return render(TEMPLATE, model);
    }

    private String render(String templateName, Map<String, Object> model) throws Exception {
        StringTemplateLoader stubs = new StringTemplateLoader();
        stubs.putTemplate("message-support.ftl", MESSAGE_SUPPORT_STUB);

        TemplateLoader loader = new MultiTemplateLoader(new TemplateLoader[] {
                stubs,
                // The base product's templates are bootstrap data, read by path (in no jar since C4.2a)
                new FileTemplateLoader(new File("bootstrap/templates/messages"))
        });

        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setTemplateLoader(loader);
        cfg.setObjectWrapper(new DefaultObjectWrapper(Configuration.VERSION_2_3_31));
        cfg.setLocalizedLookup(false);

        model.putIfAbsent("text", new KeyText(ENGLISH_DATE_FORMAT));

        Template template = cfg.getTemplate(templateName);
        StringWriter out = new StringWriter();
        template.process(model, out);
        return out.toString();
    }

    // ------------------------------------------------------------- the fixture

    private static MessageVo message(String id, int number, Type type) {
        MessageVo m = new MessageVo();
        m.setId(id);
        m.setNumber(number);
        m.setType(type);
        return m;
    }

    /** A week of two notices: the smallest document the cover belongs to. */
    private static Map<String, Object> aWeek() {
        List<MessageVo> messages = new ArrayList<>(List.of(
                message("uid-a", 101, Type.TEMPORARY_NOTICE),
                message("uid-b", 102, Type.MISCELLANEOUS_NOTICE)));

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("messages", messages);
        model.put("areaHeadings", false);
        model.put("week", "36");
        model.put("weekTo", "");
        model.put("year", "2025");
        model.put("yearNumber", 2025);
        model.put("edition", "1");
        model.put("ISSN", "1397-999X");
        model.put("timeZone", "Europe/Copenhagen");
        return model;
    }

    /** The volume the clock would print, for the edition the clock has no year for. */
    private static int volumeOfThisYear() {
        return LocalDate.now().getYear() - 1884;
    }

    // -------------------------------------------------------------- the checks

    /**
     * THE DEFECT THIS PINS. The volume is the ISSUE's year, not the printer's.
     *
     * Asserted as the 2025 edition rendered now: the two numbers differ, so a
     * document that quietly went on counting from the clock fails here rather
     * than a year after it shipped.
     */
    @Test
    public void theVolumeIsCountedFromTheYearTheNumberingDerived() throws Exception {
        String html = render(aWeek());

        assertTrue(html.contains("pdf.volume(141)"),
                "the 2025 edition does not count its volume from 2025; a December issue amended in "
                        + "January prints the volume of the year it was printed in");
        assertFalse(volumeOfThisYear() != 141
                        && html.contains("pdf.volume(" + volumeOfThisYear() + ")"),
                "the volume was counted from the year this test is being run in");
    }

    /**
     * The printing date stays, and stays the clock's.
     *
     * It is the one part of the weekly cover that IS about the day it was made,
     * and it sits on the same line as the volume -- so a change to one of them is
     * the plausible way to lose the other.
     */
    @Test
    public void thePrintingDateIsStillTheDayTheDocumentWasMade() throws Exception {
        String html = render(aWeek());

        // Formatted the way the template formats it, in the same default locale
        // FreeMarker is configured with here.
        String today = new SimpleDateFormat(ENGLISH_DATE_FORMAT, Locale.getDefault())
                .format(new Date());
        assertTrue(html.contains(today),
                "the cover no longer says which day the document was printed on");
    }

    /**
     * Every report dates its first page by the pattern of the language's
     * date text ({@code msg.time.date_format}, "d MMMM yyyy" in English), not by
     * the Danish "dd. MMMM yyyy" written into the template. Asserted with a pattern
     * no template holds, so a template that keeps a pattern of its own fails here.
     * FAILS on the templates that wrote "dd. MMMM yyyy".
     */
    @Test
    public void everyReportDatesItsFirstPageByTheLanguagesDateText() throws Exception {
        String isoToday = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        for (String template : List.of(TEMPLATE, "nm-tp-report-pdf.ftl", "nm-almanac-report-pdf.ftl",
                "nm-annex-report-pdf.ftl", "fa-list-pdf.ftl")) {
            Map<String, Object> model = aWeek();
            model.put("text", new KeyText("'dated' yyyy-MM-dd"));
            if (template.startsWith("nm-almanac")) {
                // The cover is what is asserted: the almanac's contents need more of a message than these have
                model.put("messages", new ArrayList<>());
            }

            String html = render(template, model);

            assertTrue(html.contains("dated " + isoToday),
                    template + " does not date its first page by the language's date text: " + html);
        }
    }

    /**
     * With no derived year the clock still answers, and the document renders.
     *
     * The legacy print dialog renders this template for an ad-hoc selection of
     * messages with no issue behind it at all. A guard that printed nothing there
     * would empty the cell on the one caller that has only the clock to go on.
     */
    @Test
    public void anAdHocPrintWithNoIssueBehindItCountsFromTheClock() throws Exception {
        Map<String, Object> model = aWeek();
        model.remove("yearNumber");

        String html = render(model);

        assertTrue(html.contains("pdf.volume(" + volumeOfThisYear() + ")"),
                "a render with no derived year lost its volume line; the print dialog has no issue "
                        + "to take one from");
    }

    /** And a free-text year label never reaches the arithmetic. */
    @Test
    public void aFreeTextYearLabelIsPrintedAndNotComputedFrom() throws Exception {
        Map<String, Object> model = aWeek();
        model.put("year", "To tusind og femogtyve");
        model.put("week", "36+37");

        String html = render(model);

        assertTrue(html.contains("To tusind og femogtyve"),
                "the cover does not print the year the edition is called");
        assertTrue(html.contains("pdf.volume(141)"),
                "the label reached the volume arithmetic, which fails the whole render the first "
                        + "time a year is written out in words");
    }

    // ------------------------------------------------------ the weeks of the issue

    /** The number of times the text occurs in the document */
    private static int occurrences(String html, String needle) {
        int count = 0;
        for (int at = html.indexOf(needle); at >= 0; at = html.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /**
     * RENDER11. An issue named for weeks 36 to 38 is headed by those weeks, on its first page and in its page
     * header, in both weekly reports, and so is a pair, in whatever form the issue's name writes them. The release
     * hands the report the weeks as the name writes them ({@code weeks}); the templates printed {@code week} alone,
     * so "Uge 36-38, 2026" was headed "Uge 36". FAILS on the templates that print {@code week}.
     */
    @Test
    public void bothWeeklyReportsAreHeadedByTheWeeksTheIssuesNameSays() throws Exception {
        for (String weeks : List.of("36-38", "36+37", "36 og 37")) {
            Map<String, Object> model = aWeek();
            model.put("weekTo", weeks.substring(weeks.length() - 2));
            model.put("weeks", weeks);

            String nm = render(model);
            assertEquals(2, occurrences(nm, "pdf.week " + weeks + ", 2025"),
                    "the NtM report's heading and page header do not both say weeks " + weeks + ": " + nm);

            String tp = render("nm-tp-report-pdf.ftl", model);
            assertEquals(2, occurrences(tp, "pdf.week " + weeks + ", 2025"),
                    "the T&P report's heading and page header do not both say weeks " + weeks + ": " + tp);
            assertTrue(tp.contains("pdf.nm_abbrev  " + weeks + " 2025"),
                    "the T&P report's first-page line does not say weeks " + weeks);
        }
    }

    /** A single week, and the legacy print dialog's model, which hands the week alone, print the week. */
    @Test
    public void aSingleWeekAndAPrintWithoutWeeksPrintTheWeek() throws Exception {
        Map<String, Object> single = aWeek();
        single.put("weeks", "36");
        assertEquals(2, occurrences(render(single), "pdf.week 36, 2025"));

        Map<String, Object> dialog = aWeek();
        dialog.remove("weekTo");
        assertEquals(2, occurrences(render(dialog), "pdf.week 36, 2025"),
                "a render with no weeks lost the week; the print dialog hands only the week");
        assertEquals(2, occurrences(render("nm-tp-report-pdf.ftl", dialog), "pdf.week 36, 2025"));
    }
}
