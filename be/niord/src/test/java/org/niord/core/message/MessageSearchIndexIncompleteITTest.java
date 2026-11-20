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

import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.niord.core.settings.SettingsService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A free-text search answered while the Lucene index is still being filled after a start (be/docs/potential-errors.md,
 * LUCENE-7). The owner's decision of 2026-11-19: the search keeps answering as it does, and its result is marked
 * ({@link PagedSearchResultVo#getIndexIncomplete()}) so the editors' list can warn that messages may be missing; the
 * public API answers exactly as before. The print of a list made in the window says so in a line at its top.
 *
 * <p>The window is made by marking this context's index as not complete, with its rows indexed: what is tested is the
 * mark, not what a half-filled index finds. The index's writer lock is held meanwhile, so none of its passes (second
 * 38 of every minute) runs and marks it complete again. The messages are committed (the index reads the database and
 * an HTTP request runs in a transaction of its own) and removed afterwards; {@code disableLucene} is set back to
 * {@code false}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageSearchIndexIncompleteITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSearchService messageSearchService;

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

    @Autowired
    SettingsService settingsService;

    @PersistenceContext
    EntityManager em;

    private final List<Integer> createdMessageIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        try {
            settingsService.set(MessagePostgresIndex.DISABLE_LUCENE_SETTING, false);
        } catch (Exception ignored) {
            // best effort
        }
        for (Integer id : createdMessageIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
        }
        createdMessageIds.clear();
        try {
            messageLuceneIndex.recreateIndex();
        } catch (Exception ignored) {
            // best effort: the next pass drops the removed rows anyway
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixtures
    // ------------------------------------------------------------------------------------------- //

    /** A globally unique, purely alphabetic token (digits tokenize differently in the two backends) */
    private static String uniqueToken() {
        long n = System.nanoTime();
        StringBuilder sb = new StringBuilder("zzidx");
        do {
            sb.append((char) ('a' + (int) (n % 26)));
            n /= 26;
        } while (n > 0);
        return sb.toString();
    }

    /** A past publish date, so the publications here take numbers off the current year's sequence */
    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** A committed, PUBLISHED coastal warning in dma-nw whose title holds the token */
    private Message createPublished(String token) throws Exception {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate());
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        msg.createDesc("en").setTitle("Wreck " + token);
        Message created = messageService.createMessage(msg);
        createdMessageIds.add(created.getId());
        return Tx.requiringNew().call(() -> {
            messageService.updateStatus(created.getUid(), Status.VERIFIED);
            return messageService.updateStatus(created.getUid(), Status.PUBLISHED);
        });
    }

    /** This context's index itself, past its proxy */
    private MessageLuceneIndex index() {
        return (MessageLuceneIndex) AopTestUtils.getUltimateTargetObject(messageLuceneIndex);
    }

    /**
     * Runs the work with this context's index marked as complete or not, its writer lock held so that no pass
     * changes the mark meanwhile; the mark is put back afterwards
     */
    private <T> T withIndexComplete(boolean complete, Callable<T> work) throws Exception {
        MessageLuceneIndex index = index();
        ReentrantLock lock = MessageLuceneIndex.writerLock(index.indexFolder);
        lock.lock();
        boolean was = index.allMessagesIndexed;
        try {
            index.allMessagesIndexed = complete;
            return work.call();
        } finally {
            index.allMessagesIndexed = was;
            lock.unlock();
        }
    }

    private static MessageSearchParams freeText(String token) {
        return new MessageSearchParams().query(token).language("en");
    }

    // ------------------------------------------------------------------------------------------- //
    // The search service
    // ------------------------------------------------------------------------------------------- //

    /**
     * During the refill a free-text search answers as before (the indexed message is found) and its result is
     * marked, through each way into the search: the one that swallows a failure, the one that lets it through, and
     * the paged ids. FAILS without the mark: the result carries none.
     */
    @Test
    void aFreeTextSearchDuringTheRefillAnswersAsBeforeAndIsMarked() throws Exception {
        String token = uniqueToken();
        Message message = createPublished(token);
        messageLuceneIndex.recreateIndex();

        withIndexComplete(false, () -> {
            PagedSearchResultVo<Message> orFail = messageSearchService.searchOrFail(freeText(token));
            assertEquals(List.of(message.getId()), orFail.getData().stream().map(Message::getId).toList(),
                    "the search answers as it does today");
            assertEquals(Boolean.TRUE, orFail.getIndexIncomplete(), "and says that the index is not complete");

            assertEquals(Boolean.TRUE, messageSearchService.search(freeText(token)).getIndexIncomplete(),
                    "the search that swallows a failure says so too");

            PagedSearchResultVo<Message> paged = new PagedSearchResultVo<>();
            List<Integer> ids = messageSearchService.searchPagedMessageIds(freeText(token), paged);
            assertEquals(List.of(message.getId()), ids);
            assertEquals(Boolean.TRUE, paged.getIndexIncomplete(), "and so does the paged-ids path");
            return null;
        });
    }

    /** Once the index is complete, the same search carries no mark */
    @Test
    void aFreeTextSearchAfterTheRefillIsNotMarked() throws Exception {
        String token = uniqueToken();
        Message message = createPublished(token);
        messageLuceneIndex.recreateIndex();

        withIndexComplete(true, () -> {
            PagedSearchResultVo<Message> result = messageSearchService.searchOrFail(freeText(token));
            assertEquals(List.of(message.getId()), result.getData().stream().map(Message::getId).toList());
            assertNull(result.getIndexIncomplete(), "a search over the complete index is not marked");
            return null;
        });
    }

    /**
     * A re-index that empties the index while a search runs: the index was complete when the search began and is not
     * when its languages have been searched. The search reads the mark again after them and is marked. Made with a
     * stand-in for the index in this context's search service, put back afterwards. FAILS with one read before the
     * search: the result carries no mark although it was answered from the emptied index.
     */
    @Test
    void aFreeTextSearchDuringWhichTheIndexIsEmptiedIsMarked() throws Exception {
        String token = uniqueToken();
        Message message = createPublished(token);
        MessageSearchService service = (MessageSearchService) AopTestUtils.getUltimateTargetObject(messageSearchService);
        MessageLuceneIndex real = service.messageLuceneIndex;
        MessageLuceneIndex emptiedDuringTheSearch = Mockito.mock(MessageLuceneIndex.class);
        Mockito.when(emptiedDuringTheSearch.allMessagesIndexed()).thenReturn(true, false);
        Mockito.when(emptiedDuringTheSearch.searchIndex(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(),
                Mockito.anyBoolean())).thenReturn(List.of(message.getId().longValue()));
        service.messageLuceneIndex = emptiedDuringTheSearch;
        try {
            PagedSearchResultVo<Message> result = messageSearchService.searchOrFail(freeText(token));
            assertEquals(List.of(message.getId()), result.getData().stream().map(Message::getId).toList(),
                    "the search answers as it does today");
            assertEquals(Boolean.TRUE, result.getIndexIncomplete(),
                    "and says that the index was not complete when it was searched");
        } finally {
            service.messageLuceneIndex = real;
        }
    }

    /** A search without free text does not read the index, and is not marked during the refill */
    @Test
    void aSearchWithoutFreeTextDuringTheRefillIsNotMarked() throws Exception {
        Message message = createPublished(uniqueToken());

        withIndexComplete(false, () -> {
            PagedSearchResultVo<Message> result = messageSearchService.searchOrFail(new MessageSearchParams()
                    .seriesIds(Set.of("dma-nw"))
                    .statuses(Set.of(Status.PUBLISHED)));
            assertTrue(result.getData().stream().anyMatch(m -> m.getId().equals(message.getId())));
            assertNull(result.getIndexIncomplete(), "no free text, no mark");
            return null;
        });
    }

    /** The PostgreSQL backend keeps its table across a start: a free-text search answered by it is not marked */
    @Test
    void aFreeTextSearchAnsweredByThePostgresBackendIsNotMarked() throws Exception {
        String token = uniqueToken();
        createPublished(token);
        assertNotNull(settingsService.getSetting(MessagePostgresIndex.DISABLE_LUCENE_SETTING),
                "precondition: start-up added the disableLucene row");
        settingsService.set(MessagePostgresIndex.DISABLE_LUCENE_SETTING, true);

        withIndexComplete(false, () -> {
            assertNull(messageSearchService.searchOrFail(freeText(token)).getIndexIncomplete(),
                    "the Lucene index's state does not mark an answer of the PostgreSQL backend");
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // Over HTTP: the editors' search and the public API
    // ------------------------------------------------------------------------------------------- //

    /**
     * The editors' search endpoint carries the mark in its JSON during the refill, with the messages found, and
     * leaves the property out once the index is complete: an ordinary answer is what it was.
     */
    @Test
    void theEditorsSearchCarriesTheMarkDuringTheRefillAndLeavesItOutAfter() throws Exception {
        String token = uniqueToken();
        Message message = createPublished(token);
        messageLuceneIndex.recreateIndex();

        withIndexComplete(false, () -> {
            given().queryParam("query", token).queryParam("lang", "en")
                    .when().get("/rest/messages/search")
                    .then().statusCode(200)
                    .body("data.id", hasItem(message.getUid()))
                    .body("indexIncomplete", is(true));
            return null;
        });

        withIndexComplete(true, () -> {
            given().queryParam("query", token).queryParam("lang", "en")
                    .when().get("/rest/messages/search")
                    .then().statusCode(200)
                    .body("data.id", hasItem(message.getUid()))
                    .body("$", not(hasKey("indexIncomplete")));
            return null;
        });
    }

    /**
     * The print of a list during the refill (LUCENE-7): the PDF is still made, and the HTML it is rendered from (the
     * debug answer of a signed-in caller) carries the line that says messages may be missing, at the top of the
     * document, with the message found; once the index is complete the same print has no such line. FAILS without
     * the line: the print of the marked search reads as a complete one.
     */
    @Test
    void thePrintOfAListDuringTheRefillCarriesTheLineAtItsTop() throws Exception {
        String token = uniqueToken();
        createPublished(token);
        messageLuceneIndex.recreateIndex();

        withIndexComplete(false, () -> {
            String html = given().auth().oauth2(accessToken("sysadmin"))
                    .queryParam("query", token).queryParam("lang", "en").queryParam("debug", true)
                    .when().get("/rest/message-reports/report.pdf")
                    .then().statusCode(200)
                    .extract().asString();
            assertTrue(html.contains(IndexIncompleteNotice.BUILT_IN_TEXT), "the print says messages may be missing");
            assertTrue(html.indexOf(IndexIncompleteNotice.BUILT_IN_TEXT) < html.indexOf("Wreck " + token),
                    "at the top, above the messages it found");

            given().auth().oauth2(accessToken("sysadmin"))
                    .queryParam("query", token).queryParam("lang", "en")
                    .when().get("/rest/message-reports/report.pdf")
                    .then().statusCode(200)
                    .contentType("application/pdf");
            return null;
        });

        withIndexComplete(true, () -> {
            String html = given().auth().oauth2(accessToken("sysadmin"))
                    .queryParam("query", token).queryParam("lang", "en").queryParam("debug", true)
                    .when().get("/rest/message-reports/report.pdf")
                    .then().statusCode(200)
                    .extract().asString();
            assertTrue(html.contains("Wreck " + token), "the print holds the message");
            assertFalse(html.contains(IndexIncompleteNotice.BUILT_IN_TEXT), "and no line once the index is complete");
            return null;
        });
    }

    /**
     * The public API answers during the refill exactly as before: a JSON list of messages, without the property
     * anywhere in it. (Its search takes no free text, so the index is not read; the list is serialized on its own.)
     */
    @Test
    void thePublicApiAnswersAsBeforeDuringTheRefill() throws Exception {
        Message message = createPublished(uniqueToken());

        withIndexComplete(false, () -> {
            String body = given().queryParam("messageSeries", "dma-nw").queryParam("lang", "en")
                    .when().get("/rest/public/v1/messages")
                    .then().statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("$", instanceOf(List.class))
                    .body("id", hasItem(message.getUid()))
                    .extract().asString();
            assertFalse(body.contains("indexIncomplete"), "the public API's answer carries no new property");
            return null;
        });
    }
}
