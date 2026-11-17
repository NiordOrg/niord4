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
package org.niord.core.mailinglist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mailinglist.vo.MailingListDescVo;
import org.niord.core.mailinglist.vo.MailingListTriggerDescVo;
import org.niord.core.mailinglist.vo.MailingListTriggerVo;
import org.niord.core.mailinglist.vo.MailingListVo;
import org.niord.core.user.Contact;
import org.niord.core.user.ContactMapper;
import org.niord.core.user.User;
import org.niord.core.user.UserMapper;
import org.niord.model.DataFilter;
import org.niord.model.message.Status;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MailingListMapper}. Pins the field projection / {@link DataFilter} semantics
 * that were previously embedded in {@code MailingList.toVo(DataFilter)} and the conversion logic of the
 * {@code MailingList(MailingListVo)} / {@code MailingListTrigger(MailingListTriggerVo)} constructors,
 * proving the extraction is behaviour-preserving.
 *
 * <p>Uses a DIFFERENTIAL strategy where the entity methods still exist: it asserts the mapper output
 * matches the entity's own {@code toVo}, and the round-tripped entity matches the entity built by the
 * conversion constructor, field-by-field across the nested graph. Runs as a plain unit test with no
 * persistence context.
 */
public class MailingListMapperTest {

    private MailingListMapper mapper;

    @BeforeEach
    public void setUp() {
        // Constructor injection: pass the real (stateless) mappers directly.
        mapper = new MailingListMapper(new UserMapper(), new ContactMapper());
    }

    /** Detail filter: includes descs, triggers, users, contacts. */
    private static DataFilter detailsAndRecipients() {
        return DataFilter.get().fields("MailingList.details", "MailingList.users", "MailingList.contacts");
    }

    private static User user(String username, String email, String first, String last, String lang) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setFirstName(first);
        u.setLastName(last);
        u.setLanguage(lang);
        return u;
    }

    private static Contact contact(Integer id, String email, String name, String lang) {
        Contact c = new Contact();
        c.setId(id);
        c.setEmail(email);
        c.setName(name);
        c.setLanguage(lang);
        return c;
    }

    /** Builds a representative mailing list with a scheduled + a status-change trigger and recipients. */
    private static MailingList mailingList() {
        MailingList ml = new MailingList();
        ml.setMailingListId("ml-1");
        ml.setActive(true);

        ml.getUsers().add(user("alice", "alice@example.com", "Alice", "A", "en"));
        ml.getContacts().add(contact(7, "bob@example.com", "Bob", "da"));

        ml.createDesc("en").copyDesc(descEntity("en", "English name", "English description"));
        ml.createDesc("da").copyDesc(descEntity("da", "Dansk navn", "Dansk beskrivelse"));

        // Scheduled trigger
        MailingListTrigger scheduled = new MailingListTrigger();
        scheduled.setType(TriggerType.SCHEDULED);
        scheduled.setScheduleType(ScheduleType.DAILY);
        scheduled.setScheduledExecutionTime("08:00");
        scheduled.setScheduledExecutionTimeZone("Europe/Copenhagen");
        scheduled.setMessageQuery("status=PUBLISHED");
        scheduled.getScriptResourcePaths().add("a/template.ftl");
        scheduled.getScriptResourcePaths().add("b/script.js");
        scheduled.setPublicReport(Boolean.TRUE);
        scheduled.createDesc("en").copyDesc(triggerDescEntity("en", "Scheduled subject"));
        ml.addTrigger(scheduled);

        // Status-change trigger
        MailingListTrigger statusChange = new MailingListTrigger();
        statusChange.setType(TriggerType.STATUS_CHANGE);
        statusChange.setStatusChanges(new HashSet<>(Arrays.asList(Status.PUBLISHED, Status.CANCELLED)));
        statusChange.setMessageFilter("msg.promulgation('navtex').promulgate");
        statusChange.createDesc("da").copyDesc(triggerDescEntity("da", "Status emne"));
        ml.addTrigger(statusChange);

        return ml;
    }

    private static MailingListDesc descEntity(String lang, String name, String description) {
        MailingListDesc d = new MailingListDesc();
        d.setLang(lang);
        d.setName(name);
        d.setDescription(description);
        return d;
    }

    private static MailingListTriggerDesc triggerDescEntity(String lang, String subject) {
        MailingListTriggerDesc d = new MailingListTriggerDesc();
        d.setLang(lang);
        d.setSubject(subject);
        return d;
    }

    // ------------------------------------------------------------------------------------------------
    //  toVo - differential vs the entity's own toVo
    // ------------------------------------------------------------------------------------------------

    /** With the full detail+recipients filter, the mapper matches the entity's toVo across the graph. */
    @Test
    public void toVo_detailsAndRecipients_matchesEntityToVo() {
        MailingList ml = mailingList();
        DataFilter filter = detailsAndRecipients();

        MailingListVo expected = ml.toVo(filter);
        MailingListVo actual = mapper.toVo(ml, filter);

        assertMailingListVoEquals(expected, actual);

        // And spot-check the concrete values so the assertions are not vacuous if both sides were null.
        assertEquals("ml-1", actual.getMailingListId());
        assertEquals(Boolean.TRUE, actual.getActive());
        assertEquals(Integer.valueOf(2), actual.getRecipientNo());
        assertEquals(2, actual.getDescs().size());
        assertEquals(2, actual.getTriggers().size());
        assertEquals(1, actual.getUsers().size());
        assertEquals("alice@example.com", actual.getUsers().get(0).getEmail());
        assertEquals(1, actual.getContacts().size());
        assertEquals("bob@example.com", actual.getContacts().get(0).getEmail());
    }

    /** The default filter (no DETAILS) omits descs, triggers, recipientNo, users, contacts. */
    @Test
    public void toVo_defaultFilter_matchesEntityToVoAndOmitsDetails() {
        MailingList ml = mailingList();
        DataFilter filter = DataFilter.get();

        MailingListVo expected = ml.toVo(filter);
        MailingListVo actual = mapper.toVo(ml, filter);

        assertMailingListVoEquals(expected, actual);

        assertEquals("ml-1", actual.getMailingListId());
        assertEquals(Boolean.TRUE, actual.getActive());
        assertNull(actual.getDescs(), "DETAILS off => descs omitted");
        assertNull(actual.getTriggers(), "DETAILS off => triggers omitted");
        assertNull(actual.getRecipientNo(), "recipientNo only set under DETAILS");
        assertNull(actual.getUsers(), "users only set when the field is requested");
        assertNull(actual.getContacts(), "contacts only set when the field is requested");
    }

    /** Details on, but the users/contacts fields not requested: recipientNo is set, but lists are not. */
    @Test
    public void toVo_detailsOnly_setsRecipientNoButOmitsUserContactLists() {
        MailingList ml = mailingList();
        DataFilter filter = DataFilter.get().fields("MailingList.details");

        MailingListVo expected = ml.toVo(filter);
        MailingListVo actual = mapper.toVo(ml, filter);

        assertMailingListVoEquals(expected, actual);

        assertEquals(Integer.valueOf(2), actual.getRecipientNo());
        assertNull(actual.getUsers(), "users field not requested");
        assertNull(actual.getContacts(), "contacts field not requested");
        assertEquals(2, actual.getTriggers().size());
    }

    /** A null entity maps to a null VO. */
    @Test
    public void toVo_nullEntity_isNull() {
        assertNull(mapper.toVo(null, DataFilter.get()));
    }

    // ------------------------------------------------------------------------------------------------
    //  toEntity - differential vs the entity's conversion constructor
    // ------------------------------------------------------------------------------------------------

    /** The mapper-built entity matches the entity built by the {@code MailingList(MailingListVo)} ctor. */
    @Test
    public void toEntity_matchesEntityConversionConstructor() {
        // Build a VO via the entity's own (verified) toVo so the input is realistic.
        MailingListVo vo = mailingList().toVo(detailsAndRecipients());

        MailingList expected = new MailingList(vo);
        MailingList actual = mapper.toEntity(vo);

        assertMailingListEntityEquals(expected, actual);

        // Spot-check concrete values.
        assertEquals("ml-1", actual.getMailingListId());
        assertTrue(actual.isActive());
        assertEquals(1, actual.getUsers().size());
        assertEquals("alice@example.com", actual.getUsers().get(0).getEmail());
        assertEquals(1, actual.getContacts().size());
        assertEquals(Integer.valueOf(7), actual.getContacts().get(0).getId());
        assertEquals(2, actual.getDescs().size());
        assertEquals(2, actual.getTriggers().size());
        // addTrigger back-links each trigger to the owning mailing list.
        assertEquals(actual, actual.getTriggers().get(0).getMailingList());
    }

    /** SCHEDULED trigger: status changes are cleared and messageFilter nulled by the reset logic. */
    @Test
    public void toEntity_scheduledTrigger_clearsStatusFieldsLikeConstructor() {
        MailingListTriggerVo t = new MailingListTriggerVo();
        t.setType(TriggerType.SCHEDULED);
        t.setScheduleType(ScheduleType.DAILY);
        t.setScheduledExecutionTime("08:00");
        t.setScheduledExecutionTimeZone("Europe/Copenhagen");
        t.setMessageQuery("status=PUBLISHED");
        // These are invalid for a SCHEDULED trigger and must be reset:
        t.setStatusChanges(new HashSet<>(Arrays.asList(Status.PUBLISHED)));
        t.setMessageFilter("should be cleared");
        t.setScriptResourcePaths(Arrays.asList("a/template.ftl", "bogus.txt", "b/script.js"));

        MailingListVo vo = new MailingListVo();
        vo.setMailingListId("ml-sched");
        vo.setTriggers(Arrays.asList(t));

        MailingList expected = new MailingList(vo);
        MailingList actual = mapper.toEntity(vo);

        MailingListTrigger expectedTrigger = expected.getTriggers().get(0);
        MailingListTrigger actualTrigger = actual.getTriggers().get(0);

        // Differential: matches the constructor's reset behaviour.
        assertTriggerEntityEquals(expectedTrigger, actualTrigger);

        // Concrete: SCHEDULED reset clears statusChanges + messageFilter, keeps schedule fields.
        assertTrue(actualTrigger.getStatusChanges().isEmpty(), "statusChanges cleared for SCHEDULED");
        assertNull(actualTrigger.getMessageFilter(), "messageFilter cleared for SCHEDULED");
        assertEquals(ScheduleType.DAILY, actualTrigger.getScheduleType());
        assertEquals("08:00", actualTrigger.getScheduledExecutionTime());
        assertEquals("status=PUBLISHED", actualTrigger.getMessageQuery());
        // path2type filtering: only .ftl + .js survive, "bogus.txt" is dropped.
        assertEquals(Arrays.asList("a/template.ftl", "b/script.js"), actualTrigger.getScriptResourcePaths());
    }

    /** STATUS_CHANGE trigger: schedule-only fields + messageQuery are nulled by the reset logic. */
    @Test
    public void toEntity_statusChangeTrigger_clearsScheduleFieldsLikeConstructor() {
        MailingListTriggerVo t = new MailingListTriggerVo();
        t.setType(TriggerType.STATUS_CHANGE);
        t.setStatusChanges(new HashSet<>(Arrays.asList(Status.PUBLISHED, Status.CANCELLED)));
        t.setMessageFilter("keep me");
        // These are invalid for a STATUS_CHANGE trigger and must be reset:
        t.setScheduleType(ScheduleType.DAILY);
        t.setScheduledExecutionTime("08:00");
        t.setScheduledExecutionTimeZone("Europe/Copenhagen");
        t.setMessageQuery("should be cleared");

        MailingListVo vo = new MailingListVo();
        vo.setMailingListId("ml-status");
        vo.setTriggers(Arrays.asList(t));

        MailingList expected = new MailingList(vo);
        MailingList actual = mapper.toEntity(vo);

        MailingListTrigger expectedTrigger = expected.getTriggers().get(0);
        MailingListTrigger actualTrigger = actual.getTriggers().get(0);

        assertTriggerEntityEquals(expectedTrigger, actualTrigger);

        // Concrete: STATUS_CHANGE reset nulls schedule fields + messageQuery, keeps statusChanges + filter.
        assertNull(actualTrigger.getScheduleType());
        assertNull(actualTrigger.getScheduledExecutionTime());
        assertNull(actualTrigger.getScheduledExecutionTimeZone());
        assertNull(actualTrigger.getMessageQuery(), "messageQuery cleared for STATUS_CHANGE");
        assertEquals("keep me", actualTrigger.getMessageFilter());
        assertEquals(new HashSet<>(Arrays.asList(Status.PUBLISHED, Status.CANCELLED)),
                actualTrigger.getStatusChanges());
    }

    /** A null VO into toEntity yields null. */
    @Test
    public void toEntity_nullVo_isNull() {
        assertNull(mapper.toEntity(null));
    }

    /** active is false when the VO's Boolean active is null (mirrors the constructor's null guard). */
    @Test
    public void toEntity_nullActive_isFalse() {
        MailingListVo vo = new MailingListVo();
        vo.setMailingListId("ml-x");
        vo.setActive(null);

        assertTrue(!new MailingList(vo).isActive(), "constructor maps null active -> false");
        assertTrue(!mapper.toEntity(vo).isActive(), "mapper maps null active -> false");
    }

    // ------------------------------------------------------------------------------------------------
    //  Differential assertion helpers
    // ------------------------------------------------------------------------------------------------

    private static void assertMailingListVoEquals(MailingListVo expected, MailingListVo actual) {
        assertEquals(expected.getMailingListId(), actual.getMailingListId());
        assertEquals(expected.getActive(), actual.getActive());
        assertEquals(expected.getRecipientNo(), actual.getRecipientNo());

        assertListSizeEquals(expected.getDescs(), actual.getDescs());
        if (expected.getDescs() != null) {
            for (int i = 0; i < expected.getDescs().size(); i++) {
                MailingListDescVo e = expected.getDescs().get(i);
                MailingListDescVo a = actual.getDescs().get(i);
                assertEquals(e.getLang(), a.getLang());
                assertEquals(e.getName(), a.getName());
                assertEquals(e.getDescription(), a.getDescription());
            }
        }

        assertListSizeEquals(expected.getUsers(), actual.getUsers());
        if (expected.getUsers() != null) {
            for (int i = 0; i < expected.getUsers().size(); i++) {
                assertEquals(expected.getUsers().get(i).getUsername(), actual.getUsers().get(i).getUsername());
                assertEquals(expected.getUsers().get(i).getEmail(), actual.getUsers().get(i).getEmail());
                assertEquals(expected.getUsers().get(i).getFirstName(), actual.getUsers().get(i).getFirstName());
                assertEquals(expected.getUsers().get(i).getLastName(), actual.getUsers().get(i).getLastName());
                assertEquals(expected.getUsers().get(i).getLanguage(), actual.getUsers().get(i).getLanguage());
            }
        }

        assertListSizeEquals(expected.getContacts(), actual.getContacts());
        if (expected.getContacts() != null) {
            for (int i = 0; i < expected.getContacts().size(); i++) {
                assertEquals(expected.getContacts().get(i).getId(), actual.getContacts().get(i).getId());
                assertEquals(expected.getContacts().get(i).getEmail(), actual.getContacts().get(i).getEmail());
                assertEquals(expected.getContacts().get(i).getName(), actual.getContacts().get(i).getName());
                assertEquals(expected.getContacts().get(i).getLanguage(), actual.getContacts().get(i).getLanguage());
            }
        }

        assertListSizeEquals(expected.getTriggers(), actual.getTriggers());
        if (expected.getTriggers() != null) {
            for (int i = 0; i < expected.getTriggers().size(); i++) {
                assertTriggerVoEquals(expected.getTriggers().get(i), actual.getTriggers().get(i));
            }
        }
    }

    private static void assertTriggerVoEquals(MailingListTriggerVo expected, MailingListTriggerVo actual) {
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getScheduleType(), actual.getScheduleType());
        assertEquals(expected.getScheduledExecutionTime(), actual.getScheduledExecutionTime());
        assertEquals(expected.getScheduledExecutionTimeZone(), actual.getScheduledExecutionTimeZone());
        assertEquals(expected.getStatusChanges(), actual.getStatusChanges());
        assertEquals(expected.getMessageQuery(), actual.getMessageQuery());
        assertEquals(expected.getMessageFilter(), actual.getMessageFilter());
        assertEquals(expected.getScriptResourcePaths(), actual.getScriptResourcePaths());
        assertEquals(expected.getPublicReport(), actual.getPublicReport());

        assertListSizeEquals(expected.getDescs(), actual.getDescs());
        if (expected.getDescs() != null) {
            for (int i = 0; i < expected.getDescs().size(); i++) {
                MailingListTriggerDescVo e = expected.getDescs().get(i);
                MailingListTriggerDescVo a = actual.getDescs().get(i);
                assertEquals(e.getLang(), a.getLang());
                assertEquals(e.getSubject(), a.getSubject());
            }
        }
    }

    private static void assertMailingListEntityEquals(MailingList expected, MailingList actual) {
        assertEquals(expected.getMailingListId(), actual.getMailingListId());
        assertEquals(expected.isActive(), actual.isActive());

        assertEquals(expected.getUsers().size(), actual.getUsers().size());
        for (int i = 0; i < expected.getUsers().size(); i++) {
            assertEquals(expected.getUsers().get(i).getUsername(), actual.getUsers().get(i).getUsername());
            assertEquals(expected.getUsers().get(i).getEmail(), actual.getUsers().get(i).getEmail());
            assertEquals(expected.getUsers().get(i).getLanguage(), actual.getUsers().get(i).getLanguage());
        }

        assertEquals(expected.getContacts().size(), actual.getContacts().size());
        for (int i = 0; i < expected.getContacts().size(); i++) {
            assertEquals(expected.getContacts().get(i).getId(), actual.getContacts().get(i).getId());
            assertEquals(expected.getContacts().get(i).getEmail(), actual.getContacts().get(i).getEmail());
            assertEquals(expected.getContacts().get(i).getName(), actual.getContacts().get(i).getName());
        }

        assertEquals(expected.getDescs().size(), actual.getDescs().size());
        for (int i = 0; i < expected.getDescs().size(); i++) {
            assertEquals(expected.getDescs().get(i).getLang(), actual.getDescs().get(i).getLang());
            assertEquals(expected.getDescs().get(i).getName(), actual.getDescs().get(i).getName());
            assertEquals(expected.getDescs().get(i).getDescription(), actual.getDescs().get(i).getDescription());
        }

        assertEquals(expected.getTriggers().size(), actual.getTriggers().size());
        for (int i = 0; i < expected.getTriggers().size(); i++) {
            assertTriggerEntityEquals(expected.getTriggers().get(i), actual.getTriggers().get(i));
        }
    }

    private static void assertTriggerEntityEquals(MailingListTrigger expected, MailingListTrigger actual) {
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getScheduleType(), actual.getScheduleType());
        assertEquals(expected.getScheduledExecutionTime(), actual.getScheduledExecutionTime());
        assertEquals(expected.getScheduledExecutionTimeZone(), actual.getScheduledExecutionTimeZone());
        assertEquals(expected.getStatusChanges(), actual.getStatusChanges());
        assertEquals(expected.getMessageQuery(), actual.getMessageQuery());
        assertEquals(expected.getMessageFilter(), actual.getMessageFilter());
        assertEquals(expected.getScriptResourcePaths(), actual.getScriptResourcePaths());
        assertEquals(expected.getPublicReport(), actual.getPublicReport());

        assertEquals(expected.getDescs().size(), actual.getDescs().size());
        for (int i = 0; i < expected.getDescs().size(); i++) {
            assertEquals(expected.getDescs().get(i).getLang(), actual.getDescs().get(i).getLang());
            assertEquals(expected.getDescs().get(i).getSubject(), actual.getDescs().get(i).getSubject());
        }
    }

    private static void assertListSizeEquals(List<?> expected, List<?> actual) {
        if (expected == null) {
            assertNull(actual);
        } else {
            assertEquals(expected.size(), actual.size());
        }
    }
}
