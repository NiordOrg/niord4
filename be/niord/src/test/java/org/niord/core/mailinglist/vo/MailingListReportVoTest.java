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
package org.niord.core.mailinglist.vo;

import org.junit.jupiter.api.Test;
import org.niord.core.mailinglist.MailingList;
import org.niord.core.mailinglist.MailingListTrigger;
import org.niord.core.mailinglist.ScheduleType;
import org.niord.core.mailinglist.TriggerType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the validation and projection behavior of the
 * {@link MailingListReportVo#MailingListReportVo(MailingListTrigger, String)} constructor.
 *
 * <p>This VO wraps a SCHEDULED trigger that has been flagged as a public report, copying a
 * language-resolved snapshot of the parent {@link MailingList}'s name plus the trigger's
 * scheduling fields. The test lives in the same package as the production class so it can
 * build the entity graph with package-private setters and read the package-private fields.
 *
 * <p>Behavior pinned:
 * <ul>
 *   <li><b>Valid construction</b> copies the trigger id, the schedule type/time/time-zone, the
 *       supplied {@code lang}, and the parent mailing-list name for that language.</li>
 *   <li><b>Name is language-resolved</b> via {@code mailingList.getDescs(DataFilter.get().lang(lang))
 *       .get(0).getName()}: passing "da" yields the Danish desc name, "en" the English one.</li>
 *   <li><b>Validation guard</b>: the constructor throws {@link IllegalArgumentException} unless the
 *       trigger is BOTH {@code TriggerType.SCHEDULED} AND has {@code publicReport == Boolean.TRUE}.
 *       The non-obvious oracle is that the check is {@code getPublicReport() != Boolean.TRUE}, so a
 *       {@code null} publicReport and an explicit {@code Boolean.FALSE} are both rejected, and a
 *       STATUS_CHANGE trigger is rejected regardless of the publicReport flag.</li>
 * </ul>
 */
public class MailingListReportVoTest {

    /** Builds a mailing list with English + Danish description names. */
    private MailingList newMailingList() {
        MailingList ml = new MailingList();
        ml.createDesc("en").setName("Report EN");
        ml.createDesc("da").setName("Report DA");
        return ml;
    }

    /**
     * Builds a SCHEDULED, public-report trigger (id 42, DAILY at 08:00 UTC) attached to the
     * given mailing list. Callers mutate fields before constructing the VO to drive each case.
     */
    private MailingListTrigger newScheduledTrigger(MailingList ml) {
        MailingListTrigger t = new MailingListTrigger();
        t.setId(42);
        t.setType(TriggerType.SCHEDULED);
        t.setPublicReport(Boolean.TRUE);
        t.setScheduleType(ScheduleType.DAILY);
        t.setScheduledExecutionTime("08:00");
        t.setScheduledExecutionTimeZone("UTC");
        ml.addTrigger(t);
        return t;
    }

    @Test
    public void validScheduledPublicTrigger_copiesIdAndScheduleFields() {
        MailingList ml = newMailingList();
        MailingListTrigger t = newScheduledTrigger(ml);

        MailingListReportVo vo = new MailingListReportVo(t, "en");

        assertEquals(Integer.valueOf(42), vo.getId(), "trigger id should be copied");
        assertEquals(ScheduleType.DAILY, vo.getScheduleType(), "schedule type should be copied");
        assertEquals("08:00", vo.getScheduledExecutionTime(), "execution time should be copied");
        assertEquals("UTC", vo.getScheduledExecutionTimeZone(), "execution time-zone should be copied");
        assertEquals("en", vo.getLang(), "lang should be the constructor argument");
    }

    @Test
    public void validTrigger_resolvesNameForEnglishLang() {
        MailingList ml = newMailingList();
        MailingListTrigger t = newScheduledTrigger(ml);

        MailingListReportVo vo = new MailingListReportVo(t, "en");

        assertEquals("Report EN", vo.getName(), "name should be the English mailing-list desc name");
    }

    @Test
    public void validTrigger_resolvesNameForDanishLang() {
        MailingList ml = newMailingList();
        MailingListTrigger t = newScheduledTrigger(ml);

        MailingListReportVo vo = new MailingListReportVo(t, "da");

        assertEquals("Report DA", vo.getName(), "name should honor the requested lang");
        assertEquals("da", vo.getLang(), "lang should be the constructor argument");
    }

    @Test
    public void statusChangeTrigger_isRejected() {
        MailingList ml = newMailingList();
        MailingListTrigger t = newScheduledTrigger(ml);
        // Wrong type: even with publicReport == TRUE this must be rejected.
        t.setType(TriggerType.STATUS_CHANGE);

        try {
            new MailingListReportVo(t, "en");
            fail("STATUS_CHANGE trigger must not be usable as a public report");
        } catch (IllegalArgumentException e) {
            assertEquals("Trigger 42 cannot be used as a public report", e.getMessage(),
                    "error message should reference the offending trigger id");
        }
    }

    @Test
    public void scheduledTriggerWithNullPublicReport_isRejected() {
        MailingList ml = newMailingList();
        MailingListTrigger t = newScheduledTrigger(ml);
        // publicReport left unset (null) -> getPublicReport() != Boolean.TRUE is true -> rejected.
        t.setPublicReport(null);

        try {
            new MailingListReportVo(t, "en");
            fail("SCHEDULED trigger without publicReport flag must be rejected");
        } catch (IllegalArgumentException e) {
            assertEquals("Trigger 42 cannot be used as a public report", e.getMessage());
        }
    }

    @Test
    public void scheduledTriggerWithFalsePublicReport_isRejected() {
        MailingList ml = newMailingList();
        MailingListTrigger t = newScheduledTrigger(ml);
        t.setPublicReport(Boolean.FALSE);

        try {
            new MailingListReportVo(t, "en");
            fail("SCHEDULED trigger with publicReport == FALSE must be rejected");
        } catch (IllegalArgumentException e) {
            assertEquals("Trigger 42 cannot be used as a public report", e.getMessage());
        }
    }

    @Test
    public void statusChangeTriggerWithNullPublicReport_isRejected() {
        MailingList ml = newMailingList();
        MailingListTrigger t = newScheduledTrigger(ml);
        // Both guards fail: wrong type AND missing publicReport flag.
        t.setType(TriggerType.STATUS_CHANGE);
        t.setPublicReport(null);

        try {
            new MailingListReportVo(t, "en");
            fail("STATUS_CHANGE trigger without publicReport flag must be rejected");
        } catch (IllegalArgumentException e) {
            assertEquals("Trigger 42 cannot be used as a public report", e.getMessage());
        }
    }
}
