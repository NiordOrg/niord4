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

import org.junit.jupiter.api.Test;
import org.niord.core.mailinglist.vo.MailingListDescVo;
import org.niord.core.mailinglist.vo.MailingListTriggerVo;
import org.niord.core.mailinglist.vo.MailingListVo;
import org.niord.core.user.Contact;
import org.niord.core.user.User;
import org.niord.core.user.vo.ContactVo;
import org.niord.core.user.vo.UserVo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral plain JUnit 4 unit tests for the pure logic of the {@link MailingList} entity:
 * the {@code MailingList(MailingListVo)} value-object constructor, {@link MailingList#addTrigger},
 * {@link MailingList#checkComputeNextScheduledExecutions()} and the data-filter-driven
 * {@link MailingList#toVo(org.niord.model.DataFilter)} projection.
 *
 * <p>No DB, no Quarkus, no mocks — every assertion pins a concrete value, size or reference so the
 * tests survive a future Spring port.
 *
 * <p>Key non-obvious oracles being pinned:
 * <ul>
 *   <li>{@code active} is derived as {@code vo.getActive() != null && vo.getActive()}, so a
 *       {@code null} {@code Boolean} maps to {@code false}, not to a {@code NullPointerException}.</li>
 *   <li>Descriptions are only copied from the VO when {@link MailingListDescVo#descDefined()} is true
 *       (i.e. name or description is non-blank); blank descs are silently dropped.</li>
 *   <li>{@link MailingList#addTrigger} installs the back-reference so that
 *       {@code trigger.getMailingList() == list}.</li>
 *   <li>{@code checkComputeNextScheduledExecutions} only populates the package-private
 *       {@code nextScheduledExecution} field for SCHEDULED triggers; STATUS_CHANGE triggers keep it
 *       {@code null}. This test lives in the same package and reads that field directly.</li>
 *   <li>{@code LIST_DETAILS_FILTER} contains only {@code "MailingList.details"}, so toVo populates
 *       descs/triggers/recipientNo but leaves {@code users}/{@code contacts} as {@code null}.
 *       {@code LIST_DETAILS_AND_RECIPIENTS_FILTER} additionally carries the {@code users}/{@code contacts}
 *       fields, so those VO collections are populated.</li>
 * </ul>
 */
public class MailingListEntityTest {

    // --------------------------------------------------------------------------------------------- //
    // Builders
    // --------------------------------------------------------------------------------------------- //

    /** Builds a UserVo with the only two fields the recipient-copy assertions care about. */
    private UserVo userVo(String username, String email) {
        UserVo vo = new UserVo();
        vo.setUsername(username);
        vo.setEmail(email);
        return vo;
    }

    /** Builds a ContactVo with the only two fields the recipient-copy assertions care about. */
    private ContactVo contactVo(String email, String name) {
        ContactVo vo = new ContactVo();
        vo.setEmail(email);
        vo.setName(name);
        return vo;
    }

    /** Builds a defined English description VO. */
    private MailingListDescVo descVo(String name, String description) {
        MailingListDescVo vo = new MailingListDescVo();
        vo.setLang("en");
        vo.setName(name);
        vo.setDescription(description);
        return vo;
    }

    /** Builds a SCHEDULED, daily trigger VO firing at 15:55. */
    private MailingListTriggerVo scheduledTriggerVo() {
        MailingListTriggerVo vo = new MailingListTriggerVo();
        vo.setType(TriggerType.SCHEDULED);
        vo.setScheduleType(ScheduleType.DAILY);
        vo.setScheduledExecutionTime("15:55");
        return vo;
    }

    /** Builds a fully-populated mailing-list VO (one user, one contact, one desc, one trigger). */
    private MailingListVo fullVo(Boolean active) {
        MailingListVo vo = new MailingListVo();
        vo.setMailingListId("nw-daily");
        vo.setActive(active);

        List<UserVo> users = new ArrayList<>();
        users.add(userVo("jdoe", "jdoe@example.dk"));
        vo.setUsers(users);

        List<ContactVo> contacts = new ArrayList<>();
        contacts.add(contactVo("contact@example.dk", "External Contact"));
        vo.setContacts(contacts);

        List<MailingListDescVo> descs = new ArrayList<>();
        descs.add(descVo("Daily NW", "Daily navigational warnings"));
        vo.setDescs(descs);

        List<MailingListTriggerVo> triggers = new ArrayList<>();
        triggers.add(scheduledTriggerVo());
        vo.setTriggers(triggers);

        return vo;
    }

    /**
     * Builds a mailing list entity (via setters and entity objects, not the VO ctor) carrying:
     * one user, one contact, one defined English desc and one SCHEDULED trigger.
     */
    private MailingList entityWithRecipients() {
        MailingList list = new MailingList();
        list.setMailingListId("nw-daily");
        list.setActive(true);

        User user = new User();
        user.setUsername("jdoe");
        user.setEmail("jdoe@example.dk");
        list.getUsers().add(user);

        Contact contact = new Contact();
        contact.setEmail("contact@example.dk");
        contact.setName("External Contact");
        list.getContacts().add(contact);

        list.createDesc("en").setName("Daily NW");

        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setScheduleType(ScheduleType.DAILY);
        trigger.setScheduledExecutionTime("15:55");
        list.addTrigger(trigger);

        return list;
    }

    // --------------------------------------------------------------------------------------------- //
    // MailingList(MailingListVo) constructor
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructorCopiesIdAndCollectionsFromVo() {
        MailingList list = new MailingList(fullVo(Boolean.TRUE));

        assertEquals("nw-daily", list.getMailingListId());
        assertEquals(1, list.getUsers().size(), "one user copied");
        assertEquals("jdoe", list.getUsers().get(0).getUsername());
        assertEquals("jdoe@example.dk", list.getUsers().get(0).getEmail());
        assertEquals(1, list.getContacts().size(), "one contact copied");
        assertEquals("contact@example.dk", list.getContacts().get(0).getEmail());
        assertEquals("External Contact", list.getContacts().get(0).getName());
        assertEquals(1, list.getDescs().size(), "one defined desc copied");
        assertEquals("Daily NW", list.getDescs().get(0).getName());
        assertEquals("Daily navigational warnings", list.getDescs().get(0).getDescription());
        assertEquals(1, list.getTriggers().size(), "one trigger copied");
    }

    @Test
    public void constructorWithActiveTrueIsActive() {
        MailingList list = new MailingList(fullVo(Boolean.TRUE));

        assertTrue(list.isActive(), "active Boolean.TRUE -> isActive() true");
    }

    @Test
    public void constructorWithActiveNullIsNotActive() {
        // active == null must NOT throw and must resolve to false (vo.getActive() != null && ...).
        MailingList list = new MailingList(fullVo(null));

        assertFalse(list.isActive(), "active null -> isActive() false");
    }

    @Test
    public void constructorWithActiveFalseIsNotActive() {
        MailingList list = new MailingList(fullVo(Boolean.FALSE));

        assertFalse(list.isActive(), "active Boolean.FALSE -> isActive() false");
    }

    @Test
    public void constructorSetsBackReferenceOnCopiedTriggers() {
        MailingList list = new MailingList(fullVo(Boolean.TRUE));

        // addTrigger installs the back-reference, so the copied trigger points at the new list.
        assertSame(list, list.getTriggers().get(0).getMailingList(),
                "trigger.getMailingList() must be the owning list");
    }

    @Test
    public void constructorDropsBlankDescriptions() {
        MailingListVo vo = fullVo(Boolean.TRUE);
        // A desc whose name and description are both blank is not "descDefined" and must be dropped.
        vo.getDescs().add(descVo("", ""));

        MailingList list = new MailingList(vo);

        assertEquals(1, list.getDescs().size(), "only the defined desc survives");
        assertEquals("Daily NW", list.getDescs().get(0).getName());
    }

    // --------------------------------------------------------------------------------------------- //
    // addTrigger
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void addTriggerInstallsBackReferenceAndAppends() {
        MailingList list = new MailingList();
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.STATUS_CHANGE);

        MailingListTrigger returned = list.addTrigger(trigger);

        assertSame(trigger, returned, "addTrigger returns the same trigger instance");
        assertSame(list, trigger.getMailingList(), "back-reference points at the owning list");
        assertEquals(1, list.getTriggers().size(), "trigger appended to the list");
        assertSame(trigger, list.getTriggers().get(0));
    }

    // --------------------------------------------------------------------------------------------- //
    // checkComputeNextScheduledExecutions
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void checkComputeNextScheduledExecutionsSetsTimeForScheduledTrigger() {
        MailingList list = new MailingList();
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setScheduleType(ScheduleType.DAILY);
        trigger.setScheduledExecutionTime("15:55");
        list.addTrigger(trigger);

        // Reading the package-private field directly — this test lives in the same package.
        assertNull(trigger.nextScheduledExecution, "not computed before the call");

        list.checkComputeNextScheduledExecutions();

        assertNotNull(trigger.nextScheduledExecution,
                "scheduled trigger gets a next execution time");
    }

    @Test
    public void checkComputeNextScheduledExecutionsLeavesStatusChangeTriggerNull() {
        MailingList list = new MailingList();
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.STATUS_CHANGE);
        list.addTrigger(trigger);

        list.checkComputeNextScheduledExecutions();

        assertNull(trigger.nextScheduledExecution,
                "STATUS_CHANGE trigger keeps a null next execution time");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(LIST_DETAILS_FILTER) — details only, no recipients
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoWithDetailsFilterPopulatesDescsTriggersAndRecipientNo() {
        MailingList list = entityWithRecipients();

        MailingListVo vo = list.toVo(MailingList.LIST_DETAILS_FILTER);

        assertEquals("nw-daily", vo.getMailingListId());
        assertTrue(vo.getActive(), "active flag copied");
        assertNotNull(vo.getDescs(), "descs populated by the details filter");
        assertEquals(1, vo.getDescs().size());
        assertEquals("Daily NW", vo.getDescs().get(0).getName());
        assertNotNull(vo.getTriggers(), "triggers populated when non-empty");
        assertEquals(1, vo.getTriggers().size());
        // recipientNo is users.size() + contacts.size() = 1 + 1.
        assertEquals(Integer.valueOf(2), vo.getRecipientNo(),
                "recipientNo counts both users and contacts");
    }

    @Test
    public void toVoWithDetailsFilterLeavesUsersAndContactsNull() {
        MailingList list = entityWithRecipients();

        MailingListVo vo = list.toVo(MailingList.LIST_DETAILS_FILTER);

        // The "users"/"contacts" fields are NOT part of LIST_DETAILS_FILTER, so they are not copied,
        // even though the recipientNo proves the entity actually has recipients.
        assertNull(vo.getUsers(), "users not included by the details-only filter");
        assertNull(vo.getContacts(), "contacts not included by the details-only filter");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(LIST_DETAILS_AND_RECIPIENTS_FILTER) — details plus recipients
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoWithRecipientsFilterPopulatesUsersAndContacts() {
        MailingList list = entityWithRecipients();

        MailingListVo vo = list.toVo(MailingList.LIST_DETAILS_AND_RECIPIENTS_FILTER);

        // Details are still present...
        assertNotNull(vo.getDescs(), "descs still populated");
        assertEquals(1, vo.getDescs().size());
        assertEquals(Integer.valueOf(2), vo.getRecipientNo(), "recipientNo still counts both");

        // ...and now the recipient collections are populated too.
        assertNotNull(vo.getUsers(), "users populated by the recipients filter");
        assertEquals(1, vo.getUsers().size());
        assertEquals("jdoe", vo.getUsers().get(0).getUsername());
        assertEquals("jdoe@example.dk", vo.getUsers().get(0).getEmail());

        assertNotNull(vo.getContacts(), "contacts populated by the recipients filter");
        assertEquals(1, vo.getContacts().size());
        assertEquals("contact@example.dk", vo.getContacts().get(0).getEmail());
        assertEquals("External Contact", vo.getContacts().get(0).getName());
    }
}
