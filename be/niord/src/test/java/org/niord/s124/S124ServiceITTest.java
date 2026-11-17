/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.s124;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.xml.XmlAssert;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link S124Service#generateGML(String, String)} - the message-to-S-124-GML
 * conversion that backs the public {@code /rest/S-124} API. These exercise the DB-resolution and
 * validation logic that the pure-template test ({@code S124Test}, in the {@code niord} module) cannot:
 * resolving a persisted message by UID and by short ID, and the reject branches (unknown id,
 * non-public status, NM, un-numbered).
 *
 * <p>Run as a {@code @QuarkusTest} against the seeded MySQL container ({@link ITTest}). The seed has
 * no {@code Message} rows, so each test creates the message it needs through the real
 * {@link MessageService#createMessage}; every test is wrapped in {@link TestTransaction} so the writes
 * roll back and the shared seed stays pristine ({@code generateGML} resolves the message via JPA, which
 * sees the flushed in-transaction rows). This is a framework-coupled test - see
 * docs/framework-coupled-tests.md.</p>
 */
class S124ServiceITTest extends ITTest {

    @Autowired
    S124Service s124Service;

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    /** A fixed past publish date (2001) -> deterministic GML year "2001"; keeps off the current-year sequence. */
    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Creates and flushes a PUBLISHED message of the given series/type, then stamps a number + short id. */
    private Message persistMessage(String seriesId, Type type, Integer number, String shortId) throws Exception {
        return persistMessage(seriesId, type, number, shortId, Status.PUBLISHED);
    }

    /**
     * Creates and flushes a message with the given status. The status is stamped directly
     * (like the number/short id) - the real publish machinery would renumber the message
     * from the series sequence and break the deterministic assertions.
     */
    private Message persistMessage(String seriesId, Type type, Integer number, String shortId, Status status) throws Exception {
        Message msg = new Message();
        MessageSeries series = new MessageSeries(seriesId);
        series.setMainType(type.getMainType());
        msg.setMessageSeries(series);
        msg.setType(type);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate());
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("S-124 subject");
        pd.setDetails("<p>Light buoy replaced.</p>");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle("S-124 IT message");

        Message created = messageService.createMessage(msg);
        if (number != null) {
            created.setNumber(number);
        }
        if (shortId != null) {
            created.setShortId(shortId);
        }
        created.checkUpdateYear();
        created.setStatus(status);
        em.flush();
        return created;
    }

    /* ---------------------------------------------------------------- */
    /* Happy path                                                       */
    /* ---------------------------------------------------------------- */

    @Test
    @Transactional
    void generateGmlForNumberedNwMessageByUidProducesValidGml() throws Exception {
        Message msg = persistMessage("dma-nw", Type.COASTAL_WARNING, 911, "NW-911-01");

        String gml = s124Service.generateGML(msg.getUid(), "en");

        XmlAssert xml = XmlAssert.parse(gml); // also proves well-formed (and generation is XSD-validated)
        xml.assertRoot("Dataset")
           .assertRootAttr("id", "NW-911-01")
           .assertElementCount("NavwarnPreamble", 1)
           .assertElementTextContains("warningType", "Coastal Navigational Warning")
           .assertElementText("warningNumber", "911")
           .assertElementText("year", "2001")
           .assertElementText("agencyResponsibleForProduction", "DK00") // the S-62 code from niord.s124.producer-code
           // The country setting: the test root niord.json and the seed set none, so it is the base
           // product's neutral XX
           .assertElementText("nationality", "XX")
           .assertElementText("interoperabilityIdentifier", "urn:mrn:iho:nw:xx:nw-911-01");
    }

    @Test
    @Transactional
    void generateGmlResolvesMessageByShortId() throws Exception {
        persistMessage("dma-nw", Type.COASTAL_WARNING, 912, "NW-912-01");

        // Pass the short ID rather than the UID - resolveMessage must fall back to findByShortId.
        String gml = s124Service.generateGML("NW-912-01", "en");

        XmlAssert.parse(gml)
                 .assertRoot("Dataset")
                 .assertRootAttr("id", "NW-912-01")
                 .assertElementText("warningNumber", "912");
    }

    @Test
    @Transactional
    void generateGmlDanishLanguageSetsDanishDatasetLanguage() throws Exception {
        Message msg = persistMessage("dma-nw", Type.COASTAL_WARNING, 913, "NW-913-01");
        // The requested language leads only when the message has content in it
        msg.createDesc("da").setTitle("S-124 IT meddelelse");
        msg.getParts().getFirst().createDesc("da").setDetails("<p>Lystønde udskiftet.</p>");
        em.flush();

        String gml = s124Service.generateGML(msg.getUid(), "da");

        // The agency is language-independent in S-124 v2.0.0; the requested language drives
        // the ISO 639-2 dataset language instead, and the English texts ride along
        XmlAssert.parse(gml)
                 .assertElementText("datasetLanguage", "dan")
                 .assertElementCount("information", 2)
                 .assertElementTextContains("warningInformation", "Lystønde udskiftet")
                 .assertElementTextContains("warningInformation", "Light buoy replaced");
    }

    @Test
    @Transactional
    void requestedLanguageWithoutContentFallsBackToEnglish() throws Exception {
        // The English-only IT message: asking for Danish cannot produce a Danish dataset, so it
        // is spelled in the first candidate that has content
        Message msg = persistMessage("dma-nw", Type.COASTAL_WARNING, 914, "NW-914-01");

        XmlAssert.parse(s124Service.generateGML(msg.getUid(), "da"))
                 .assertElementText("datasetLanguage", "eng")
                 .assertElementCount("information", 1);
    }

    /* ---------------------------------------------------------------- */
    /* Reject branches -> IllegalArgumentException                      */
    /* ---------------------------------------------------------------- */

    @Test
    @Transactional
    void generateGmlForUnknownMessageThrowsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> s124Service.generateGML("no-such-message-id", "en"));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("not found"),
                "message should indicate the message was not found; got: " + ex.getMessage());
    }

    @Test
    @Transactional
    void generateGmlForBlankMessageIdThrowsIllegalArgumentException() {
        // resolveMessage("") returns null -> generateGML raises the not-found IAE.
        assertThrows(IllegalArgumentException.class,
                () -> s124Service.generateGML("   ", "en"));
    }

    @Test
    @Transactional
    void generateGmlForNoticeToMarinersIsRejected() throws Exception {
        // S-124 does not support NM (Notices to Mariners T&P).
        Message nm = persistMessage("dma-nm", Type.TEMPORARY_NOTICE, 5, "NM-005-01");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> s124Service.generateGML(nm.getUid(), "en"));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("Notices to Mariners"),
                "NM messages must be rejected with the T&P message; got: " + ex.getMessage());
    }

    @Test
    @Transactional
    void generateGmlForUnnumberedWarningIsRejected() throws Exception {
        // A published warning from a MANUAL series may carry no number -> S-124 rejects
        // un-numbered warnings for FINAL generation.
        Message msg = persistMessage("dma-nw", Type.COASTAL_WARNING, null, null);
        assertNotNull(msg.getUid());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> s124Service.generateGML(msg.getUid(), "en"));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("un-numbered"),
                "un-numbered warnings must be rejected; got: " + ex.getMessage());
    }

    @Test
    @Transactional
    void generateGmlForUnpublishedDraftIsRejectedAsNotFound() throws Exception {
        // S124-5: the id-resolving entry backs the anonymous /rest/S-124 endpoint, so
        // unpublished drafts must not leak - and the rejection is shaped exactly like an
        // unknown id, so the response does not confirm that the UID exists.
        Message draft = persistMessage("dma-nw", Type.COASTAL_WARNING, 916, "NW-916-01", Status.DRAFT);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> s124Service.generateGML(draft.getUid(), "en"));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("not found"),
                "drafts must be reported exactly like unknown ids; got: " + ex.getMessage());
    }

    @Test
    @Transactional
    void generateGmlServesCancelledMessages() throws Exception {
        // Formerly-published content stays retrievable (Status.isPublic()): the SECOM store
        // regenerates the final GML from the CANCELLED message, and cancellation references
        // (spec use case 2) must stay resolvable.
        Message cancelled = persistMessage("dma-nw", Type.COASTAL_WARNING, 917, "NW-917-01", Status.CANCELLED);

        String gml = s124Service.generateGML(cancelled.getUid(), "en");

        XmlAssert.parse(gml)
                 .assertRoot("Dataset")
                 .assertRootAttr("id", "NW-917-01");
    }
}
