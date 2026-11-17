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

import org.niord.core.bootstrap.BootstrapMarker;
import org.niord.core.report.FmReportService;
import org.niord.core.service.BaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The missing-path report (docs/code-data-config-plan.md §6.6): every template or script path that data or the code
 * names and that has no {@code ScriptResource} row, and every built-in report id that has no {@code FmReport} row.
 * Since C4.2a the code reads no template from the jar, so such a path resolves to nothing on a running deployment.
 *
 * <p>The named paths are {@link RequiredTemplates#PATHS}, the script paths of {@code Category},
 * {@code MailingListTrigger} and {@code PromulgationType}, {@code FmReport.templatePath}, every literal
 * {@code <#include "…">} or {@code <#import "…">} in a FreeMarker row, resolved against the including row's folder
 * as FreeMarker does ({@code .} and {@code ..} segments removed, a leading {@code /} from the root), and every
 * {@code load('niord:…')} in a JavaScript row. Every path loses one leading {@code /}, as FreeMarker drops it before
 * it asks the loader, and is compared exactly with the rows' paths. A localized variant ({@code x_<ll>.ftl}) no data
 * row or constant names: the expected paths, the path names the release import files and the bootstraps carry
 * ({@value #EXPECTED_PATHS}, one in each jar that has one), add each variant whose {@code x.ftl} is named.
 *
 * <p>The report only reads. It is logged at every start where the bootstrap marker reads {@code existing} or
 * {@code applied} (a report that cannot be made is a warning, never a failed start), and answered by {@code GET /rest/script-resources/missing-paths} (SYSADMIN). The same query as
 * read-only SQL ({@link #sql(Collection)}) goes into each release folder's {@code missing-paths.sql}, so a sysadmin
 * can run it before an upgrade; {@code MissingPathReportITTest} holds the two to the same answer.
 */
@Service
public class MissingPathReport extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(MissingPathReport.class);

    /** The class-path resource listing the template paths a jar's release files and bootstrap carry */
    public static final String EXPECTED_PATHS = "META-INF/niord/expected-template-paths.txt";

    /** A literal include or import of a FreeMarker template; a path with an interpolation is not literal */
    static final String INCLUDE_REGEX = "<#(?:include|import)\\s+[\"']([^\"'$]+)[\"']";
    /** A literal {@code load('niord:…')} of a script */
    static final String LOAD_REGEX = "load\\(\\s*[\"']niord:([^\"']+)[\"']\\s*\\)";
    /** A {@code .} segment */
    static final String DOT_SEGMENT_REGEX = "(^|/)\\./";
    /** A segment followed by {@code ..}; the segment is not {@code ..} itself */
    static final String DOT_DOT_REGEX = "(^|/)(?!\\.\\./)[^/]+/\\.\\./";
    /** A localized variant, as {@code FmTemplateLoader} reads one */
    static final String VARIANT_REGEX = "^(.*)_[a-z]{2}\\.ftl$";

    private static final Pattern INCLUDE = Pattern.compile(INCLUDE_REGEX);
    private static final Pattern LOAD = Pattern.compile(LOAD_REGEX);
    private static final Pattern DOT_SEGMENT = Pattern.compile(DOT_SEGMENT_REGEX);
    private static final Pattern DOT_DOT = Pattern.compile(DOT_DOT_REGEX);
    private static final Pattern VARIANT = Pattern.compile(VARIANT_REGEX);

    static final String CODE = "code: RequiredTemplates";
    static final String BUILT_IN_REPORT = "code: FmReportService.BUILT_IN_REPORT_IDS";

    private final BootstrapMarker marker;

    MissingPathReport(BootstrapMarker marker) {
        this.marker = marker;
    }

    /** What a missing entry is */
    public enum Kind {
        /** a template or script path with no ScriptResource row */
        TEMPLATE,
        /** a built-in report id with no FmReport row */
        REPORT
    }

    /**
     * One missing path or report id
     *
     * @param kind    what it is
     * @param path    the path, or the report id
     * @param namedBy what names it, sorted
     */
    public record Entry(Kind kind, String path, List<String> namedBy) {
    }

    /**
     * Logs the report at start, when the bootstrap marker reads {@code existing} or {@code applied}: a deployment
     * whose bootstrap is still to come ({@code pending}, {@code in-progress}, {@code failed}) gets its rows from
     * it. Runs after the marker is written (its listener comes first); writes nothing.
     *
     * <p>It never stops a start: an exception from an ApplicationReadyEvent listener fails the start, so a report
     * that cannot be made is logged as a warning, and the endpoint gives it later. It runs outside a transaction
     * ({@code report()} is called on this instance, not through the proxy): a failed query inside a read-only
     * transaction would mark it rollback-only, and its commit would throw past this method.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void logAtStart() {
        try {
            String state = marker.read().map(BootstrapMarker.State::state).orElse(null);
            if (!BootstrapMarker.EXISTING.equals(state) && !BootstrapMarker.APPLIED.equals(state)) {
                return;
            }
            List<Entry> missing = report();
            if (missing.isEmpty()) {
                log.info("Missing-path report: no missing path");
                return;
            }
            StringBuilder text = new StringBuilder("Missing-path report: " + missing.size()
                    + " named template, script or built-in report path(s) without a row; a sysadmin imports them "
                    + "from a release file (GET /rest/script-resources/missing-paths):");
            missing.forEach(e -> text.append("\n  ").append(e.kind()).append(' ').append(e.path())
                    .append(" (named by ").append(String.join("; ", e.namedBy())).append(')'));
            log.warn(text.toString());
        } catch (RuntimeException e) {
            log.warn("Missing-path report could not be made at start; GET /rest/script-resources/missing-paths "
                    + "gives it: " + e, e);
        }
    }

    /**
     * Returns the missing paths and report ids, sorted by kind and path
     *
     * @return the missing entries
     */
    @Transactional(readOnly = true)
    public List<Entry> report() {
        return report(expectedPaths());
    }

    /**
     * Returns the missing paths and report ids against the given expected paths
     *
     * @param expectedPaths the paths the release files and bootstraps carry
     * @return the missing entries, sorted by kind and path
     */
    @Transactional(readOnly = true)
    public List<Entry> report(Collection<String> expectedPaths) {
        Set<String> rows = new HashSet<>(em.createNativeQuery("SELECT path FROM public.\"ScriptResource\"",
                String.class).getResultList());

        Map<String, Set<String>> named = new TreeMap<>();
        RequiredTemplates.PATHS.forEach(p -> name(named, p, CODE));
        for (String owner : List.of("Category", "MailingListTrigger", "PromulgationType")) {
            for (Object[] r : rows("SELECT \"" + owner + "_id\", \"scriptResourcePaths\" FROM public.\"" + owner
                    + "_scriptResourcePaths\"")) {
                name(named, (String) r[1], owner + " " + r[0]);
            }
        }
        for (Object[] r : rows("SELECT \"reportId\", \"templatePath\" FROM public.\"FmReport\"")) {
            name(named, (String) r[1], "FmReport " + r[0]);
        }
        for (Object[] r : rows("SELECT path, type, content FROM public.\"ScriptResource\" "
                + "WHERE type IN ('FM', 'JS') AND content IS NOT NULL")) {
            String path = (String) r[0];
            String content = (String) r[2];
            if ("FM".equals(r[1])) {
                INCLUDE.matcher(content).results().forEach(m ->
                        name(named, resolve(path, m.group(1)), "include in " + path));
            } else {
                LOAD.matcher(content).results().forEach(m -> name(named, m.group(1), "load in " + path));
            }
        }
        for (String expected : expectedPaths) {
            Matcher variant = VARIANT.matcher(expected);
            if (variant.matches()) {
                String base = variant.group(1) + ".ftl";
                if (named.containsKey(base)) {
                    name(named, expected, "variant of " + base);
                }
            }
        }

        List<Entry> missing = new ArrayList<>();
        named.forEach((path, by) -> {
            if (!rows.contains(path)) {
                missing.add(new Entry(Kind.TEMPLATE, path, List.copyOf(by)));
            }
        });
        Set<String> reportIds = new HashSet<>(em.createNativeQuery("SELECT \"reportId\" FROM public.\"FmReport\"",
                String.class).getResultList());
        FmReportService.BUILT_IN_REPORT_IDS.stream().sorted()
                .filter(id -> !reportIds.contains(id))
                .forEach(id -> missing.add(new Entry(Kind.REPORT, id, List.of(BUILT_IN_REPORT))));
        return missing;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    /** Adds a named path, without one leading '/'; an empty path names nothing */
    private static void name(Map<String, Set<String>> named, String path, String by) {
        String p = RequiredTemplates.normalize(path);
        if (p != null && !p.isEmpty()) {
            named.computeIfAbsent(p, k -> new TreeSet<>()).add(by);
        }
    }

    /**
     * Resolves an include as FreeMarker does: a leading '/' from the root, anything else against the including
     * template's folder, then the {@code .} and {@code ..} segments removed
     *
     * @param from   the including template's path
     * @param target the included path
     * @return the resolved path
     */
    static String resolve(String from, String target) {
        String path = target.startsWith("/") ? target : from.substring(0, from.lastIndexOf('/') + 1) + target;
        path = DOT_SEGMENT.matcher(path).replaceAll("$1");
        Matcher dotDot = DOT_DOT.matcher(path);
        while (dotDot.find()) {
            path = dotDot.replaceFirst("$1");
            dotDot = DOT_DOT.matcher(path);
        }
        return path;
    }

    /**
     * Returns the expected paths: the union of every {@value #EXPECTED_PATHS} on the class path (the base product's,
     * and a country's when its jar has one), sorted
     *
     * @return the expected paths
     */
    public static List<String> expectedPaths() {
        Set<String> paths = new TreeSet<>();
        try {
            Enumeration<URL> lists = MissingPathReport.class.getClassLoader().getResources(EXPECTED_PATHS);
            while (lists.hasMoreElements()) {
                try (InputStream in = lists.nextElement().openStream()) {
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                            .map(String::strip)
                            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                            .forEach(paths::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + EXPECTED_PATHS, e);
        }
        return List.copyOf(paths);
    }

    /**
     * Returns the report as one read-only PostgreSQL query, with the columns {@code kind}, {@code path} and
     * {@code named_by} (the names joined with "; "), in the order of {@link #report(Collection)}: the text a
     * release folder's {@code missing-paths.sql} holds
     *
     * @param expectedPaths the paths the release files and bootstraps carry
     * @return the query
     */
    public static String sql(Collection<String> expectedPaths) {
        String c = " COLLATE \"C\"";
        return """
                -- The missing-path report (docs/code-data-config-plan.md §6.6), generated from MissingPathReport.sql():
                -- every template or script path that data or the code names and that has no ScriptResource row, and
                -- every built-in report id with no FmReport row. It only reads; run it before an upgrade with
                --   psql -d <database> -f missing-paths.sql
                WITH RECURSIVE
                required(path) AS (SELECT unnest(CAST(ARRAY[%s] AS text[]))),
                expected(path) AS (SELECT unnest(CAST(ARRAY[%s] AS text[]))),
                report_ids(id) AS (SELECT unnest(CAST(ARRAY[%s] AS text[]))),
                refs(source, target) AS (
                    SELECT r.path, m[1]
                    FROM public."ScriptResource" r CROSS JOIN LATERAL regexp_matches(r.content, '%s', 'g') AS m
                    WHERE r.type = 'FM'
                ),
                resolved(path, named_by) AS (
                    SELECT regexp_replace(CASE WHEN target LIKE '/%%' THEN target
                                               ELSE regexp_replace(source, '[^/]*$', '') || target END,
                                          '%s', '\\1', 'g'),
                           'include in ' || source
                    FROM refs
                    UNION ALL
                    SELECT regexp_replace(path, '%s', '\\1'), named_by FROM resolved WHERE path ~ '%s'
                ),
                named(path, named_by) AS (
                    SELECT path, 'code: RequiredTemplates' FROM required
                    UNION ALL
                    SELECT "scriptResourcePaths", 'Category ' || "Category_id" FROM public."Category_scriptResourcePaths"
                    UNION ALL
                    SELECT "scriptResourcePaths", 'MailingListTrigger ' || "MailingListTrigger_id"
                    FROM public."MailingListTrigger_scriptResourcePaths"
                    UNION ALL
                    SELECT "scriptResourcePaths", 'PromulgationType ' || "PromulgationType_id"
                    FROM public."PromulgationType_scriptResourcePaths"
                    UNION ALL
                    SELECT "templatePath", 'FmReport ' || "reportId" FROM public."FmReport"
                    UNION ALL
                    SELECT path, named_by FROM resolved WHERE path !~ '%s'
                    UNION ALL
                    SELECT m[1], 'load in ' || r.path
                    FROM public."ScriptResource" r CROSS JOIN LATERAL regexp_matches(r.content, '%s', 'g') AS m
                    WHERE r.type = 'JS'
                ),
                normalized(path, named_by) AS (
                    SELECT CASE WHEN path LIKE '/%%' THEN substr(path, 2) ELSE path END, named_by
                    FROM named WHERE path <> ''
                ),
                with_variants(path, named_by) AS (
                    SELECT path, named_by FROM normalized
                    UNION ALL
                    SELECT e.path, 'variant of ' || regexp_replace(e.path, '%s', '\\1.ftl')
                    FROM expected e
                    WHERE e.path ~ '%s'
                      AND EXISTS (SELECT 1 FROM normalized n WHERE n.path = regexp_replace(e.path, '%s', '\\1.ftl'))
                )
                SELECT kind, path, named_by FROM (
                    SELECT 'TEMPLATE' AS kind, w.path,
                           string_agg(DISTINCT w.named_by%s, '; ' ORDER BY w.named_by%s) AS named_by
                    FROM with_variants w
                    WHERE NOT EXISTS (SELECT 1 FROM public."ScriptResource" s WHERE s.path = w.path)
                    GROUP BY w.path
                    UNION ALL
                    SELECT 'REPORT', id, '%s'
                    FROM report_ids
                    WHERE NOT EXISTS (SELECT 1 FROM public."FmReport" f WHERE f."reportId" = report_ids.id)
                ) missing
                ORDER BY CASE kind WHEN 'TEMPLATE' THEN 0 ELSE 1 END, path%s;
                """.formatted(
                array(RequiredTemplates.PATHS),
                array(expectedPaths),
                array(FmReportService.BUILT_IN_REPORT_IDS),
                quote(INCLUDE_REGEX),
                quote(DOT_SEGMENT_REGEX),
                quote(DOT_DOT_REGEX), quote(DOT_DOT_REGEX),
                quote(DOT_DOT_REGEX),
                quote(LOAD_REGEX),
                quote(VARIANT_REGEX), quote(VARIANT_REGEX), quote(VARIANT_REGEX),
                c, c,
                BUILT_IN_REPORT,
                c);
    }

    /** The values as the elements of a SQL array literal */
    private static String array(Collection<String> values) {
        return values.stream().sorted().map(v -> "'" + quote(v) + "'").collect(Collectors.joining(", "));
    }

    /** Doubles the single quotes of a value put between single quotes */
    private static String quote(String value) {
        return value.replace("'", "''");
    }
}
