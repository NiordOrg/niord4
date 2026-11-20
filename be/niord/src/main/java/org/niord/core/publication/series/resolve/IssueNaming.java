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

package org.niord.core.publication.series.resolve;

import org.niord.core.publication.series.PublicationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives an issue's numbers, and expands the name and file-name patterns.
 *
 * Two errors this exists to not reproduce.
 *
 * The issue is named for the ISO week of the EFFECTIVE CUT-OFF -- the end of the
 * window, not its start. Naming from the start produces "Uge 26+27, 2026" where
 * production says "EfS uge 27".
 *
 * And a week-numbered issue takes the ISO WEEK-year, not the calendar year.
 * Legacy pairs a correct ISO week with a calendar year read in the JVM default
 * zone, so a cut-off on 31 December 2025 comes out as "EfS uge 1 - 2025" when it
 * belongs to week 1 of 2026. Everything here derives in the series' own
 * timezone.
 *
 * The converse holds for a publication that is not numbered by week, and it is
 * the reason {@link YearBasis} exists rather than a single rule. An annual
 * edition closing at 31 December 23:59 names the year it CLOSES; answering with
 * the ISO week-year would name it for the following January, and the year is
 * part of its file name and therefore of its public download link.
 */
public final class IssueNaming {

    /**
     * The complete token vocabulary. Nothing else may declare a token, and the
     * API serves this constant rather than a copy -- a menu built from a second
     * list is a second source of truth.
     */
    public static final Set<String> TOKENS = new LinkedHashSet<>(List.of(
            "week", "week-2-digits",
            "weekTo", "weekTo-2-digits",
            "year", "year-2-digits",
            "month", "month-2-digits",
            "day", "day-2-digits",
            "edition"));

    /*
     * The zero-padded variants are IN.
     *
     * Production proves they are used -- nm-w01-2026 through nm-w34-2026 -- and
     * without them a file-name pattern that wants "uge 07" cannot be expressed at
     * all.
     *
     * The day tokens are in for a related reason. Without them a series
     * with cadence = DAILY has no way to name its issues, and shipping a cadence
     * that cannot produce a name is worse than either alternative. Adding tokens
     * is additive and costs nothing; removing DAILY from the cadence enum would
     * mean a Liquibase changeset that drops and recreates the cadence column's
     * CHECK constraint later.
     */

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]*)\\}");

    /**
     * A "+" written straight between the first week and the closing week ("${week}+${weekTo}", either variant of
     * each, spaces allowed around the "+"): the pair form, which {@link #joined} turns into a range.
     */
    private static final Pattern WEEK_PAIR_JOIN = Pattern.compile(
            "(\\$\\{week(?:-2-digits)?\\}\\s*)\\+(?=\\s*\\$\\{weekTo(?:-2-digits)?\\})");

    /** The numbers an issue derives from its cut-off. */
    public record Numbers(int week, Integer weekTo, int year, int month, int day, Integer edition) {
    }

    /**
     * What the publication PRINTS where a derived number would go.
     *
     * The period an issue covers and the period it is CALLED are two different
     * facts, and only the first is arithmetic. A double week is written "36+37",
     * "36 & 37" or "36 og 37" depending on the publication and on who typed it,
     * and none of those is a number: the moment they shared a column, either the
     * ordering broke or the cover did.
     *
     * So the numbers stay derived and numeric -- ordering, gap detection, the
     * timeline and the archive all keep reading them -- and this carries the free
     * text that goes on the page. A blank or absent label means the derived
     * number prints, which is what every issue does until somebody says otherwise.
     *
     * THE WEEK AND THE YEAR, AND ${weekTo} IS ALWAYS THE DERIVED NUMBER. A window
     * that swallowed a period the publication skipped carries a second week of
     * its own, so ${week}+${weekTo} already prints "36+37" from the arithmetic (and
     * "36-38" for three weeks or more, see {@link #joined}); a
     * publication that writes the pair some other way writes the whole of it into
     * the week label, and the pattern that names the edition then reads one field
     * rather than two that have to be kept in step.
     *
     * BOTH VARIANTS OF A TOKEN TAKE THE LABEL, the zero-padded one included.
     * "36+37" has no two-digit form, and a pattern that asked for one would
     * otherwise print the derived 36 beside a title saying 36+37 -- the exact
     * disagreement the label exists to remove. A label is a decision about what
     * this edition is called, and it is printed as it was written.
     */
    public record Labels(String week, String year) {

        /** Nothing overridden: every token renders the derived number. */
        public static final Labels NONE = new Labels(null, null);

        /** Whether anything at all was written down. */
        public boolean any() {
            return notBlank(week) || notBlank(year);
        }

        static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * Which year ${year} means.
     *
     * Declared here, as a plain choice, rather than taken from the series'
     * numbering scheme directly: this class knows about instants and patterns and
     * deliberately not about series settings, and the mapping from a scheme to a
     * basis is a publication rule that belongs beside the other publication rules.
     */
    public enum YearBasis {
        /** The ISO week-based year, which is what pairs correctly with an ISO week. */
        ISO_WEEK_YEAR,
        /** The calendar year the cut-off falls in, which is what an annual edition is named for. */
        CALENDAR_YEAR
    }

    private IssueNaming() {
    }

    /** A pattern referenced a token that does not exist. */
    public static class UnknownTokenException extends PublicationException {
        private final String token;

        public UnknownTokenException(String token) {
            super("UNKNOWN_TOKEN", "unknown token ${" + token + "}; the vocabulary is " + TOKENS);
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /**
     * Derives the numbers for an issue closing at the given cut-off.
     *
     * THE BASIS IS ALWAYS ASKED FOR, and there is deliberately no overload that
     * defaults it. There used to be, defaulting to the ISO week-year, and four
     * naming paths -- a gap row's suggestion, a timeline cell, a create draft and
     * the public mapping -- reached it without ever stating which year they
     * meant. An annual edition closing at 31 December 2025 23:59 falls in ISO week
     * 1 of 2026, so each of them named it "2026" while the issue's own stored year
     * said 2025. A caller that has to name the basis cannot inherit the wrong one
     * by omission.
     *
     * @param cutoff the EFFECTIVE cut-off -- the end of the window
     * @param intervalFrom the start, used only to detect a multi-week issue
     * @param zone the series' nominal cut-off timezone; never the JVM default
     * @param edition the edition number, where the scheme has one
     * @param yearBasis which year ${year} means for this publication
     */
    public static Numbers derive(Date cutoff, Date intervalFrom, ZoneId zone, Integer edition,
                                 YearBasis yearBasis) {
        if (cutoff == null) {
            throw new IllegalArgumentException("an issue always has a cut-off to derive from");
        }
        ZoneId z = zone == null ? ZoneId.of("UTC") : zone;
        ZonedDateTime end = Instant.ofEpochMilli(cutoff.getTime()).atZone(z);

        WeekFields iso = WeekFields.ISO;
        int week = end.get(iso.weekOfWeekBasedYear());

        // Two answers, and which one is right depends on what the publication is
        // numbered by. For a week-numbered issue it is the ISO week-BASED year: a
        // cut-off on 31 December can belong to week 1 of the following year, and
        // the calendar year would name it wrongly. For everything else it is the
        // calendar year the cut-off falls in -- an annual edition closing at 31
        // December 23:59 is the edition for THAT year, and the week-based answer
        // would name it for the January after it.
        int year = yearBasis == YearBasis.CALENDAR_YEAR
                ? end.getYear()
                : end.get(iso.weekBasedYear());

        Integer weekTo = null;
        if (intervalFrom != null) {
            // An issue is named for the week it CLOSED in. An ordinary weekly
            // window runs Wednesday to Wednesday and therefore straddles two ISO
            // weeks, so "the start falls in a different week" is true of every
            // ordinary week and names nothing. A multi-week issue -- "Uge 15+16",
            // the double week over a holiday -- is one whose window spans more
            // than one cadence PERIOD, and it is named for the weeks it closed:
            // the cut-off week and the ones before it that no issue closed.
            long days = (cutoff.getTime() - intervalFrom.getTime()) / 86_400_000L;
            // A quarter-period of tolerance, not rounding: a single week released up
            // to five days late is still one period, and only a window that has
            // genuinely swallowed most of a second one is named for two.
            long periods = (long) Math.floor(days / 7.0 + 0.25);
            if (periods >= 2) {
                // Which weeks, though, is counted in ISO weeks, not in days: the
                // previous issue closed the week its cut-off fell in, so this one
                // closed every week after that one up to its own. Counting the
                // span from the day count instead dropped a fully covered week
                // whenever a release came a few days early or late: nineteen days
                // across weeks 35 to 38 floored to two periods and named the issue
                // 37+38, leaving week 36 named by no issue.
                ZonedDateTime start = Instant.ofEpochMilli(intervalFrom.getTime()).atZone(z);
                long closed = ChronoUnit.WEEKS.between(mondayOf(start), mondayOf(end));
                if (closed >= 2) {
                    weekTo = week;
                    week = end.minusWeeks(closed - 1).get(iso.weekOfWeekBasedYear());
                }
            }
        }

        return new Numbers(week, weekTo, year, end.getMonthValue(), end.getDayOfMonth(), edition);
    }

    /** The Monday of the ISO week the instant falls in, in the instant's own zone. */
    private static LocalDate mondayOf(ZonedDateTime at) {
        return at.toLocalDate().with(ChronoField.DAY_OF_WEEK, 1);
    }

    /**
     * How many weeks an issue is named for: 1 for a single week, 2 for a pair, 3 or more for a range.
     *
     * Counted from the numbers alone, so it answers for a stored issue as for a derived one. Across a year's end
     * the closing week is the smaller number and the first week belongs to the week-year before the cut-off's,
     * which has 52 or 53 ISO weeks: weeks 52 to 2 of an issue named for 2026 are three weeks (52 of 2025, 1 and
     * 2), while weeks 52 to 1 of an issue named for 2027 are three as well, because 2026 has a week 53.
     */
    public static int weeksNamed(Numbers n) {
        if (n == null || n.weekTo() == null) {
            return 1;
        }
        if (n.weekTo() >= n.week()) {
            return n.weekTo() - n.week() + 1;
        }
        return isoWeeksIn(n.year() - 1) - n.week() + 1 + n.weekTo();
    }

    /** The number of ISO weeks in a week-based year: 28 December is always in its last week. */
    private static int isoWeeksIn(int weekBasedYear) {
        return LocalDate.of(weekBasedYear, 12, 28).get(WeekFields.ISO.weekOfWeekBasedYear());
    }

    /**
     * The pattern with the pair joiner written as a range where the issue is named for three weeks or more.
     *
     * A pattern names a double week "${week}+${weekTo}": "36+37", the pair, one issue covering both weeks. An issue
     * that closed three weeks or more has only its first and its last week to print, and "36+38" reads as weeks 36
     * and 38. So the "+" between the two tokens prints as a hyphen there, "36-38", and across a year's end "52-2"
     * beside the later year. Only that "+" is touched: any other text of the pattern is printed as written, and a
     * pair or a single week expands exactly as before. The admin app's issueNumberText and expandIssuePattern
     * follow the same rule, so the strip, the drawer and the name agree.
     */
    private static String joined(String pattern, Numbers numbers) {
        if (pattern == null || weeksNamed(numbers) < 3) {
            return pattern;
        }
        return WEEK_PAIR_JOIN.matcher(pattern).replaceAll("$1-");
    }

    /** The four tokens a name writes its weeks with: the first week and the closing week, each in both variants. */
    private static final Set<String> WEEK_TOKENS = Set.of("week", "week-2-digits", "weekTo", "weekTo-2-digits");

    /** The weeks of a pattern that writes none: the pair form, which prints a range for three weeks or more. */
    private static final String PAIR_FORM = "${week}+${weekTo}";

    /**
     * The week part of a name pattern: the stretch from its first week token to its last ({@code ${week}},
     * {@code ${weekTo}} and their two-digit variants), with the text between them, or null where the pattern
     * writes no week.
     *
     * The stretch ends at the first token of another kind ({@code ${year}}, a month, a day, the edition): a week
     * token after it is not part of the weeks. So "Uge ${week} og ${weekTo}, ${year}" gives
     * "${week} og ${weekTo}", "Uge ${week}, ${year}" gives "${week}", and "Uge ${week}, ${year} (${weekTo})" gives
     * "${week}" too, where taking every week token would print the year inside the weeks. Within the stretch the
     * tokens may stand in any order and any one may stand twice: "${weekTo} back to ${week}" is taken as written.
     *
     * Text before the first week token and after the last is not part of the weeks, with one exception: a bracket,
     * round or square, that the stretch opens and the pattern's text closes after it (before any further token) is
     * taken up to its closing bracket, and one the stretch closes that the text before it opens is taken from its
     * opening bracket. So "Week ${week} (${week-2-digits}) ${year}" gives "${week} (${week-2-digits})", which prints
     * "36 (36)", and never the half-open "36 (36". A bracket the pattern leaves open is left as it is.
     */
    public static String weekSpanOf(String pattern) {
        if (pattern == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(pattern);
        int start = -1;
        int end = -1;
        int before = 0;
        int after = pattern.length();
        while (m.find()) {
            if (WEEK_TOKENS.contains(m.group(1))) {
                if (start < 0) {
                    start = m.start();
                }
                end = m.end();
            } else if (start >= 0) {
                after = m.start();
                break;
            } else {
                before = m.end();
            }
        }
        if (start < 0) {
            return null;
        }
        for (char[] pair : BRACKETS) {
            int open = 0;
            int unopened = 0;
            for (int i = start; i < end; i++) {
                char c = pattern.charAt(i);
                if (c == pair[0]) {
                    open++;
                } else if (c == pair[1]) {
                    if (open > 0) {
                        open--;
                    } else {
                        unopened++;
                    }
                }
            }
            end = closedAfter(pattern, end, after, pair, open);
            start = openedBefore(pattern, start, before, pair, unopened);
        }
        return pattern.substring(start, end);
    }

    /** The brackets {@link #weekSpanOf} keeps whole, each as its opening and its closing character. */
    private static final char[][] BRACKETS = {{'(', ')'}, {'[', ']'}};

    /** The end of the span taken up to where the text after it closes {@code open} brackets, or as it was. */
    private static int closedAfter(String pattern, int end, int limit, char[] pair, int open) {
        for (int i = end; open > 0 && i < limit; i++) {
            char c = pattern.charAt(i);
            if (c == pair[0]) {
                open++;
            } else if (c == pair[1] && --open == 0) {
                return i + 1;
            }
        }
        return end;
    }

    /** The start of the span taken back to where the text before it opens {@code unopened} brackets, or as it was. */
    private static int openedBefore(String pattern, int start, int limit, char[] pair, int unopened) {
        for (int i = start - 1; unopened > 0 && i >= limit; i--) {
            char c = pattern.charAt(i);
            if (c == pair[1]) {
                unopened++;
            } else if (c == pair[0] && --unopened == 0) {
                return i;
            }
        }
        return start;
    }

    /** Whether a week part names the closing week: holds {@code ${weekTo}} in either variant. */
    private static boolean namesClosingWeek(String weekPart) {
        return weekPart.contains("${weekTo}") || weekPart.contains("${weekTo-2-digits}");
    }

    /** The first token of a week part that names the first week, as written, or null where it names none. */
    private static String firstWeekTokenOf(String weekPart) {
        Matcher m = TOKEN.matcher(weekPart);
        while (m.find()) {
            if (m.group(1).equals("week") || m.group(1).equals("week-2-digits")) {
                return m.group();
            }
        }
        return null;
    }

    /**
     * The weeks an issue is named for, as the series' name pattern writes them, in the name the series suggests for
     * the issue: the week part of the pattern ({@link #weekSpanOf}) expanded for this issue by {@link #expand}, the
     * code that expands the name, so the weeks the report prints are the same characters as the weeks in that name.
     * "Uge ${week}, ${year}" gives "36" for an issue of weeks 36 to 38, as the name "Uge 36, 2026" says;
     * "Uge ${week} og ${weekTo}, ${year}" gives "36 og 37" for a pair; "Uge ${week}+${weekTo}, ${year}" gives
     * "36+37" for a pair and "36-38" for three weeks or more ({@link #joined}), across a year's end "52+1" and
     * "52-2". Null where there is no week.
     *
     * ONE PLACE WHERE THE WEEKS LEAVE THE NAME: a single week under a week part that writes the closing week. The
     * closing week expands to nothing there, so the name keeps the text before it, "Uge 36+, 2026" and
     * "Uge 36 og , 2026" (ISSUE13 in be/docs/potential-errors.md). A name is a suggestion an editor sees and can
     * retype, a heading is not, so the weeks are then the first week's token alone, as the pattern writes it ("36",
     * or "07" for {@code ${week-2-digits}}), until the name itself is settled.
     *
     * A pattern that writes no week (or none at all, or one whose week part expands to nothing, as a lone
     * {@code ${weekTo}} does for a single week) gives the pair form, "${week}+${weekTo}": the week alone, "36+37"
     * or "36-38". So a report is always headed by a week, and by all the weeks where its name cannot say them.
     *
     * The weekly reports print it in their heading and page header (the report's {@code weeks}); each source's line
     * in the accumulated report ({@code group.weeks}) takes {@link #allWeeksText}, which differs only where the
     * pattern cannot say a span. A closing week equal to the first is a single week. A week label is not this
     * method's: it is printed whole, in its place ({@code PrintedNumbering.printedWeeks}).
     *
     * @param namePattern the series' name pattern in the report's language, or null
     * @param week the first week, or null
     * @param weekTo the closing week, or null for a single week
     * @param year the week-based year the issue is named for, which places a span across a year's end; null reads
     *             as a span within one year
     */
    public static String weeksText(String namePattern, Integer week, Integer weekTo, Integer year) {
        if (week == null) {
            return null;
        }
        boolean span = weekTo != null && !weekTo.equals(week);
        Numbers numbers = new Numbers(week, span ? weekTo : null, year == null ? 0 : year, 1, 1, null);
        String weekPart = weekSpanOf(namePattern);
        if (weekPart != null && !span && namesClosingWeek(weekPart)) {
            weekPart = firstWeekTokenOf(weekPart);
        }
        if (weekPart != null) {
            try {
                String weeks = expand(weekPart, numbers);
                if (!weeks.isBlank()) {
                    return weeks;
                }
            } catch (RuntimeException e) {
                // A pattern that does not expand names no issue either (S-14 refuses it on the series); the pair
                // form below still heads the document by its weeks.
            }
        }
        return expand(span ? PAIR_FORM : "${week}", numbers);
    }

    /**
     * The weeks a compiled document names one of its sources by: {@link #weeksText}, except for a source of several
     * weeks whose pattern's week part does not write both its first and its closing week, which gets the pair form
     * ("36+37", "36-38").
     *
     * The sections of a compiled document are the record of what its period covered, so a source's line names
     * every week the source covered. A pattern that names the first week alone, "EfS uge ${week} - ${year}", as
     * every pattern restored from the previous system does (that system had no closing-week token), names a double
     * issue by its first week, and {@link #weeksText} heads the weekly itself so; its line in the compiled document
     * would then say "Week 36" for a source of weeks 36 and 37, where the template printed "Week 36 - 37" before.
     * A pattern that writes both weeks, and a single week, are as {@link #weeksText} gives them.
     */
    public static String allWeeksText(String namePattern, Integer week, Integer weekTo, Integer year) {
        boolean span = week != null && weekTo != null && !weekTo.equals(week);
        if (span) {
            String weekPart = weekSpanOf(namePattern);
            if (weekPart == null || !namesClosingWeek(weekPart) || firstWeekTokenOf(weekPart) == null) {
                return weeksText(null, week, weekTo, year);
            }
        }
        return weeksText(namePattern, week, weekTo, year);
    }

    /** The values each token expands to, for a given set of numbers. */
    public static Map<String, String> valuesOf(Numbers n) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("week", String.valueOf(n.week()));
        v.put("week-2-digits", pad(n.week()));
        v.put("weekTo", n.weekTo() == null ? "" : String.valueOf(n.weekTo()));
        v.put("weekTo-2-digits", n.weekTo() == null ? "" : pad(n.weekTo()));
        v.put("year", String.valueOf(n.year()));
        v.put("year-2-digits", pad(n.year() % 100));
        v.put("month", String.valueOf(n.month()));
        v.put("month-2-digits", pad(n.month()));
        v.put("day", String.valueOf(n.day()));
        v.put("day-2-digits", pad(n.day()));
        v.put("edition", n.edition() == null ? "" : String.valueOf(n.edition()));
        return v;
    }

    /**
     * The same values, with the printed labels laid over the derived numbers.
     *
     * ONE OVERLAY, applied here, so that a typed "36+37" reaches the name
     * pattern, the file-name pattern and the citation format by the same route.
     * The alternative was each caller remembering to ask -- and the one that
     * forgot would publish a document whose title and whose file name disagreed
     * about which weeks it covers.
     */
    public static Map<String, String> valuesOf(Numbers n, Labels labels) {
        Map<String, String> v = valuesOf(n);
        if (labels == null) {
            return v;
        }
        if (Labels.notBlank(labels.week())) {
            v.put("week", labels.week());
            v.put("week-2-digits", labels.week());
        }
        if (Labels.notBlank(labels.year())) {
            v.put("year", labels.year());
            v.put("year-2-digits", labels.year());
        }
        return v;
    }

    private static String pad(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }

    /**
     * Expands a pattern.
     *
     * S-14: nothing of the form ${...} may survive. Production serves a real PDF
     * at .../Skydeomraader-%24%7Byear%7D.pdf today because an unexpanded token
     * reached a file name and then a URL, so this is asserted rather than assumed.
     */
    public static String expand(String pattern, Numbers numbers) {
        return expand(pattern, numbers, Labels.NONE);
    }

    /** The same, with the printed labels laid over the derived numbers. */
    public static String expand(String pattern, Numbers numbers, Labels labels) {
        if (pattern == null) {
            return null;
        }
        Map<String, String> values = valuesOf(numbers, labels);

        Matcher m = TOKEN.matcher(joined(pattern, numbers));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            if (!TOKENS.contains(token)) {
                // Loudly. A surviving token becomes part of a file name, and then
                // part of a public URL.
                throw new UnknownTokenException(token);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(token, "")));
        }
        m.appendTail(out);

        String expanded = out.toString();
        if (expanded.contains("${")) {
            throw new IllegalStateException(
                    "a token survived expansion in [" + expanded + "]; this is how a literal ${year} "
                            + "reaches a published file name");
        }
        return expanded;
    }

    /**
     * The one token that is expanded LATER, by the citation layer.
     *
     * A citation format reads "EfS ${week}/${year} ${parameters}": the naming
     * tokens belong to the issue and are fixed the moment it is published, but
     * ${parameters} is whatever the editor types at the moment of citing. It
     * cannot be resolved here, and it must not be treated as unknown.
     */
    public static final String DEFERRED_TOKEN = "parameters";

    /**
     * Expands a citation format, leaving ${parameters} for the citation layer.
     *
     * Separate from expand() rather than a flag on it, because for every OTHER
     * use a surviving ${...} is a bug -- production serves a real PDF at
     * .../Skydeomraader-%24%7Byear%7D.pdf because an unexpanded token reached a
     * file name and then a URL. This is the single place where one surviving
     * token is correct, and it survives by name.
     */
    public static String expandCitation(String pattern, Numbers numbers) {
        return expandCitation(pattern, numbers, Labels.NONE);
    }

    /**
     * The same, with the printed labels laid over the derived numbers.
     *
     * A citation is printed too -- into message HTML, where it stays. An issue
     * whose cover says "uge 36+37" and whose citations say "uge 36" is the same
     * disagreement as a title that argues with its own file name.
     */
    public static String expandCitation(String pattern, Numbers numbers, Labels labels) {
        if (pattern == null) {
            return null;
        }
        Map<String, String> values = valuesOf(numbers, labels);

        Matcher m = TOKEN.matcher(joined(pattern, numbers));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            if (DEFERRED_TOKEN.equals(token)) {
                // Put it back verbatim.
                m.appendReplacement(out, Matcher.quoteReplacement("${" + DEFERRED_TOKEN + "}"));
                continue;
            }
            if (!TOKENS.contains(token)) {
                throw new UnknownTokenException(token);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(token, "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * True when every token in the pattern is one this vocabulary declares.
     *
     * STRICT. ${parameters} is NOT accepted here, and that is the point: this
     * validates file-name, name and link patterns, where a surviving token
     * becomes part of a public URL. Production already serves a real PDF at
     * .../Skydeomraader-%24%7Byear%7D.pdf because one did.
     *
     * Citation formats are validated by {@link #isCitationExpandable} instead.
     */
    public static boolean isExpandable(String pattern) {
        return expandableWith(pattern, false);
    }

    /**
     * True when a CITATION format is expandable -- ${parameters} included.
     *
     * Separate from {@link #isExpandable} because the two answers genuinely
     * differ, and conflating them breaks in one direction or the other: reject
     * ${parameters} here and no citable series can be configured at all, since
     * S-13 requires a reference format for any series that may be cited and the
     * legacy convention puts ${parameters} in it. Accept it there and it reaches
     * a file name.
     */
    public static boolean isCitationExpandable(String pattern) {
        return expandableWith(pattern, true);
    }

    private static boolean expandableWith(String pattern, boolean allowDeferred) {
        if (pattern == null) {
            return true;
        }
        Matcher m = TOKEN.matcher(pattern);
        while (m.find()) {
            String token = m.group(1);
            if (allowDeferred && DEFERRED_TOKEN.equals(token)) {
                continue;
            }
            if (!TOKENS.contains(token)) {
                return false;
            }
        }
        return true;
    }
}
