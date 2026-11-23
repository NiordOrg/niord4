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
package org.niord.core.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.niord.core.area.AreaService;
import org.niord.core.batch.BatchService;
import org.niord.core.category.CategoryService;
import org.niord.core.integration.NiordIntegrationService;
import org.niord.core.mail.ScheduledMailService;
import org.niord.core.mailinglist.MailingListMessageListener;
import org.niord.core.mailinglist.MailingListSchedulerService;
import org.niord.core.message.MessageLuceneIndex;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.ScheduledMessageService;
import org.niord.core.repo.RepositoryService;
import org.niord.core.settings.SettingsRefresher;
import org.niord.web.map.TileFetcher;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration-readiness characterisation of every {@link Scheduled @Scheduled} cron expression in the
 * application. This test exists specifically to de-risk the planned Quarkus&nbsp;&rarr;&nbsp;Spring port.
 *
 * <p><b>The risk it guards.</b> Quarkus parses {@code @Scheduled(cron=...)} using the <em>Quartz</em> cron
 * flavour: six fields {@code second minute hour day-of-month month day-of-week}, where a {@code ?} in the
 * day-of-week field is the "no specific value" placeholder used whenever day-of-month is given. Spring's
 * {@code org.springframework.scheduling.support.CronExpression} is a different six-field parser:
 * <ul>
 *   <li>Spring &le;5.2 <em>rejects</em> {@code ?} outright; Spring 5.3+/6 accepts it as a synonym for
 *       {@code *} — so a verbatim copy's validity is Spring-version-dependent.</li>
 *   <li>Spring numbers day-of-week {@code 0-7} (0/7=SUN) whereas Quartz uses {@code 1-7} (1=SUN) — so a
 *       <em>numeric</em> day-of-week would shift by a day if copied verbatim.</li>
 * </ul>
 * No production test pins the firing schedule (the schedulers are time-driven and otherwise exercised only by
 * direct method invocation), so a mis-translation during the migration would ship silently. See CRON1 in
 * docs/potential-errors.md.
 *
 * <p><b>What this test asserts.</b> It reflectively reads the <em>actual</em> cron string off each production
 * {@code @Scheduled} method (the annotation is {@code @Retention(RUNTIME)}), and for each one pins:
 * <ol>
 *   <li>the exact Quartz expression currently in the code (drift detection — changing a schedule forces this
 *       test, and therefore the migration translation, to be revisited);</li>
 *   <li>the Quartz <em>shape</em> that makes the expression safe to translate: exactly six fields and a
 *       {@code ?} day-of-week placeholder (never a numeric day-of-week, which would hit the numbering
 *       difference above);</li>
 *   <li>a ready-to-use, version-independent Spring six-field translation (the {@code ?} replaced by {@code *},
 *       which every Spring version accepts), validated to be well-formed and to leave the other five fields
 *       untouched.</li>
 * </ol>
 * <p><b>Externalized schedules.</b> A schedule may be declared as a single {@code ${property:default}}
 * placeholder instead of a bare literal, so it can be re-tuned per environment or switched off with
 * {@code -} ({@code Scheduled.CRON_DISABLED}) — {@code ScheduledMessageService}'s two status sweeps, the
 * hourly firing-exercise run and the mailing lists' status-change outbox sweep are, and the IT suite disables them
 * that way; so are the mail sender and the mailing lists' scheduled triggers, which a start on a copy of another
 * deployment's data switches off. What ships is still the placeholder's <em>default</em>, so that is what
 * the table below pins, alongside the property name: renaming the property or editing the default fails this
 * test, which is what makes the property name a real contract with the configuration that sets it (the
 * {@code @TestPropertySource} on {@code ITTest}). A deployment's override is ordinary configuration and out
 * of scope here.
 *
 * <p>It also asserts that the total number of {@code @Scheduled} methods across the known scheduler classes is
 * exactly {@value #EXPECTED_TOTAL}: adding a new scheduled job to any of these classes fails the count check,
 * forcing the new cron to be added to the translation table here before the Spring port.
 *
 * <p><b>Coupling note.</b> This is a pure unit test (no container). Post Quarkus&nbsp;&rarr;&nbsp;Spring port
 * it reflects on {@code org.springframework.scheduling.annotation.Scheduled} (which is also {@code @Retention(RUNTIME)}
 * and exposes the cron string via {@link Scheduled#cron()}). The production cron strings are still written in the
 * <em>Quartz</em> six-field flavour (a trailing {@code ?} day-of-week placeholder), so the migration-translation
 * assertions below remain meaningful: Spring's own parser must accept these expressions, and the documented
 * version-independent translation (the {@code ?} replaced by {@code *}) is what every Spring version accepts.
 * Documented in docs/framework-coupled-tests.md.
 */
public class ScheduledCronExpressionTest {

    /** Expected number of {@code @Scheduled} cron methods across the scheduler classes listed below. */
    private static final int EXPECTED_TOTAL = 16;

    /**
     * One row per production {@code @Scheduled(cron=...)} method: the declaring class, the (zero-arg) method
     * name, the EXACT Quartz expression that ships today (the annotation literal, or the default inside a
     * {@code ${property:default}} placeholder — then also the property name), and the migration-safe Spring
     * six-field translation. Keep this table in sync with {@code grep -rn "@Scheduled" niord/src/main}.
     */
    private record CronRow(Class<?> type, String method, String property, String quartz, String spring) {

        /** A schedule hardcoded in the annotation — no {@code ${property:default}} placeholder. */
        CronRow(Class<?> type, String method, String quartz, String spring) {
            this(type, method, null, quartz, spring);
        }
    }

    private static final List<CronRow> ROWS = List.of(
            // second minute hour day-of-month month day-of-week('?')   ->   Spring (?  ->  *)
            new CronRow(RepositoryService.class,           "cleanUpTempRoot",                    "50 22 * * * ?",   "50 22 * * * *"),
            new CronRow(NiordIntegrationService.class,     "processNextPendingNiordIntegration", "51 */1 * * * ?",  "51 */1 * * * *"),
            new CronRow(BatchService.class,                "cleanUpExpiredBatchJobFiles",        "30 42 */1 * * ?", "30 42 */1 * * *"),
            // Externalized: the IT suite sets the property to "-" so the hourly run cannot touch the firing tests.
            new CronRow(FiringExerciseService.class,       "updateFiringExercises",
                    "niord.schedule.firing-exercise-cron", "50 21 */1 * * ?", "50 21 */1 * * *"),
            new CronRow(CategoryService.class,             "recomputeTreeSortOrder",             "13 23 * * * ?",   "13 23 * * * *"),
            // Externalized: "-" lets a copy of another deployment's data start without sending its pending mails.
            new CronRow(ScheduledMailService.class,        "sendPendingMails",
                    "niord.schedule.mail-send-cron", "24 * * * * ?", "24 * * * * *"),
            new CronRow(ScheduledMailService.class,        "deleteExpiredMails",                 "48 28 5 * * ?",   "48 28 5 * * *"),
            new CronRow(AreaService.class,                 "recomputeTreeSortOrder",             "13 3 * * * ?",    "13 3 * * * *"),
            // Externalized: "-" lets a copy of another deployment's data start without running its due triggers.
            new CronRow(MailingListSchedulerService.class, "checkExecuteScheduledTriggers",
                    "niord.schedule.mailing-list-trigger-cron", "37 */1 */1 * * ?", "37 */1 */1 * * *"),
            // Externalized: the IT suite sets the property to "-", since every context of the run shares the outbox.
            new CronRow(MailingListMessageListener.class, "sweep",
                    "niord.mailinglist.status-change-sweep-cron", "23 * * * * ?", "23 * * * * *"),
            new CronRow(MessageTagService.class,           "removeExpiredMessageTags",           "22 22 */1 * * ?", "22 22 */1 * * *"),
            new CronRow(MessageLuceneIndex.class,          "updateLuceneIndex",                  "38 */1 * * * ?",  "38 */1 * * * *"),
            new CronRow(TileFetcher.class,                 "sweepTileCaches",                    "41 47 3 * * ?",   "41 47 3 * * *"),
            // Externalized: the IT suite sets the property to "-", since every context of the run shares the count.
            new CronRow(SettingsRefresher.class, "pollForChanges",
                    "niord.settings.change-poll-cron", "14/30 * * * * ?", "14/30 * * * * *"),
            // Externalized: the IT suite sets both properties to "-" so the two status sweeps cannot fire.
            // Auto-publish runs at second 7 (SCHED3): a second no other per-minute job uses, and before the
            // mailing-list run at second 37 that it shared until then.
            new CronRow(ScheduledMessageService.class, "checkForExpirablePublishedMessages",
                    "niord.schedule.auto-expire-cron",  "28 * * * * ?", "28 * * * * *"),
            new CronRow(ScheduledMessageService.class, "checkForPublishableMessages",
                    "niord.schedule.auto-publish-cron", "7 * * * * ?",  "7 * * * * *")
    );

    /** The {@code ${property:default}} form an externalized schedule is declared in. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+):(.+)}");

    /**
     * A cron as declared on a {@code @Scheduled} method: the property it can be overridden through
     * ({@code null} when the expression is hardcoded), and the expression that is actually scheduled with
     * the shipped configuration — the literal itself, or the placeholder's default.
     */
    private record DeclaredCron(String property, String expression) {}

    /** Reads and splits the cron declared on a (zero-arg) {@code @Scheduled} method, failing if absent. */
    private static DeclaredCron declaredCron(Class<?> type, String method) throws NoSuchMethodException {
        Method m = type.getDeclaredMethod(method);
        Scheduled[] scheduled = m.getAnnotationsByType(Scheduled.class);
        assertEquals(1, scheduled.length, method + " must carry exactly one @Scheduled annotation");
        String cron = scheduled[0].cron();

        Matcher placeholder = PLACEHOLDER.matcher(cron);
        if (placeholder.matches()) {
            return new DeclaredCron(placeholder.group(1), placeholder.group(2));
        }
        // Anything else containing '${' is a form this test cannot read the shipped expression out of (a
        // placeholder with no default, or a composite) — and a default-less schedule would also fail to start.
        assertFalse(cron.contains("${"), method + " must declare its cron either as a literal or as a single "
                + "${property:default} placeholder, was: '" + cron + "'");
        return new DeclaredCron(null, cron);
    }

    // ------------------------------------------------------------------------------------------------
    // Per-method: the live Quartz expression matches what we documented (drift detection).
    // ------------------------------------------------------------------------------------------------

    @Test
    public void everyScheduledMethodDeclaresTheExpectedQuartzExpression() throws Exception {
        for (CronRow row : ROWS) {
            DeclaredCron declared = declaredCron(row.type(), row.method());
            assertEquals(row.quartz(), declared.expression(),
                    row.type().getSimpleName() + "#" + row.method()
                    + " cron drifted from the documented value — update the migration translation table");
            // Pinning the property alongside the default makes the name a contract with whatever sets it:
            // externalizing/renaming/inlining a schedule cannot silently orphan an override such as the IT
            // suite's niord.schedule.* disables on ITTest.
            assertEquals(row.property(), declared.property(),
                    row.type().getSimpleName() + "#" + row.method()
                    + " cron property drifted from the documented value — update the migration translation "
                    + "table and every configuration that sets the property");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Per-method: Quartz shape that makes the expression safe to port — 6 fields, '?' day-of-week.
    // ------------------------------------------------------------------------------------------------

    @Test
    public void everyExpressionHasSixQuartzFieldsWithAQuestionMarkDayOfWeek() throws Exception {
        for (CronRow row : ROWS) {
            String cron = declaredCron(row.type(), row.method()).expression();
            String[] fields = cron.trim().split("\\s+");

            assertEquals(6, fields.length,
                    row.method() + " must be a 6-field Quartz cron, was: '" + cron + "'");

            // day-of-week (6th field) is the Quartz '?' placeholder — never a numeric DOW, which would be the
            // field that shifts by a day under Spring's 0-7 numbering.
            assertEquals("?", fields[5], row.method() + " day-of-week field must be the Quartz '?' placeholder, was: '"
                    + fields[5] + "'");

            // The remaining five fields are non-empty Quartz tokens (digits, '*', '/', '-', ',').
            for (int i = 0; i < 5; i++) {
                assertFalse(fields[i].isEmpty(), row.method() + " field " + i + " must not be empty");
                assertTrue(fields[i].matches("[0-9*/,\\-]+"),
                        row.method() + " field " + i + " ('" + fields[i] + "') is not a recognised cron token");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Per-method: the documented Spring translation is well-formed and only changes the '?' placeholder.
    // ------------------------------------------------------------------------------------------------

    @Test
    public void springTranslationIsSixFieldsWithoutQuestionMarkAndPreservesTheOtherFields() {
        for (CronRow row : ROWS) {
            String[] quartz = row.quartz().trim().split("\\s+");
            String[] spring = row.spring().trim().split("\\s+");

            assertEquals(6, spring.length, row.method() + " Spring translation must be a 6-field cron");

            // The only permitted change Quartz -> Spring here is the day-of-week '?' placeholder becoming '*'
            // (accepted by every Spring version). The first five fields must be identical.
            for (int i = 0; i < 5; i++) {
                assertEquals(quartz[i], spring[i],
                        row.method() + " Spring field " + i + " must be unchanged from Quartz");
            }
            assertEquals("?", quartz[5], row.method() + " Quartz day-of-week placeholder must be '?'");
            assertEquals("*", spring[5], row.method() + " Spring day-of-week must replace '?' with '*'");
            assertFalse(row.spring().contains("?"),
                    row.method() + " Spring translation must not contain a '?' that older Spring rejects");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Coverage guard: exactly EXPECTED_TOTAL @Scheduled methods exist across the known scheduler classes.
    // A new scheduled job added to any of these classes fails this, forcing it into the translation table.
    // ------------------------------------------------------------------------------------------------

    @Test
    public void theKnownSchedulerClassesDeclareExactlyTheExpectedNumberOfScheduledMethods() {
        // Distinct classes referenced by the translation table.
        List<Class<?>> classes = new ArrayList<>();
        for (CronRow row : ROWS) {
            if (!classes.contains(row.type())) {
                classes.add(row.type());
            }
        }

        Map<String, Integer> discovered = new LinkedHashMap<>();
        int total = 0;
        for (Class<?> type : classes) {
            int count = 0;
            for (Method m : type.getDeclaredMethods()) {
                if (m.getAnnotationsByType(Scheduled.class).length > 0) {
                    count++;
                }
            }
            discovered.put(type.getSimpleName(), count);
            total += count;
        }

        assertEquals(EXPECTED_TOTAL, total,
                "the number of @Scheduled methods changed (" + discovered + ") — a scheduled job was "
                + "added or removed; add/remove the matching row in this migration translation table");

        // Sanity: the table itself enumerates exactly EXPECTED_TOTAL methods.
        assertEquals(EXPECTED_TOTAL, ROWS.size(), "translation table size must match EXPECTED_TOTAL");
    }

    // ------------------------------------------------------------------------------------------------
    // Post-migration proof (CRON1 resolved): Spring's own CronExpression parser accepts every LIVE
    // production expression (the trailing '?' form), and the '?' form fires on the IDENTICAL schedule
    // as the documented '?'->'*' translation. This closes CRON1: Spring Framework (5.3+/6/7) maps a
    // day-of-week '?' to '*', and because every day-of-week field here is '?' (never numeric) the
    // Quartz-vs-Spring DOW renumbering cannot bite. A future scheduler added with a NUMERIC day-of-week
    // would make the two forms diverge and fail this test.
    // ------------------------------------------------------------------------------------------------

    @Test
    public void springCronParsesEveryLiveExpressionAndQuestionMarkFiresIdenticallyToStar() throws Exception {
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        for (CronRow row : ROWS) {
            String live = declaredCron(row.type(), row.method()).expression();   // the production '?' form
            CronExpression liveForm = CronExpression.parse(live);      // Spring must accept '?' verbatim
            CronExpression starForm = CronExpression.parse(row.spring());

            LocalDateTime liveNext = base;
            LocalDateTime starNext = base;
            for (int i = 0; i < 50; i++) {
                liveNext = liveForm.next(liveNext);
                starNext = starForm.next(starNext);
                assertEquals(starNext, liveNext,
                        row.type().getSimpleName() + "#" + row.method() + " next-fire #" + i
                        + " must match between the live '?' form and the '?'->'*' translation");
            }
        }
    }
}
