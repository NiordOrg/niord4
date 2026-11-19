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
package org.niord.core.mailinglist;

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mailinglist.vo.MailingListReportVo;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageService;
import org.niord.core.script.ScriptResourceService;
import org.niord.core.user.Contact;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.ScriptResourceFixtures;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MailingListExecutionService}, run against the seeded MySQL container of
 * the {@link ITTest} infrastructure.
 *
 * <p>The service is essentially a pipeline of bail-out branches: a trigger is only turned into one or
 * more {@link ScheduledMail}s when it is the right type, has at least one language variant (desc), has
 * a Freemarker script resource, passes its message filter / has a non-blank message query, and has at
 * least one recipient for the language. These tests pin every one of those branches.
 *
 * <p><b>Rendering oracle.</b> The test classpath carries a Freemarker template at
 * {@code templates/mailinglist/it-test.ftl}, added as a database row before each test
 * ({@link ScriptResourceFixtures}; since C4.2a a template is read from the database only), that renders to the
 * literal text
 * {@code IT-MAILINGLIST-TEMPLATE lang=<lang> count=<N>}, where {@code N} is the number of messages
 * handed to the template. Adding that path to a trigger's {@code scriptResourcePaths} exercises real
 * Freemarker rendering, and the {@code count=N} marker lets us assert how many messages the trigger
 * fed to the template.
 *
 * <p><b>Recipient oracle.</b> A list recipient is materialised into a {@code ScheduledMailRecipient}
 * whose address equals {@link org.niord.core.mail.IMailable#computeInternetAddress()} — for a
 * {@link Contact} with name {@code "X"} and email {@code "e@h"} that is {@code "X <e@h>"}. A contact
 * with {@code language == null} is included for the default language ("en"), which is the only language
 * these triggers define.
 *
 * <p><b>Transaction model (critical).</b> Most tests are wrapped in {@link TestTransaction} so all
 * writes roll back and the shared seed database stays pristine. The direct overloads
 * {@code executeStatusChangeTrigger(trigger, message, persist)} (unannotated, joins the test tx) and
 * {@code executeScheduledTrigger(trigger, persist)} ({@code @Transactional}, joins the test tx) see the
 * trigger that was flushed inside the test transaction. The id-based overloads
 * {@code executeScheduledTrigger(Integer)}, {@code executeStatusChangeTrigger(Integer, String, long)},
 * {@code computeNextScheduledExecution(Integer)} and {@code postponeFailedScheduledTrigger(Integer)} are
 * {@code @Transactional(REQUIRES_NEW)}: under a
 * {@link TestTransaction} they run in a fresh transaction that cannot see the uncommitted trigger, so
 * {@code getByPrimaryKey} returns {@code null} and only the invalid-id path is reachable. The
 * {@code computeNextScheduledExecution} and {@code postponeFailedScheduledTrigger} happy paths therefore commit
 * their fixtures through {@link Tx#requiringNew()} and remove them again in {@link #afterEach()}.
 *
 * <p>{@code getMailingListReports} and {@code executeMailingListReport} are not transactional and run in
 * the ambient (test) transaction, so they too see flushed-but-uncommitted rows.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MailingListExecutionServiceITTest extends ITTest {

    private static final String IT_TEMPLATE = "templates/mailinglist/it-test.ftl";

    @Autowired
    MailingListExecutionService executionService;

    @Autowired
    MailingListService mailingListService;

    @Autowired
    MessageService messageService;

    @Autowired
    NiordApp app;

    @Autowired
    ScriptResourceService resourceService;

    @PersistenceContext
    EntityManager em;

    @BeforeEach
    void addTheTestTemplate() {
        ScriptResourceFixtures.ensure(resourceService, IT_TEMPLATE);
    }

    /** Ids of mailing lists COMMITTED outside the test transaction, to be cleaned up in {@link #afterEach()}. */
    private final List<Integer> committedMailingListIds = new ArrayList<>();

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds and flushes an active mailing list with a unique id and an "en" name in the test tx. */
    private MailingList persistActiveMailingList() {
        MailingList ml = new MailingList();
        ml.setMailingListId("IT-MLX-" + System.nanoTime());
        ml.setActive(true);
        ml.createDesc("en").setName("Rep");
        em.persist(ml);
        em.flush();
        return ml;
    }

    /**
     * Persists a contact and adds it as a recipient of the mailing list. The contact has no language,
     * so it is included for the default language ("en"). Returns its computed internet address.
     */
    private String addContactRecipient(MailingList ml, String name, String email) {
        Contact contact = new Contact();
        contact.setName(name);
        contact.setEmail(email);
        em.persist(contact);                // ManyToMany has no cascade - must persist separately
        ml.getContacts().add(contact);
        em.flush();
        return contact.computeInternetAddress();
    }

    /** Builds a trigger of the given type bound to the list, flushes it, and returns it. */
    private MailingListTrigger persistTrigger(MailingList ml, TriggerType type) {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(type);
        ml.addTrigger(trigger);
        em.persist(trigger);
        em.flush();
        return trigger;
    }

    /** Adds an "en" desc carrying the given subject to the trigger and re-flushes. */
    private void addDesc(MailingListTrigger trigger, String subject) {
        trigger.createDesc("en").setSubject(subject);
        em.flush();
    }

    /** Adds the IT Freemarker template to the trigger's script resources and re-flushes. */
    private void addItTemplate(MailingListTrigger trigger) {
        trigger.getScriptResourcePaths().add(IT_TEMPLATE);
        em.flush();
    }

    /** Builds, persists and returns a minimal renderable DRAFT NW message in the test tx. */
    private Message createRenderableMessage(String title) throws Exception {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw")); // substituted with the persisted series
        msg.setType(Type.LOCAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return messageService.createMessage(msg);
    }

    @AfterEach
    void afterEach() {
        // Best-effort cleanup of mailing lists committed by REQUIRES_NEW tests (cascades the triggers).
        for (Integer id : committedMailingListIds) {
            try {
                Tx.requiringNew().run(() -> {
                    MailingList ml = em.find(MailingList.class, id);
                    if (ml != null) {
                        em.remove(ml);
                    }
                });
            } catch (Exception ignored) {
                // swallow - cleanup is best-effort
            }
        }
        committedMailingListIds.clear();

        // Best-effort removal of any committed ScriptResource row that mirrors the IT template path.
        try {
            Tx.requiringNew().run(() ->
                    em.createQuery("delete from ScriptResource r where r.path = :p")
                            .setParameter("p", IT_TEMPLATE)
                            .executeUpdate());
        } catch (Exception ignored) {
            // swallow - best-effort
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // executeStatusChangeTrigger(trigger, message, persist) - bail-out branches
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void statusChangeTriggerRejectsWrongTriggerType() {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger scheduled = persistTrigger(ml, TriggerType.SCHEDULED);

        assertThrows(IllegalAccessException.class,
                () -> executionService.executeStatusChangeTrigger(scheduled, new Message(), false),
                "a SCHEDULED trigger must be rejected by the status-change overload");
    }

    @Test
    @Transactional
    void statusChangeTriggerWithoutLanguagesYieldsNoMails() throws Exception {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.STATUS_CHANGE);
        // No descs -> no languages.

        List<ScheduledMail> mails = executionService.executeStatusChangeTrigger(trigger, new Message(), false);

        assertTrue(mails.isEmpty(),
                "a status-change trigger with no language variants must produce no mails");
    }

    @Test
    @Transactional
    void statusChangeTriggerWithoutFreemarkerResourceYieldsNoMails() throws Exception {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.STATUS_CHANGE);
        addDesc(trigger, "Subj");
        // No script resource paths.

        List<ScheduledMail> mails = executionService.executeStatusChangeTrigger(trigger, new Message(), false);

        assertTrue(mails.isEmpty(),
                "a status-change trigger without a Freemarker template must produce no mails");
    }

    @Test
    @Transactional
    void statusChangeTriggerWhoseFilterRejectsMessageYieldsNoMails() throws Exception {
        MailingList ml = persistActiveMailingList();
        addContactRecipient(ml, "Rcpt", "rcpt-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.STATUS_CHANGE);
        addDesc(trigger, "Subj");
        addItTemplate(trigger);
        trigger.setMessageFilter("false"); // a filter that never matches
        em.flush();

        List<ScheduledMail> mails = executionService.executeStatusChangeTrigger(trigger, new Message(), false);

        assertTrue(mails.isEmpty(),
                "a message that fails the trigger's message filter must produce no mails");
    }

    @Test
    @Transactional
    void statusChangeTriggerHappyPathBuildsOneMailPerRecipientWithRenderedBody() throws Exception {
        MailingList ml = persistActiveMailingList();
        String address = addContactRecipient(ml, "Alice", "alice-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.STATUS_CHANGE);
        addDesc(trigger, "Status subj");
        addItTemplate(trigger);
        // No message filter -> the message qualifies.

        Message message = createRenderableMessage("Status title " + System.nanoTime());

        List<ScheduledMail> mails = executionService.executeStatusChangeTrigger(trigger, message, false);

        assertEquals(1, mails.size(),
                "one recipient on a qualifying status-change trigger must yield exactly one mail");
        ScheduledMail mail = mails.get(0);
        assertEquals("Status subj", mail.getSubject(),
                "the mail subject must come from the trigger's en desc subject");
        assertEquals(1, mail.getRecipients().size(), "the split mail must carry exactly one recipient");
        assertEquals(address, mail.getRecipients().get(0).getAddress(),
                "the recipient address must equal the contact's computed internet address");
        String html = mail.getHtmlContents();
        assertTrue(html.contains("IT-MAILINGLIST-TEMPLATE"),
                "the body must be the rendered IT Freemarker template");
        assertTrue(html.contains("count=1"),
                "the status-change template must be fed exactly the single message (count=1)");
    }

    /**
     * The mailing-list templates print the texts of a message unescaped. With the production subscriber template,
     * a script, an event handler, a style, a class outside the message-HTML vocabulary, a foreign image and a
     * {@code data:} image in the details do not reach the mail, while ordinary formatting, the position table's
     * classes and the application's own image do, and a root-relative link or image becomes absolute. The stored
     * message keeps what the editor wrote. FAILS on the pre-fix code, which mailed the details as the editor
     * wrote them.
     */
    @Test
    @Transactional
    void statusChangeMailCarriesTheTextsOfTheMessageCleaned() throws Exception {
        MailingList ml = persistActiveMailingList();
        addContactRecipient(ml, "Subscriber", "subscriber-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.STATUS_CHANGE);
        addDesc(trigger, "Navigational warning");
        trigger.getScriptResourcePaths().add("templates/mailinglist/publish-navwarn.ftl");
        em.flush();

        String baseUri = StringUtils.stripEnd(app.getBaseUri(), "/");
        String details = "<p>IT <strong>bold</strong></p><ul><li>it-item</li></ul>"
                + "<table><tbody><tr><td>it-cell</td></tr></tbody></table>"
                + "<p><a href=\"/rest/repo/file/messages/it/notice.pdf\">it-notice</a></p>"
                + "<table class=\"position-table it-unknown\"><tbody><tr><td class=\"pos-index\">1)</td>"
                + "<td class=\"pos-col\">it-pos</td></tr></tbody></table>"
                + "<p><img src=\"/rest/repo/file/messages/it/it-own.png\" width=\"120\"></p>"
                + "<script>alert('it-script')</script>"
                + "<p onclick=\"alert('it-onclick')\" style=\"position:fixed\">it-clicked</p>"
                + "<img src=\"https://tracker.example.org/it.png\" onerror=\"alert('it-onerror')\">"
                + "<img src=\"data:image/png;base64,aXQtZGF0YQ==\">";
        Message message = createRenderableMessage("Status title " + System.nanoTime());
        message.getParts().get(0).getDesc("en").setDetails(details);
        em.flush();

        List<ScheduledMail> mails = executionService.executeStatusChangeTrigger(trigger, message, false);

        assertEquals(1, mails.size(), "the one subscriber must get one mail");
        String html = mails.get(0).getHtmlContents();
        assertTrue(html.contains("<strong>bold</strong>"), html);
        assertTrue(html.contains("<li>it-item</li>"), html);
        assertTrue(html.contains("<td>it-cell</td>"), html);
        assertTrue(html.contains("<p>it-clicked</p>"), html);
        assertTrue(html.contains("href=\"" + baseUri + "/rest/repo/file/messages/it/notice.pdf\""),
                "a root-relative link must become absolute: " + html);
        assertTrue(html.contains("<table class=\"position-table\"><tbody><tr><td class=\"pos-index\">1)</td>"
                + "<td class=\"pos-col\">it-pos</td>"), "the position table keeps its classes: " + html);
        assertTrue(html.contains("<img src=\"" + baseUri + "/rest/repo/file/messages/it/it-own.png\" width=\"120\">"),
                "an image of the application stays, made absolute: " + html);
        assertFalse(html.contains("it-unknown"), html);
        assertFalse(html.contains("position:fixed"), html);
        assertFalse(html.contains("it-script"), html);
        assertFalse(html.contains("it-onclick"), html);
        assertFalse(html.contains("it-onerror"), html);
        assertFalse(html.contains("tracker.example.org"), html);
        assertFalse(html.contains("aXQtZGF0YQ"), html);

        // Only the mail is cleaned: the stored message keeps what the editor wrote
        Integer id = message.getId();
        em.flush();
        em.clear();
        assertEquals(details, em.find(Message.class, id).getParts().get(0).getDesc("en").getDetails());
    }

    // ------------------------------------------------------------------------------------------- //
    // executeScheduledTrigger(trigger, persist) - bail-out branches
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void scheduledTriggerRejectsWrongTriggerType() {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger statusChange = persistTrigger(ml, TriggerType.STATUS_CHANGE);

        assertThrows(IllegalAccessException.class,
                () -> executionService.executeScheduledTrigger(statusChange, false),
                "a STATUS_CHANGE trigger must be rejected by the scheduled overload");
    }

    @Test
    @Transactional
    void scheduledTriggerWithoutLanguagesYieldsNoMails() throws Exception {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        // No descs -> no languages.

        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, false);

        assertTrue(mails.isEmpty(),
                "a scheduled trigger with no language variants must produce no mails");
    }

    @Test
    @Transactional
    void scheduledTriggerWithoutFreemarkerResourceYieldsNoMails() throws Exception {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        addDesc(trigger, "Subj");
        trigger.setMessageQuery("status=PUBLISHED");
        em.flush();
        // No script resource paths.

        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, false);

        assertTrue(mails.isEmpty(),
                "a scheduled trigger without a Freemarker template must produce no mails");
    }

    @Test
    @Transactional
    void scheduledTriggerWithBlankMessageQueryYieldsNoMails() throws Exception {
        MailingList ml = persistActiveMailingList();
        addContactRecipient(ml, "Rcpt", "rcpt-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        addDesc(trigger, "Subj");
        addItTemplate(trigger);
        // messageQuery left blank.

        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, false);

        assertTrue(mails.isEmpty(),
                "a scheduled trigger with a blank message query must produce no mails");
    }

    @Test
    @Transactional
    void scheduledTriggerHappyPathBuildsOneMailPerRecipientWithRenderedBody() throws Exception {
        MailingList ml = persistActiveMailingList();
        String address = addContactRecipient(ml, "Bob", "bob-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        addDesc(trigger, "Sched subj");
        addItTemplate(trigger);
        // Non-blank query that matches zero messages (no series named __none__).
        trigger.setMessageQuery("messageSeries=__none__&status=PUBLISHED");
        em.flush();

        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, false);

        assertEquals(1, mails.size(),
                "one recipient on a runnable scheduled trigger must yield exactly one mail");
        ScheduledMail mail = mails.get(0);
        assertEquals("Sched subj", mail.getSubject(),
                "the mail subject must come from the trigger's en desc subject");
        assertEquals(1, mail.getRecipients().size(), "the split mail must carry exactly one recipient");
        assertEquals(address, mail.getRecipients().get(0).getAddress(),
                "the recipient address must equal the contact's computed internet address");
        String html = mail.getHtmlContents();
        assertTrue(html.contains("IT-MAILINGLIST-TEMPLATE"),
                "the body must be the rendered IT Freemarker template");
        assertTrue(html.contains("count=0"),
                "a query matching no messages must feed the template an empty message list (count=0)");
    }

    /**
     * A trigger written in a language the mail texts lack (the base product's are English only) takes the
     * date formats of {@code ${date-short}} and {@code ${date-long}} from the default language. FAILS on the
     * pre-fix code, which read the trigger's language only and threw a NullPointerException from
     * {@code new SimpleDateFormat(null)}, so the scheduler failed the trigger every minute.
     */
    @Test
    @Transactional
    void scheduledTriggerInALanguageWithoutMailTextsExpandsTheDatesInTheDefaultLanguage() throws Exception {
        MailingList ml = persistActiveMailingList();
        addContactRecipient(ml, "Dora", "dora-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        trigger.createDesc("da").setSubject("Oplæsning ${date-short} / ${date-long}");
        addItTemplate(trigger);
        trigger.setMessageQuery("messageSeries=__none__&status=PUBLISHED");
        em.flush();

        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, false);

        assertEquals(1, mails.size(), "the one recipient of a single-language trigger must get one mail");
        java.util.Date today = new java.util.Date();
        java.util.Locale danish = java.util.Locale.forLanguageTag("da");
        String expected = "Oplæsning " + new java.text.SimpleDateFormat("dd-MM-yyyy", danish).format(today)
                + " / " + new java.text.SimpleDateFormat("d. MMMM yyyy", danish).format(today);
        assertEquals(expected, mails.get(0).getSubject(),
                "the dates must be formatted with the default language's mail.date_format texts, "
                        + "in the trigger's language");
    }

    // ------------------------------------------------------------------------------------------- //
    // The subject's dates: the trigger's language and zone (MAIL10)
    // ------------------------------------------------------------------------------------------- //

    /**
     * A zone whose date today differs from the JVM's. Pacific/Kiritimati (UTC+14) and Etc/GMT+12 (UTC-12) are 26
     * hours apart, so their dates always differ and at least one of them differs from the JVM's: it is just after
     * local midnight there while the JVM's day has not turned, or the other way round.
     */
    private static java.time.ZoneId aZoneInAnotherDayThanTheJvm() {
        java.time.LocalDate jvmToday = java.time.LocalDate.now(java.time.ZoneId.systemDefault());
        for (String id : List.of("Pacific/Kiritimati", "Etc/GMT+12")) {
            java.time.ZoneId zone = java.time.ZoneId.of(id);
            if (!java.time.LocalDate.now(zone).equals(jvmToday)) {
                return zone;
            }
        }
        throw new IllegalStateException("no zone in another day than the JVM's");
    }

    /** Today's date with the pattern, the language's words and the zone's day, at the given time */
    private static String dateAt(java.util.Date when, String pattern, java.util.Locale locale, java.time.ZoneId zone) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(pattern, locale);
        format.setTimeZone(java.util.TimeZone.getTimeZone(zone));
        return format.format(when);
    }

    /** Asserts that the subject is the date read just before or just after the run, in case the day turned */
    private static void assertSubjectIsTheDate(String subject, java.util.Date before, java.util.Date after,
                                               String pattern, java.util.Locale locale, java.time.ZoneId zone) {
        String atStart = dateAt(before, pattern, locale, zone);
        String atEnd = dateAt(after, pattern, locale, zone);
        assertTrue(subject.equals(atStart) || subject.equals(atEnd),
                "the subject's date must be today in " + zone + " in the " + locale + " words: expected "
                        + atStart + " but was " + subject);
    }

    /**
     * A scheduled trigger's subject date is today in the trigger's own zone, the zone its schedule runs in. Just
     * after local midnight east of UTC it is the new day there. FAILS on the pre-fix code, which read the day in the
     * JVM's zone.
     */
    @Test
    @Transactional
    void scheduledTriggerSubjectDateIsTodayInTheTriggersZone() throws Exception {
        java.time.ZoneId zone = aZoneInAnotherDayThanTheJvm();
        MailingList ml = persistActiveMailingList();
        addContactRecipient(ml, "Erin", "erin-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        addDesc(trigger, "${date-short}");
        addItTemplate(trigger);
        trigger.setScheduleType(ScheduleType.DAILY);
        trigger.setScheduledExecutionTime("06:00");
        trigger.setScheduledExecutionTimeZone(zone.getId());
        trigger.setMessageQuery("messageSeries=__none__&status=PUBLISHED");
        em.flush();

        java.util.Date before = new java.util.Date();
        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, false);
        java.util.Date after = new java.util.Date();

        assertEquals(1, mails.size());
        assertSubjectIsTheDate(mails.get(0).getSubject(), before, after,
                "dd-MM-yyyy", java.util.Locale.forLanguageTag("en"), zone);
    }

    /**
     * A status-change trigger has no zone of its own: its subject date is today in the zone of the message's
     * series, the zone of the domain that holds it. FAILS on the pre-fix code, which read the day in the JVM's
     * zone.
     */
    @Test
    @Transactional
    void statusChangeTriggerSubjectDateIsTodayInTheZoneOfTheMessagesSeries() throws Exception {
        java.time.ZoneId zone = aZoneInAnotherDayThanTheJvm();
        MailingList ml = persistActiveMailingList();
        addContactRecipient(ml, "Finn", "finn-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.STATUS_CHANGE);
        addDesc(trigger, "${date-short}");
        addItTemplate(trigger);
        Message message = createRenderableMessage("Status title " + System.nanoTime());

        // The domains holding the message's series are put in the zone; the test transaction rolls it back
        List<Integer> domainIds = em.createQuery(
                        "select d.id from Domain d join d.messageSeries s where s.seriesId = 'dma-nw'", Integer.class)
                .getResultList();
        assertFalse(domainIds.isEmpty(), "precondition: the seed puts dma-nw in a domain");
        em.createQuery("update Domain d set d.timeZone = :zone where d.id in :ids")
                .setParameter("zone", zone.getId())
                .setParameter("ids", domainIds)
                .executeUpdate();

        java.util.Date before = new java.util.Date();
        List<ScheduledMail> mails = executionService.executeStatusChangeTrigger(trigger, message, false);
        java.util.Date after = new java.util.Date();

        assertEquals(1, mails.size());
        assertSubjectIsTheDate(mails.get(0).getSubject(), before, after,
                "dd-MM-yyyy", java.util.Locale.forLanguageTag("en"), zone);
    }

    /**
     * The month words of {@code ${date-long}} are in the trigger's language, not the server's: the test picks a
     * language other than the JVM's. FAILS on the pre-fix code, which used the JVM's default language.
     */
    @Test
    @Transactional
    void subjectMonthWordsAreInTheTriggersLanguage() throws Exception {
        String language = "da".equals(java.util.Locale.getDefault().getLanguage()) ? "en" : "da";
        java.util.Locale locale = java.util.Locale.forLanguageTag(language);
        MailingList ml = persistActiveMailingList();
        addContactRecipient(ml, "Gus", "gus-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        trigger.createDesc(language).setSubject("${date-long}");
        addItTemplate(trigger);
        trigger.setMessageQuery("messageSeries=__none__&status=PUBLISHED");
        em.flush();

        java.util.Date before = new java.util.Date();
        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, false);
        java.util.Date after = new java.util.Date();

        assertEquals(1, mails.size());
        assertSubjectIsTheDate(mails.get(0).getSubject(), before, after,
                "d. MMMM yyyy", locale, java.time.ZoneId.systemDefault());
    }

    @Test
    @Transactional
    void scheduledTriggerWithPersistTrueSavesTheMails() throws Exception {
        MailingList ml = persistActiveMailingList();
        addContactRecipient(ml, "Carol", "carol-" + System.nanoTime() + "@example.com");
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        String uniqueSubject = "IT-MLX-persist-" + System.nanoTime();
        addDesc(trigger, uniqueSubject);
        addItTemplate(trigger);
        trigger.setMessageQuery("messageSeries=__none__&status=PUBLISHED");
        em.flush();

        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, true);
        assertEquals(1, mails.size(), "exactly one mail must be produced and persisted");

        // Verify the persisted DB state (still inside the rolled-back test tx).
        em.flush();
        em.clear();
        Long count = em.createQuery(
                        "select count(m) from ScheduledMail m where m.subject = :s", Long.class)
                .setParameter("s", uniqueSubject)
                .getSingleResult();
        assertEquals(1L, count,
                "persist=true must insert exactly one ScheduledMail row with the unique subject");
    }

    // ------------------------------------------------------------------------------------------- //
    // Two or more trigger languages: who receives which mail
    // ------------------------------------------------------------------------------------------- //

    /** Persists a contact with the given preferred language (null for none) on the list; returns its address. */
    private String addContactRecipient(MailingList ml, String name, String email, String language) {
        Contact contact = new Contact();
        contact.setName(name);
        contact.setEmail(email);
        contact.setLanguage(language);
        em.persist(contact);
        ml.getContacts().add(contact);
        em.flush();
        return contact.computeInternetAddress();
    }

    /** The mail each address received, failing when an address received more than one */
    private static java.util.Map<String, ScheduledMail> mailByAddress(List<ScheduledMail> mails) {
        java.util.Map<String, ScheduledMail> byAddress = new java.util.HashMap<>();
        for (ScheduledMail mail : mails) {
            assertEquals(1, mail.getRecipients().size(), "the split mail carries one recipient");
            String address = mail.getRecipients().get(0).getAddress();
            assertNull(byAddress.put(address, mail), "each recipient receives one mail: " + address);
        }
        return byAddress;
    }

    /**
     * Three contacts on the list: one whose preferred language ("fr") the trigger lacks, one who prefers one of the
     * trigger's languages ("da", written "DA" to pin that the match ignores case) and one without a language. Returns
     * the three addresses, in that order.
     */
    private String[] persistContactsInThreeLanguages(MailingList ml) {
        long n = System.nanoTime();
        return new String[] {
                addContactRecipient(ml, "French", "fr-" + n + "@example.com", "fr"),
                addContactRecipient(ml, "Danish", "da-" + n + "@example.com", "DA"),
                addContactRecipient(ml, "Nolang", "none-" + n + "@example.com", null) };
    }

    /** The mails of a trigger with subjects in English and Danish, for the contacts above */
    private static void assertTheDefaultLanguageMailReachesTheRecipientWhoseLanguageTheTriggerLacks(
            List<ScheduledMail> mails, String[] addresses) throws java.io.IOException {
        assertEquals(3, mails.size(), "each of the three contacts receives one mail");
        java.util.Map<String, ScheduledMail> byAddress = mailByAddress(mails);
        ScheduledMail french = byAddress.get(addresses[0]);
        ScheduledMail danish = byAddress.get(addresses[1]);
        ScheduledMail none = byAddress.get(addresses[2]);
        assertNotNull(french, "the contact whose language the trigger lacks receives a mail");
        assertEquals("EN subj", french.getSubject(), "the trigger's default-language mail");
        assertTrue(french.getHtmlContents().contains("lang=en"), french.getHtmlContents());
        assertNotNull(danish, "the contact who prefers Danish receives a mail");
        assertEquals("DA subj", danish.getSubject(), "the mail in its own language");
        assertTrue(danish.getHtmlContents().contains("lang=da"), danish.getHtmlContents());
        assertNotNull(none, "the contact without a language receives a mail");
        assertEquals("EN subj", none.getSubject(), "the trigger's default-language mail");
    }

    /**
     * With two trigger languages, a recipient whose preferred language the trigger lacks receives the trigger's
     * default-language mail, as a recipient without a language does. FAILS on the pre-fix code, which mailed the
     * French contact nothing.
     */
    @Test
    @Transactional
    void statusChangeTriggerSendsTheDefaultLanguageMailToARecipientWhoseLanguageTheTriggerLacks() throws Exception {
        MailingList ml = persistActiveMailingList();
        String[] addresses = persistContactsInThreeLanguages(ml);
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.STATUS_CHANGE);
        trigger.createDesc("en").setSubject("EN subj");
        trigger.createDesc("da").setSubject("DA subj");
        addItTemplate(trigger);
        Message message = createRenderableMessage("Status title " + System.nanoTime());

        List<ScheduledMail> mails = executionService.executeStatusChangeTrigger(trigger, message, false);

        assertTheDefaultLanguageMailReachesTheRecipientWhoseLanguageTheTriggerLacks(mails, addresses);
    }

    /**
     * The scheduled path picks its recipients the same way. FAILS on the pre-fix code, which mailed the French
     * contact nothing.
     */
    @Test
    @Transactional
    void scheduledTriggerSendsTheDefaultLanguageMailToARecipientWhoseLanguageTheTriggerLacks() throws Exception {
        MailingList ml = persistActiveMailingList();
        String[] addresses = persistContactsInThreeLanguages(ml);
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        trigger.createDesc("en").setSubject("EN subj");
        trigger.createDesc("da").setSubject("DA subj");
        addItTemplate(trigger);
        trigger.setMessageQuery("messageSeries=__none__&status=PUBLISHED");
        em.flush();

        List<ScheduledMail> mails = executionService.executeScheduledTrigger(trigger, false);

        assertTheDefaultLanguageMailReachesTheRecipientWhoseLanguageTheTriggerLacks(mails, addresses);
    }

    // ------------------------------------------------------------------------------------------- //
    // Id-based overloads (REQUIRES_NEW) - only the invalid-id path is reachable under @Transactional
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void executeScheduledTriggerByUnknownIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> executionService.executeScheduledTrigger(Integer.MAX_VALUE),
                "an unknown scheduled-trigger id must be rejected");
    }

    @Test
    @Transactional
    void executeStatusChangeTriggerByUnknownIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> executionService.executeStatusChangeTrigger(Integer.MAX_VALUE, "nope", 0L),
                "an unknown status-change-trigger id must be rejected");
    }

    // ------------------------------------------------------------------------------------------- //
    // computeNextScheduledExecution(Integer) - REQUIRES_NEW, committed fixture
    // ------------------------------------------------------------------------------------------- //

    @Test
    void computeNextScheduledExecutionStampsTheNextExecutionOnACommittedTrigger() {
        // Commit an active mailing list with a SCHEDULED trigger that has a concrete schedule, so the
        // REQUIRES_NEW computeNextScheduledExecution can re-read it and compute a next execution time.
        int[] triggerIdHolder = new int[1];
        Integer mailingListId = Tx.requiringNew().call(() -> {
            MailingList ml = new MailingList();
            ml.setMailingListId("IT-MLX-cnse-" + System.nanoTime());
            ml.setActive(true);
            ml.createDesc("en").setName("Rep");

            MailingListTrigger trigger = new MailingListTrigger();
            trigger.setType(TriggerType.SCHEDULED);
            trigger.setScheduleType(ScheduleType.DAILY);
            trigger.setScheduledExecutionTime("12:00");
            ml.addTrigger(trigger);

            em.persist(ml);
            em.flush();
            triggerIdHolder[0] = trigger.getId();
            return ml.getId();
        });
        committedMailingListIds.add(mailingListId);
        int triggerId = triggerIdHolder[0];

        // Precondition: the committed trigger has no next execution yet.
        assertNull(Tx.requiringNew()
                        .call(() -> em.find(MailingListTrigger.class, triggerId).getNextScheduledExecution()),
                "precondition: a freshly created trigger has no next scheduled execution");

        executionService.computeNextScheduledExecution(triggerId);

        java.util.Date next = Tx.requiringNew()
                .call(() -> em.find(MailingListTrigger.class, triggerId).getNextScheduledExecution());
        assertNotNull(next,
                "computeNextScheduledExecution must stamp a non-null next execution on a scheduled trigger");
    }

    // ------------------------------------------------------------------------------------------- //
    // postponeFailedScheduledTrigger(Integer) - REQUIRES_NEW, committed fixture (MAILLIST3, MAILLIST6)
    // ------------------------------------------------------------------------------------------- //

    /** Commits an active list with one daily SCHEDULED trigger at the given UTC time and returns the trigger id */
    private int commitDailyScheduledTrigger(String time) {
        int[] triggerIdHolder = new int[1];
        Integer mailingListId = Tx.requiringNew().call(() -> {
            MailingList ml = new MailingList();
            ml.setMailingListId("IT-MLX-pfst-" + System.nanoTime());
            ml.setActive(true);
            ml.createDesc("en").setName("Rep");

            MailingListTrigger trigger = new MailingListTrigger();
            trigger.setType(TriggerType.SCHEDULED);
            trigger.setScheduleType(ScheduleType.DAILY);
            trigger.setScheduledExecutionTime(time);
            trigger.setScheduledExecutionTimeZone("UTC");
            ml.addTrigger(trigger);

            em.persist(ml);
            em.flush();
            triggerIdHolder[0] = trigger.getId();
            return ml.getId();
        });
        committedMailingListIds.add(mailingListId);
        return triggerIdHolder[0];
    }

    private java.util.Date committedNextExecutionOf(int triggerId) {
        return Tx.requiringNew().call(() -> em.find(MailingListTrigger.class, triggerId).getNextScheduledExecution());
    }

    @Test
    void postponeFailedScheduledTriggerTakesTheRegularTimeWhenItComesWithinTheHour() {
        java.time.ZonedDateTime inHalfAnHour = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(30);
        int triggerId = commitDailyScheduledTrigger(
                inHalfAnHour.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

        java.util.Date next = executionService.postponeFailedScheduledTrigger(triggerId);

        assertEquals(inHalfAnHour.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toInstant(), next.toInstant(),
                "a regular time within the hour is kept, so a passing failure delays no mail");
        assertEquals(next, committedNextExecutionOf(triggerId), "and it is committed");
    }

    @Test
    void postponeFailedScheduledTriggerWaitsAnHourWhenTheRegularTimeIsLater() {
        int triggerId = commitDailyScheduledTrigger(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusHours(3)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

        long before = System.currentTimeMillis();
        java.util.Date next = executionService.postponeFailedScheduledTrigger(triggerId);
        long after = System.currentTimeMillis();

        long hour = 60L * 60L * 1000L;
        assertTrue(next.getTime() >= before + hour && next.getTime() <= after + hour,
                "the trigger is tried again in an hour: " + next.toInstant());
        assertEquals(next, committedNextExecutionOf(triggerId), "and it is committed");
    }

    @Test
    void postponeFailedScheduledTriggerWaitsAnHourWhenTheStoredTimeCannotBeRead() {
        int triggerId = commitDailyScheduledTrigger("12:00");
        Tx.requiringNew().run(() -> em.createQuery(
                        "update MailingListTrigger t set t.scheduledExecutionTime = '08:30:00' where t.id = :id")
                .setParameter("id", triggerId)
                .executeUpdate());

        long before = System.currentTimeMillis();
        java.util.Date next = executionService.postponeFailedScheduledTrigger(triggerId);
        long after = System.currentTimeMillis();

        long hour = 60L * 60L * 1000L;
        assertTrue(next.getTime() >= before + hour && next.getTime() <= after + hour,
                "the trigger is tried again in an hour: " + next.toInstant());
        assertEquals(next, committedNextExecutionOf(triggerId), "and it is committed");
    }

    @Test
    @Transactional
    void postponeFailedScheduledTriggerByUnknownIdAnswersNull() {
        assertNull(executionService.postponeFailedScheduledTrigger(Integer.MAX_VALUE));
    }

    @Test
    @Transactional
    void recordScheduledTriggerFailureByUnknownIdAnswersFalse() throws Exception {
        assertFalse(executionService.recordScheduledTriggerFailure(Integer.MAX_VALUE, new IllegalStateException("IT")));
    }

    // ------------------------------------------------------------------------------------------- //
    // getMailingListReports(lang)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getMailingListReportsIncludesPublicScheduledTriggers() {
        MailingList ml = persistActiveMailingList(); // name "Rep"
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        addDesc(trigger, "Report subj");
        trigger.setPublicReport(Boolean.TRUE);
        em.flush();

        List<MailingListReportVo> reports = executionService.getMailingListReports("en");

        MailingListReportVo mine = reports.stream()
                .filter(r -> r.getId().equals(trigger.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(mine, "a public scheduled trigger must appear among the mailing-list reports");
        assertEquals("Rep", mine.getName(),
                "the report name must come from the parent mailing list's en name");
    }

    @Test
    @Transactional
    void getMailingListReportsExcludesNonPublicScheduledTriggers() {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        addDesc(trigger, "Report subj");
        // publicReport not set.

        List<MailingListReportVo> reports = executionService.getMailingListReports("en");

        boolean present = reports.stream().anyMatch(r -> r.getId().equals(trigger.getId()));
        assertFalse(present,
                "a scheduled trigger without publicReport=true must NOT appear among the reports");
    }

    // ------------------------------------------------------------------------------------------- //
    // executeMailingListReport(triggerId, lang)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void executeMailingListReportRendersTemplateForPublicScheduledTrigger() throws Exception {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        addDesc(trigger, "Report subj");
        addItTemplate(trigger);
        trigger.setPublicReport(Boolean.TRUE);
        trigger.setMessageQuery("messageSeries=__none__&status=PUBLISHED");
        em.flush();

        String html = executionService.executeMailingListReport(trigger.getId(), "en");

        assertNotNull(html, "a public report must render to HTML");
        assertTrue(html.contains("IT-MAILINGLIST-TEMPLATE"),
                "the report HTML must be the rendered IT Freemarker template");
        assertTrue(html.contains("count=0"),
                "a query matching no messages must render an empty message list (count=0)");
    }

    @Test
    @Transactional
    void executeMailingListReportRejectsStatusChangeTrigger() throws Exception {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.STATUS_CHANGE);
        addDesc(trigger, "Report subj");
        addItTemplate(trigger);

        assertThrows(IllegalArgumentException.class,
                () -> executionService.executeMailingListReport(trigger.getId(), "en"),
                "a status-change trigger cannot be executed as a public report");
    }

    @Test
    @Transactional
    void executeMailingListReportRejectsNonPublicScheduledTrigger() throws Exception {
        MailingList ml = persistActiveMailingList();
        MailingListTrigger trigger = persistTrigger(ml, TriggerType.SCHEDULED);
        addDesc(trigger, "Report subj");
        addItTemplate(trigger);
        // publicReport not set.

        assertThrows(IllegalArgumentException.class,
                () -> executionService.executeMailingListReport(trigger.getId(), "en"),
                "a scheduled trigger without publicReport=true cannot be executed as a public report");
    }

    @Test
    @Transactional
    void executeMailingListReportRejectsUnknownTriggerId() {
        assertThrows(IllegalArgumentException.class,
                () -> executionService.executeMailingListReport(Integer.MAX_VALUE, "en"),
                "an unknown trigger id cannot be executed as a public report");
    }
}
