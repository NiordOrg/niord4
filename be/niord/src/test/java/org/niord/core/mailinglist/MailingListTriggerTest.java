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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mailinglist.vo.MailingListTriggerDescVo;
import org.niord.core.mailinglist.vo.MailingListTriggerVo;
import org.niord.model.DataFilter;
import org.niord.model.message.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the framework-neutral mapping and scheduling logic of {@link MailingListTrigger}.
 *
 * <p>These tests exercise plain Java behavior only (no Quarkus, no persistence) and so
 * should survive a future Quarkus-to-Spring port unchanged. The test lives in the same
 * package as the entity so it can read package-private fields directly. The one field read
 * directly is {@code nextScheduledExecution}, because {@link MailingListTrigger} has no
 * getter for it.
 *
 * <p>Behavior pinned:
 * <ul>
 *   <li><b>Constructor field-reset rules.</b> The {@code MailingListTrigger(vo)} constructor
 *       copies all fields first, then nulls/clears the fields that are not valid for the
 *       trigger type. A {@code STATUS_CHANGE} trigger discards the schedule fields
 *       (scheduleType/time/zone) and {@code messageQuery} but keeps {@code statusChanges}
 *       and {@code messageFilter}. A {@code SCHEDULED} trigger clears {@code statusChanges}
 *       and nulls {@code messageFilter} but keeps the schedule fields and {@code messageQuery}.</li>
 *   <li><b>scriptResourcePaths filtering.</b> Only paths that {@code ScriptResource.path2type}
 *       recognizes ({@code .ftl} -> FM, {@code .js} -> JS) survive; unrecognized extensions are
 *       dropped while original order is preserved.</li>
 *   <li><b>descs filtering.</b> Only descs whose {@code descDefined()} is true (non-blank
 *       subject) are copied; the subject value round-trips.</li>
 *   <li><b>toVo round trip.</b> Empty collections become {@code null} on the VO (statusChanges
 *       and scriptResourcePaths), otherwise the values are copied through.</li>
 *   <li><b>checkComputeNextScheduledExecution.</b> The next-execution Date is only computed for
 *       a SCHEDULED trigger that has both a scheduleType and a non-blank time; otherwise the
 *       field is left null.</li>
 *   <li><b>createDesc.</b> Produces a desc with the given language, an entity back-reference
 *       to the trigger, and adds it to the desc list.</li>
 * </ul>
 */
public class MailingListTriggerTest {

    private MailingListTriggerVo vo;

    @BeforeEach
    public void setUp() {
        vo = new MailingListTriggerVo();
    }

    // -------------------------------------------------------------------------
    // Constructor field-reset rules
    // -------------------------------------------------------------------------

    @Test
    public void statusChangeConstructorResetsScheduleFieldsButKeepsStatusChangesAndFilter() {
        vo.setType(TriggerType.STATUS_CHANGE);
        // These schedule-only fields must be reset for a STATUS_CHANGE trigger.
        vo.setScheduleType(ScheduleType.DAILY);
        vo.setScheduledExecutionTime("15:55");
        vo.setScheduledExecutionTimeZone("Europe/Copenhagen");
        vo.setMessageQuery("status=PUBLISHED");
        // These status-change fields must be preserved.
        vo.setStatusChanges(new HashSet<>(Arrays.asList(Status.PUBLISHED, Status.CANCELLED)));
        vo.setMessageFilter("msg.promulgation('navtex').promulgate");

        MailingListTrigger trigger = new MailingListTrigger(vo);

        assertNull(trigger.getScheduleType(), "scheduleType must be reset for STATUS_CHANGE");
        assertNull(trigger.getScheduledExecutionTime(), "scheduledExecutionTime must be reset for STATUS_CHANGE");
        assertNull(trigger.getScheduledExecutionTimeZone(), "scheduledExecutionTimeZone must be reset for STATUS_CHANGE");
        assertNull(trigger.getMessageQuery(), "messageQuery must be reset for STATUS_CHANGE");

        assertEquals(new HashSet<>(Arrays.asList(Status.PUBLISHED, Status.CANCELLED)),
                trigger.getStatusChanges(),
                "statusChanges must be preserved for STATUS_CHANGE");
        assertEquals("msg.promulgation('navtex').promulgate", trigger.getMessageFilter(),
                "messageFilter must be preserved for STATUS_CHANGE");
        assertEquals(TriggerType.STATUS_CHANGE, trigger.getType());
    }

    @Test
    public void scheduledConstructorClearsStatusChangesAndFilterButKeepsScheduleFields() {
        vo.setType(TriggerType.SCHEDULED);
        vo.setScheduleType(ScheduleType.MONDAY);
        vo.setScheduledExecutionTime("08:30");
        vo.setScheduledExecutionTimeZone("UTC");
        vo.setMessageQuery("status=PUBLISHED&type=COASTAL_WARNING");
        // These status-change-only fields must be cleared/nulled for a SCHEDULED trigger.
        vo.setStatusChanges(new HashSet<>(Arrays.asList(Status.PUBLISHED)));
        vo.setMessageFilter("some.filter");

        MailingListTrigger trigger = new MailingListTrigger(vo);

        assertTrue(trigger.getStatusChanges().isEmpty(),
                "statusChanges must be cleared (empty) for SCHEDULED");
        assertNull(trigger.getMessageFilter(), "messageFilter must be nulled for SCHEDULED");

        assertEquals(ScheduleType.MONDAY, trigger.getScheduleType());
        assertEquals("08:30", trigger.getScheduledExecutionTime());
        assertEquals("UTC", trigger.getScheduledExecutionTimeZone());
        assertEquals("status=PUBLISHED&type=COASTAL_WARNING", trigger.getMessageQuery());
        assertEquals(TriggerType.SCHEDULED, trigger.getType());
    }

    @Test
    public void constructorCopiesPublicReportFlag() {
        vo.setType(TriggerType.SCHEDULED);
        vo.setScheduleType(ScheduleType.DAILY);
        vo.setScheduledExecutionTime("06:00");
        vo.setPublicReport(Boolean.TRUE);

        MailingListTrigger trigger = new MailingListTrigger(vo);

        assertEquals(Boolean.TRUE, trigger.getPublicReport());
    }

    @Test
    public void constructorLeavesStatusChangesEmptyNotNullWhenVoHasNone() {
        // For a SCHEDULED trigger, statusChanges from the VO is null; the entity must still
        // expose a non-null (empty) set, never null.
        vo.setType(TriggerType.SCHEDULED);
        vo.setScheduleType(ScheduleType.DAILY);
        vo.setScheduledExecutionTime("12:00");

        MailingListTrigger trigger = new MailingListTrigger(vo);

        assertTrue(trigger.getStatusChanges().isEmpty(), "statusChanges should be empty");
    }

    // -------------------------------------------------------------------------
    // scriptResourcePaths filtering in the constructor
    // -------------------------------------------------------------------------

    @Test
    public void constructorKeepsOnlyRecognizedScriptResourcePathsPreservingOrder() {
        vo.setType(TriggerType.SCHEDULED);
        vo.setScheduleType(ScheduleType.DAILY);
        vo.setScheduledExecutionTime("10:00");
        // "bad.txt" is not a recognized script resource extension and must be dropped.
        vo.setScriptResourcePaths(new ArrayList<>(Arrays.asList("x.ftl", "y.js", "bad.txt")));

        MailingListTrigger trigger = new MailingListTrigger(vo);

        assertEquals(Arrays.asList("x.ftl", "y.js"), trigger.getScriptResourcePaths(),
                "only .ftl and .js paths survive, in original order");
    }

    @Test
    public void constructorDropsAllScriptResourcePathsWhenNoneRecognized() {
        vo.setType(TriggerType.SCHEDULED);
        vo.setScheduleType(ScheduleType.DAILY);
        vo.setScheduledExecutionTime("10:00");
        vo.setScriptResourcePaths(new ArrayList<>(Arrays.asList("a.txt", "b.xml")));

        MailingListTrigger trigger = new MailingListTrigger(vo);

        assertTrue(trigger.getScriptResourcePaths().isEmpty(), "no recognized extensions -> empty list");
    }

    // -------------------------------------------------------------------------
    // descs filtering in the constructor
    // -------------------------------------------------------------------------

    @Test
    public void constructorCopiesOnlyDefinedDescsAndPreservesSubject() {
        vo.setType(TriggerType.SCHEDULED);
        vo.setScheduleType(ScheduleType.DAILY);
        vo.setScheduledExecutionTime("10:00");

        MailingListTriggerDescVo defined = new MailingListTriggerDescVo();
        defined.setLang("en");
        defined.setSubject("Daily report");
        assertTrue(defined.descDefined(), "precondition: defined desc");

        MailingListTriggerDescVo blank = new MailingListTriggerDescVo();
        blank.setLang("da");
        blank.setSubject("   ");
        // Note: descDefined() uses fieldsDefined which is length-based; a blank-but-nonempty
        // string is considered "defined" by fieldsDefined. So use an empty subject for the
        // undefined case below.
        blank.setSubject("");
        assertFalse(blank.descDefined(), "precondition: undefined desc (empty subject)");

        vo.setDescs(new ArrayList<>(Arrays.asList(defined, blank)));

        MailingListTrigger trigger = new MailingListTrigger(vo);

        assertEquals(1, trigger.getDescs().size(), "only the defined desc is copied");
        MailingListTriggerDesc copied = trigger.getDescs().get(0);
        assertEquals("en", copied.getLang());
        assertEquals("Daily report", copied.getSubject());
        assertSame(trigger, copied.getEntity(), "copied desc back-references the trigger");
    }

    // -------------------------------------------------------------------------
    // toVo(DataFilter) round trip
    // -------------------------------------------------------------------------

    @Test
    public void toVoCopiesScalarFieldsAndDescSubject() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setScheduleType(ScheduleType.TUESDAY);
        trigger.setScheduledExecutionTime("23:15");
        trigger.setScheduledExecutionTimeZone("Europe/Copenhagen");
        trigger.setMessageQuery("status=PUBLISHED");
        trigger.setMessageFilter("filter.expr");
        trigger.setPublicReport(Boolean.FALSE);
        trigger.createDesc("en").setSubject("S");

        MailingListTriggerVo result = trigger.toVo(DataFilter.get());

        assertEquals(TriggerType.SCHEDULED, result.getType());
        assertEquals(ScheduleType.TUESDAY, result.getScheduleType());
        assertEquals("23:15", result.getScheduledExecutionTime());
        assertEquals("Europe/Copenhagen", result.getScheduledExecutionTimeZone());
        assertEquals("status=PUBLISHED", result.getMessageQuery());
        assertEquals("filter.expr", result.getMessageFilter());
        assertEquals(Boolean.FALSE, result.getPublicReport());
        assertEquals(1, result.getDescs().size(), "one desc round-trips");
        assertEquals("S", result.getDescs().get(0).getSubject());
        assertEquals("en", result.getDescs().get(0).getLang());
    }

    @Test
    public void toVoCopiesStatusChangesWhenNonEmpty() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.STATUS_CHANGE);
        trigger.setStatusChanges(new HashSet<>(Arrays.asList(Status.PUBLISHED, Status.EXPIRED)));

        MailingListTriggerVo result = trigger.toVo(DataFilter.get());

        assertEquals(new HashSet<>(Arrays.asList(Status.PUBLISHED, Status.EXPIRED)),
                result.getStatusChanges());
    }

    @Test
    public void toVoLeavesStatusChangesNullWhenEmpty() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        // statusChanges defaults to an empty set.

        MailingListTriggerVo result = trigger.toVo(DataFilter.get());

        assertNull(result.getStatusChanges(), "empty statusChanges must not be copied (VO stays null)");
    }

    @Test
    public void toVoCopiesScriptResourcePathsWhenNonEmpty() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setScriptResourcePaths(new ArrayList<>(Arrays.asList("a.ftl", "b.js")));

        MailingListTriggerVo result = trigger.toVo(DataFilter.get());

        assertEquals(Arrays.asList("a.ftl", "b.js"), result.getScriptResourcePaths());
    }

    @Test
    public void toVoLeavesScriptResourcePathsNullWhenEmpty() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        // scriptResourcePaths defaults to an empty list.

        MailingListTriggerVo result = trigger.toVo(DataFilter.get());

        assertNull(result.getScriptResourcePaths(),
                "empty scriptResourcePaths must not be copied (VO stays null)");
    }

    // -------------------------------------------------------------------------
    // checkComputeNextScheduledExecution
    // -------------------------------------------------------------------------

    @Test
    public void checkComputeNextScheduledExecutionSetsFutureDateForValidScheduledTrigger() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setScheduleType(ScheduleType.DAILY);
        trigger.setScheduledExecutionTime("15:55");

        Date before = new Date();
        trigger.checkComputeNextScheduledExecution();

        // nextScheduledExecution has no getter, so read the package-private field directly.
        assertSame(Date.class, trigger.nextScheduledExecution.getClass(), "the field type is a Date");
        assertTrue(!trigger.nextScheduledExecution.before(before),
                "computed next execution must be a future time (DAILY rolls forward if needed)");
    }

    @Test
    public void checkComputeNextScheduledExecutionLeavesFieldNullForStatusChangeTrigger() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.STATUS_CHANGE);
        trigger.setScheduleType(ScheduleType.DAILY);
        trigger.setScheduledExecutionTime("15:55");

        trigger.checkComputeNextScheduledExecution();

        assertNull(trigger.nextScheduledExecution,
                "STATUS_CHANGE triggers never compute a next execution");
    }

    @Test
    public void checkComputeNextScheduledExecutionLeavesFieldNullWhenScheduleTypeMissing() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setScheduleType(null);
        trigger.setScheduledExecutionTime("15:55");

        trigger.checkComputeNextScheduledExecution();

        assertNull(trigger.nextScheduledExecution, "a missing scheduleType blocks computation");
    }

    @Test
    public void checkComputeNextScheduledExecutionLeavesFieldNullWhenTimeBlank() {
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setScheduleType(ScheduleType.DAILY);
        trigger.setScheduledExecutionTime("   ");

        trigger.checkComputeNextScheduledExecution();

        assertNull(trigger.nextScheduledExecution, "a blank execution time blocks computation");
    }

    // -------------------------------------------------------------------------
    // createDesc
    // -------------------------------------------------------------------------

    @Test
    public void createDescSetsLangAndEntityBackReferenceAndAddsToDescs() {
        MailingListTrigger trigger = new MailingListTrigger();

        MailingListTriggerDesc desc = trigger.createDesc("en");

        assertEquals("en", desc.getLang());
        assertSame(trigger, desc.getEntity(), "desc back-references the creating trigger");
        assertEquals(1, trigger.getDescs().size(), "desc list grows by one");
        assertSame(desc, trigger.getDescs().get(0), "the created desc is the one added");
    }

    @Test
    public void createDescAppendsMultipleDescsInOrder() {
        MailingListTrigger trigger = new MailingListTrigger();

        MailingListTriggerDesc en = trigger.createDesc("en");
        MailingListTriggerDesc da = trigger.createDesc("da");

        List<MailingListTriggerDesc> descs = trigger.getDescs();
        assertEquals(2, descs.size());
        assertSame(en, descs.get(0));
        assertSame(da, descs.get(1));
        assertEquals("da", descs.get(1).getLang());
    }
}
