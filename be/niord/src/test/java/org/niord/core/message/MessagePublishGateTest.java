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
package org.niord.core.message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.niord.core.message.MessagePublishGate.Code;
import org.niord.core.message.MessagePublishGate.Reason;
import org.niord.core.message.MessagePublishGate.Refusal;
import org.niord.core.promulgation.NavtexMessagePromulgation;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The publish gate (MSG10): a change to VERIFIED or PUBLISHED over the status endpoints is held to what the
 * editor's own Verify and Publish allow. A message without a title in any language, or with a MANDATORY promulgation
 * type not ticked, may become neither; a message whose publish-from date is still ahead may become VERIFIED but not
 * PUBLISHED. Every other target status passes.
 * <p>
 * The active promulgation types come from a mocked {@link PromulgationTypeService}; the endpoints' use of the gate
 * and its 409 are tested in {@code MessagePublishGateITTest}.
 */
public class MessagePublishGateTest {

    private static final Date NOW = new Date(1_800_000_000_000L);
    private static final Date LATER = new Date(NOW.getTime() + 60_000L);
    private static final Date EARLIER = new Date(NOW.getTime() - 60_000L);

    private PromulgationTypeService promulgationTypeService;
    private MessagePublishGate gate;
    private final List<PromulgationType> activeTypes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        promulgationTypeService = mock(PromulgationTypeService.class);
        when(promulgationTypeService.getActivePromulgationTypes(any())).thenAnswer(i -> List.copyOf(activeTypes));
        gate = new MessagePublishGate(promulgationTypeService);
    }

    /** An active promulgation type of the current domain */
    private PromulgationType activeType(String typeId, String name, Requirement requirement) {
        PromulgationType type = new PromulgationType();
        type.setTypeId(typeId);
        type.setName(name);
        type.setRequirement(requirement);
        type.setActive(true);
        activeTypes.add(type);
        return type;
    }

    /** A draft local warning with an English title, no promulgations and no publish-from date */
    private static Message draft(String title) {
        Message message = new Message();
        message.setUid("uid-1");
        message.setType(Type.LOCAL_WARNING);
        message.setStatus(Status.DRAFT);
        message.createDesc("en").setTitle(title);
        return message;
    }

    private static void promulgation(Message message, PromulgationType type, boolean promulgate) {
        NavtexMessagePromulgation promulgation = new NavtexMessagePromulgation();
        promulgation.setType(type);
        promulgation.setPromulgate(promulgate);
        message.addPromulgation(promulgation);
    }

    private static List<Code> codes(Refusal refusal) {
        assertNotNull(refusal, "the gate let the change pass");
        return refusal.reasons().stream().map(Reason::code).toList();
    }

    /* ---------------------------------------------------------------------------------------------------------- */
    /* The title                                                                                                    */
    /* ---------------------------------------------------------------------------------------------------------- */

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"VERIFIED", "PUBLISHED"})
    void aMessageWithoutATitle_isRefused(Status target) {
        Message message = draft(null);

        assertEquals(List.of(Code.NO_TITLE), codes(gate.check(message, target, NOW)));
    }

    @Test
    void aMessageWithNoDescriptionAtAll_isRefused() {
        Message message = new Message();
        message.setUid("uid-2");
        message.setType(Type.LOCAL_WARNING);

        assertEquals(List.of(Code.NO_TITLE), codes(gate.check(message, Status.PUBLISHED, NOW)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", " ", "    ", "﻿"})
    void aTitleOfWhiteSpaceOnly_isNoTitle(String title) {
        Message message = draft(title);

        assertEquals(List.of(Code.NO_TITLE), codes(gate.check(message, Status.VERIFIED, NOW)));
    }

    @Test
    void aTitleInAnyOneLanguage_isEnough() {
        Message message = draft("  ");
        message.createDesc("da").setTitle("Lys slukket");

        assertNull(gate.check(message, Status.PUBLISHED, NOW));
    }

    @Test
    void aTitledMessageWithNoMandatoryTypeAndNoDate_passes() {
        assertNull(gate.check(draft("Light unlit"), Status.VERIFIED, NOW));
        assertNull(gate.check(draft("Light unlit"), Status.PUBLISHED, NOW));
    }

    /* ---------------------------------------------------------------------------------------------------------- */
    /* The mandatory promulgations                                                                                  */
    /* ---------------------------------------------------------------------------------------------------------- */

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"VERIFIED", "PUBLISHED"})
    void aMandatoryTypeTheMessageHasNoRowFor_isRefusedWithItsName(Status target) {
        activeType("navtex", "NAVTEX", Requirement.MANDATORY);
        Message message = draft("Light unlit");

        Refusal refusal = gate.check(message, target, NOW);

        assertEquals(List.of(Code.MANDATORY_PROMULGATION_OFF), codes(refusal));
        assertEquals(List.of("NAVTEX"), refusal.reasons().getFirst().types());
    }

    @Test
    void aMandatoryTypeWhoseRowIsNotTicked_isRefused() {
        PromulgationType navtex = activeType("navtex", "NAVTEX", Requirement.MANDATORY);
        Message message = draft("Light unlit");
        promulgation(message, navtex, false);

        assertEquals(List.of(Code.MANDATORY_PROMULGATION_OFF), codes(gate.check(message, Status.VERIFIED, NOW)));
    }

    @Test
    void aTickedMandatoryType_passes() {
        PromulgationType navtex = activeType("navtex", "NAVTEX", Requirement.MANDATORY);
        Message message = draft("Light unlit");
        promulgation(message, navtex, true);

        assertNull(gate.check(message, Status.PUBLISHED, NOW));
    }

    @Test
    void aStoredRowIsMatchedByTypeId_notByTheInstance() {
        PromulgationType navtex = activeType("navtex", "NAVTEX", Requirement.MANDATORY);
        PromulgationType sameTypeLoadedAgain = new PromulgationType();
        sameTypeLoadedAgain.setTypeId(navtex.getTypeId());
        Message message = draft("Light unlit");
        promulgation(message, sameTypeLoadedAgain, true);

        assertNull(gate.check(message, Status.PUBLISHED, NOW));
    }

    @Test
    void defaultAndOptionalTypesOff_pass() {
        PromulgationType audio = activeType("audio", "Audio", Requirement.DEFAULT);
        activeType("twitter", "Twitter", Requirement.OPTIONAL);
        Message message = draft("Light unlit");
        promulgation(message, audio, false);

        assertNull(gate.check(message, Status.PUBLISHED, NOW));
    }

    @Test
    void aMandatoryTypeThatIsNotActiveForTheMessage_isNotRequired() {
        // The service returns only the active types of the current domain and the message's type, so a MANDATORY
        // type of another domain or another message type is never asked for, also when the message holds it off
        PromulgationType elsewhere = new PromulgationType();
        elsewhere.setTypeId("navtex-elsewhere");
        elsewhere.setRequirement(Requirement.MANDATORY);
        Message message = draft("Light unlit");
        promulgation(message, elsewhere, false);

        assertNull(gate.check(message, Status.PUBLISHED, NOW));
        verify(promulgationTypeService).getActivePromulgationTypes(Type.LOCAL_WARNING);
    }

    @Test
    void everyMandatoryTypeOff_isNamed_inTheServicesOrder() {
        activeType("safetynet", "SafetyNET", Requirement.MANDATORY);
        PromulgationType audio = activeType("audio", "Audio", Requirement.MANDATORY);
        activeType("navtex", "NAVTEX", Requirement.MANDATORY);
        Message message = draft("Light unlit");
        promulgation(message, audio, true);

        Refusal refusal = gate.check(message, Status.VERIFIED, NOW);

        assertEquals(List.of(Code.MANDATORY_PROMULGATION_OFF), codes(refusal));
        assertEquals(List.of("SafetyNET", "NAVTEX"), refusal.reasons().getFirst().types());
    }

    @Test
    void aTypeWithoutAName_isNamedByItsId() {
        activeType("navtex", null, Requirement.MANDATORY);

        Refusal refusal = gate.check(draft("Light unlit"), Status.VERIFIED, NOW);

        assertEquals(List.of("navtex"), refusal.reasons().getFirst().types());
    }

    /* ---------------------------------------------------------------------------------------------------------- */
    /* The publish-from date                                                                                        */
    /* ---------------------------------------------------------------------------------------------------------- */

    @Test
    void publishingBeforeThePublishFromDate_isRefusedWithTheDate() {
        Message message = draft("Light unlit");
        message.setStatus(Status.VERIFIED);
        message.setPublishDateFrom(LATER);

        Refusal refusal = gate.check(message, Status.PUBLISHED, NOW);

        assertEquals(List.of(Code.SCHEDULED), codes(refusal));
        assertEquals(LATER.getTime(), refusal.reasons().getFirst().date().getTime());
    }

    @Test
    void verifyingAMessageWithAPublishFromDateAhead_passes() {
        // That is how a message is scheduled: the scheduler publishes a VERIFIED message at its date
        Message message = draft("Light unlit");
        message.setPublishDateFrom(LATER);

        assertNull(gate.check(message, Status.VERIFIED, NOW));
    }

    @Test
    void publishingAtOrAfterThePublishFromDate_orWithoutOne_passes() {
        Message message = draft("Light unlit");

        assertNull(gate.check(message, Status.PUBLISHED, NOW));
        message.setPublishDateFrom(EARLIER);
        assertNull(gate.check(message, Status.PUBLISHED, NOW));
        message.setPublishDateFrom(new Date(NOW.getTime()));
        assertNull(gate.check(message, Status.PUBLISHED, NOW));
    }

    @Test
    void aPublishFromDateGivenAsASqlTimestamp_isCompared() {
        // Hibernate may load the date as a java.sql.Timestamp, whose after(Date) is not Date's
        Message message = draft("Light unlit");
        message.setPublishDateFrom(new java.sql.Timestamp(LATER.getTime()));

        assertEquals(List.of(Code.SCHEDULED), codes(gate.check(message, Status.PUBLISHED, NOW)));
    }

    @Test
    void theOneArgumentCheck_comparesWithTheClock() {
        Message message = draft("Light unlit");
        message.setPublishDateFrom(new Date(System.currentTimeMillis() + 3_600_000L));

        assertEquals(List.of(Code.SCHEDULED), codes(gate.check(message, Status.PUBLISHED)));
        message.setPublishDateFrom(new Date(System.currentTimeMillis() - 3_600_000L));
        assertNull(gate.check(message, Status.PUBLISHED));
    }

    /* ---------------------------------------------------------------------------------------------------------- */
    /* Every reason at once, and the statuses the gate does not check                                              */
    /* ---------------------------------------------------------------------------------------------------------- */

    @Test
    void everyReasonIsGiven_inOneRefusal() {
        activeType("navtex", "NAVTEX", Requirement.MANDATORY);
        Message message = draft(null);
        message.setShortId("NW-001-26");
        message.setPublishDateFrom(LATER);

        Refusal refusal = gate.check(message, Status.PUBLISHED, NOW);

        assertEquals(List.of(Code.NO_TITLE, Code.MANDATORY_PROMULGATION_OFF, Code.SCHEDULED), codes(refusal));
        assertEquals("uid-1", refusal.messageId());
        assertEquals("NW-001-26", refusal.shortId());
        assertEquals("Message NW-001-26 cannot be given this status: it has no title; its mandatory promulgation "
                + "NAVTEX is not ticked; it is scheduled for publication at " + LATER.toInstant() + ".",
                refusal.describe());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"VERIFIED", "PUBLISHED"}, mode = EnumSource.Mode.EXCLUDE)
    void otherTargetStatuses_areNotChecked(Status target) {
        activeType("navtex", "NAVTEX", Requirement.MANDATORY);
        Message message = draft(null);
        message.setPublishDateFrom(LATER);

        assertNull(gate.check(message, target, NOW));
        assertFalse(MessagePublishGate.gates(target));
    }

    @Test
    void aNullTarget_isNotChecked() {
        // The endpoints refuse a missing status themselves (400)
        assertNull(gate.check(draft(null), null, NOW));
        verifyNoInteractions(promulgationTypeService);
    }

    @Test
    void gates_namesVerifiedAndPublished() {
        assertTrue(MessagePublishGate.gates(Status.VERIFIED));
        assertTrue(MessagePublishGate.gates(Status.PUBLISHED));
    }

    @Test
    void aMessageWithoutAShortId_isNamedByItsUid() {
        Message message = draft(null);
        message.setShortId("  ");

        Refusal refusal = gate.check(message, Status.VERIFIED, NOW);

        assertNull(refusal.shortId());
        assertEquals("Message uid-1 cannot be given this status: it has no title.", refusal.describe());
    }

    @Test
    void severalMandatoryTypesOff_areDescribedInThePlural() {
        activeType("safetynet", "SafetyNET", Requirement.MANDATORY);
        activeType("navtex", "NAVTEX", Requirement.MANDATORY);

        Refusal refusal = gate.check(draft("Light unlit"), Status.VERIFIED, NOW);

        assertEquals("Message uid-1 cannot be given this status: its mandatory promulgations SafetyNET, NAVTEX "
                + "are not ticked.", refusal.describe());
    }

    /* ---------------------------------------------------------------------------------------------------------- */
    /* The JSON the status endpoints answer with (the dialog reads it, P2-1-fe)                                     */
    /* ---------------------------------------------------------------------------------------------------------- */

    @Test
    void aRefusalWritesMessageIdShortIdAndReasons_leavingOutWhatAReasonDoesNotCarry() {
        activeType("navtex", "NAVTEX", Requirement.MANDATORY);
        Message message = draft(null);
        message.setPublishDateFrom(LATER);

        JsonNode json = new ObjectMapper().valueToTree(gate.check(message, Status.PUBLISHED, NOW));

        assertEquals("uid-1", json.get("messageId").asString());
        assertFalse(json.has("shortId"), json.toString());
        JsonNode reasons = json.get("reasons");
        assertEquals(3, reasons.size());
        assertEquals("NO_TITLE", reasons.get(0).get("code").asString());
        assertFalse(reasons.get(0).has("types"), json.toString());
        assertFalse(reasons.get(0).has("date"), json.toString());
        assertEquals("MANDATORY_PROMULGATION_OFF", reasons.get(1).get("code").asString());
        assertEquals("NAVTEX", reasons.get(1).get("types").get(0).asString());
        assertFalse(reasons.get(1).has("date"), json.toString());
        assertEquals("SCHEDULED", reasons.get(2).get("code").asString());
        assertTrue(reasons.get(2).has("date"), json.toString());
        assertFalse(reasons.get(2).has("types"), json.toString());
    }
}
