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
package org.niord.core.publication;

import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.publication.vo.PublicationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ${week}/${year} tokens of a publication made from a template (the legacy publication model,
 * {@code PublicationService.updateFromTemplate}) at the turn of the year (TZ3). A text that names a week pairs it
 * with the ISO week-based year, as the publication series of the redesign do ({@code IssueNaming}); a text that
 * names no week keeps the calendar year. Every value is read in the zone of the publication's domain.
 * <p>
 * Every test sets the JVM's zone to UTC, the zone the deployments run in, and restores it.
 */
public class PublicationTemplateTokensYearBoundaryTest {

    private static <T> T inUtc(Supplier<T> body) {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            return body.get();
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    private static Date utc(LocalDateTime time) {
        return Date.from(time.atZone(ZoneId.of("UTC")).toInstant());
    }

    private static Domain domain(String timeZone) {
        Domain domain = new Domain();
        domain.setDomainId("niord-nm");
        domain.setTimeZone(timeZone);
        return domain;
    }

    @Test
    public void weekAndYear_atNewYear_nameTheWeeksOwnYear() {
        // A publication dated 1 January 2027 is in ISO week 53 of 2026, one dated 30 December 2024 in week 1 of
        // 2025: "53/2026" and "1/2025", not "53/2027" and "1/2024".
        List<String> names = inUtc(() -> {
            List<String> out = new ArrayList<>();
            for (LocalDate d : List.of(LocalDate.of(2027, 1, 1), LocalDate.of(2024, 12, 30))) {
                Publication p = new Publication();
                p.setPublishDateFrom(utc(d.atTime(12, 0)));
                PublicationTemplateUpdateCtx ctx = new PublicationTemplateUpdateCtx(p, null);
                out.add(ctx.str("${week}/${year}", null));
                out.add(ctx.str("nm-w${week-2-digits}-${year-2-digits}", null));
                out.add(ctx.str("${year}", null));
            }
            return out;
        });
        assertEquals(List.of("53/2026", "nm-w53-26", "2027", "1/2025", "nm-w01-25", "2024"), names);
    }

    @Test
    public void theTokensAreReadInThePublicationDomainsZone() {
        // 23:30 UTC on 31 December 2026 is 00:30 on 1 January 2027 in Copenhagen: the year is 2027 there, and
        // the week is still week 53 of 2026.
        List<String> names = inUtc(() -> {
            Publication p = new Publication();
            p.setDomain(domain("Europe/Copenhagen"));
            p.setPublishDateFrom(utc(LocalDateTime.of(2026, 12, 31, 23, 30)));
            PublicationTemplateUpdateCtx ctx = new PublicationTemplateUpdateCtx(p, null);
            return List.of(ctx.str("${week}/${year}", null), ctx.str("${year}", null),
                    ctx.str("${month}", null));
        });
        assertEquals(List.of("53/2026", "2027", "1"), names);
    }

    @Test
    public void theTemplatesDomainGivesTheZone_andAnUnreadableZoneIsTheJvms() {
        // The template's domain is the one the publication takes over (Publication.updateFromTemplate), so it
        // gives the zone; a zone that cannot be read falls back to the JVM's.
        List<String> names = inUtc(() -> {
            Publication template = new Publication();
            template.setDomain(domain("Europe/Copenhagen"));
            Publication p = new Publication();
            p.setTemplate(template);
            p.setPublishDateFrom(utc(LocalDateTime.of(2026, 12, 31, 23, 30)));
            String fromTemplate = new PublicationTemplateUpdateCtx(p, null).str("${year}", null);

            Publication q = new Publication();
            q.setDomain(domain("Not/A_Zone"));
            q.setPublishDateFrom(utc(LocalDateTime.of(2026, 12, 31, 23, 30)));
            String unreadable = new PublicationTemplateUpdateCtx(q, null).str("${year}", null);
            return List.of(fromTemplate, unreadable);
        });
        assertEquals(List.of("2027", "2026"), names);
    }

    @Test
    public void reportParameters_withAWeek_takeTheWeekBasedYearInEveryValue() {
        // A template's report parameters are separate values: the NM template gives "year" as "${year}" and
        // "week" as "${week}", printed together in the report header. When any of them names a week, ${year} is
        // the week-based year in all of them; parameters without a week keep the calendar year.
        List<Object> values = inUtc(() -> {
            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("year", "${year}");
            nm.put("week", "${week}");
            Map<String, Object> plain = new LinkedHashMap<>();
            plain.put("year", "${year}");

            Publication template = new Publication();
            template.setReportParams(nm);
            Publication p = new Publication();
            p.setStatus(PublicationStatus.DRAFT);
            p.setTemplate(template);
            p.setPublishDateFrom(utc(LocalDateTime.of(2024, 12, 30, 12, 0)));
            p.updateFromTemplate(new PublicationTemplateUpdateCtx(p, null));

            Publication template2 = new Publication();
            template2.setReportParams(plain);
            Publication q = new Publication();
            q.setStatus(PublicationStatus.DRAFT);
            q.setTemplate(template2);
            q.setPublishDateFrom(utc(LocalDateTime.of(2024, 12, 30, 12, 0)));
            q.updateFromTemplate(new PublicationTemplateUpdateCtx(q, null));

            return List.of(p.getReportParams().get("week"), p.getReportParams().get("year"),
                    q.getReportParams().get("year"));
        });
        assertEquals(List.of("1", "2025", "2024"), values);
    }
}
