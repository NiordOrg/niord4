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
import org.niord.test.Tx;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.xml.XmlAssert;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-contract integration tests for {@link S124RestService} - the public {@code /rest/S-124} API.
 * The endpoints carry no security annotations (so anonymous access is permitted), and this suite
 * asserts the status-code mapping, content type and body for:
 * <ul>
 *   <li>{@code GET /rest/S-124/messages/{id}} -&gt; 200 + {@code application/gml+xml} for a real
 *       published numbered NW message, 404 for an unknown id or an unpublished draft
 *       (S124-5: the endpoint is anonymous, so drafts must not leak), and 400 with a fixed
 *       {@code text/plain} body for a message whose GML fails to generate;</li>
 *   <li>{@code GET /rest/S-124/xsds/{file}} -&gt; 200 for a bundled XSD, 404 for an unknown one.</li>
 * </ul>
 *
 * <p>HTTP requests run in the server's own transaction, which a {@code @Transactional} cannot roll
 * back, so the messages are created committed via {@link #commitNw} (with unique UIDs that never
 * occur in the seed) and deleted in {@link #deleteMessages()} - history rows
 * first, since {@code Message.history} is a non-cascading {@code @OneToMany}. Each test is therefore
 * independent and leaves the shared seed pristine. The GML <em>content</em> itself is asserted by
 * {@code S124Test} / {@code S124ServiceITTest}; here we focus on the HTTP envelope. This is a
 * framework-coupled test - see docs/framework-coupled-tests.md.</p>
 */
class S124RestServiceITTest extends ITTest {

    private static final String MESSAGES = "/rest/S-124/messages/";
    private static final String XSDS = "/rest/S-124/xsds/";

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    private final List<Integer> createdIds = new ArrayList<>();
    private String createdUid;

    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Builds a detached DRAFT NW message with one English DETAILS part. */
    private static Message buildDraftNw() {
        Message msg = new Message();
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(Type.COASTAL_WARNING.getMainType());
        msg.setMessageSeries(series);
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate());
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("S-124 subject");
        pd.setDetails("<p>Light buoy replaced.</p>");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle("S-124 REST IT message");
        return msg;
    }

    @BeforeEach
    void createMessage() {
        createdUid = commitNw(Status.PUBLISHED, 914, "NW-914-01");
    }

    /**
     * Creates a committed NW with the given status (stamped directly, like the number/short
     * id - the real publish machinery would renumber the message from the series sequence).
     */
    private String commitNw(Status status, int number, String shortId) {
        return Tx.requiringNew().call(() -> {
            try {
                Message created = messageService.createMessage(buildDraftNw());
                created.setNumber(number);
                created.setShortId(shortId);
                created.checkUpdateYear();
                created.setStatus(status);
                em.flush();
                createdIds.add(created.getId());
                return created.getUid();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @AfterEach
    void deleteMessages() {
        for (Integer id : createdIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory h where h.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
        }
        createdIds.clear();
        createdUid = null;
    }

    /* ---------------------------------------------------------------- */
    /* GET /rest/S-124/messages/{id}                                    */
    /* ---------------------------------------------------------------- */

    @Test
    void getMessageByUidReturns200GmlXml() {
        assertNotNull(createdUid, "the committed message must have a UID");

        Response resp = given().when().get(MESSAGES + createdUid).then().extract().response();

        assertEquals(200, resp.statusCode(), "an existing numbered NW message must return 200");
        assertTrue(resp.getContentType() != null && resp.getContentType().contains("gml+xml"),
                "response must be served as GML; got content-type: " + resp.getContentType());

        XmlAssert.parse(resp.getBody().asString())
                 .assertRoot("Dataset")
                 .assertRootAttr("id", "NW-914-01")
                 .assertElementText("warningNumber", "914");
    }

    @Test
    void getMessageByUnknownIdReturns404() {
        given().when()
                .get(MESSAGES + "this-message-does-not-exist")
                .then()
                .statusCode(404)
                .body(org.hamcrest.Matchers.containsString("No message found"));
    }

    @Test
    void getUnpublishedDraftReturns404() {
        // S124-5: an anonymous caller who knows a draft's UID must not be able to read it
        // before publication; the response is byte-shaped like the unknown-id case, so it
        // does not confirm that the UID exists.
        String draftUid = commitNw(Status.DRAFT, 918, "NW-918-01");

        given().when()
                .get(MESSAGES + draftUid)
                .then()
                .statusCode(404)
                .body(org.hamcrest.Matchers.containsString("No message found"));
    }

    @Test
    void generationFailureAnswers400InPlainTextWithoutTheMessageData() {
        // A short id is the dataset's gml:id, an XML ID, and one starting with a digit fails the schema
        // validation. The validator's message quotes the offending value, and the value is data an editor
        // wrote, so the answer is a fixed plain text and the detail stays in the log.
        String shortId = "9IT-" + System.nanoTime();
        String uid = commitNw(Status.PUBLISHED, 919, shortId);

        Response resp = given().when().get(MESSAGES + uid).then().extract().response();

        assertEquals(400, resp.statusCode(), "a message whose GML cannot be generated must answer 400");
        assertTrue(resp.getContentType() != null && resp.getContentType().startsWith("text/plain"),
                "the error must be served as plain text; got content-type: " + resp.getContentType());
        assertEquals("Error generating GML", resp.getBody().asString());
    }

    @Test
    void notFoundAnswerIsPlainText() {
        Response resp = given().when().get(MESSAGES + "this-message-does-not-exist").then().extract().response();

        assertEquals(404, resp.statusCode());
        assertTrue(resp.getContentType() != null && resp.getContentType().startsWith("text/plain"),
                "the 404 echoes the id from the address, so it must be plain text; got " + resp.getContentType());
    }

    /* ---------------------------------------------------------------- */
    /* GET /rest/S-124/xsds/{file}                                      */
    /* ---------------------------------------------------------------- */

    @Test
    void getBundledXsdReturns200WithSchemaContent() {
        Response resp = given().when().get(XSDS + "124_2.0.0").then().extract().response();

        assertEquals(200, resp.statusCode(), "the bundled 124_2.0.0.xsd must be served");
        // S124-1: the XSD is served as text/xml (matching the endpoint's @GetMapping produces), not
        // the previous mismatched application/gml+xml.
        assertTrue(resp.getContentType().contains("text/xml"),
                "the bundled XSD must be served as text/xml, not application/gml+xml");
        String body = resp.getBody().asString();
        assertTrue(body.contains("schema"),
                "the returned XSD must contain an XML-schema definition");
        // It is valid XML.
        XmlAssert.parse(body);
    }

    @Test
    void getUnknownXsdReturns404() {
        given().when()
                .get(XSDS + "NoSuchSchema")
                .then()
                .statusCode(404)
                .body(org.hamcrest.Matchers.containsString("XSD does not exist"));
    }
}
