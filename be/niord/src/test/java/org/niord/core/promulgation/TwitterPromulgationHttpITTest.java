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
package org.niord.core.promulgation;

import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import org.niord.test.TwitterApiStub;
import org.niord.test.http.HttpStub;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test that drives a real Twitter promulgation all the way out onto the wire
 * and asserts on the HTTP request the twitter4j client actually transmits.
 *
 * <p>This is the only test that exercises the success path of
 * {@link TwitterPromulgationService#checkPromulgateMessage(String)} →
 * {@code promulgateMessage} → {@code twitter.updateStatus(...)}. Every other promulgation test stops
 * at the degraded branches (no settings, invalid credentials), because there was previously no way to
 * stand up the remote endpoint the client posts to. {@link TwitterApiStub} closes that gap: it
 * starts a local {@link HttpStub} and sets the {@code twitter4j.restBaseURL} system property, so the
 * real client POSTs its status update to the stub. We then assert that a {@code POST} to a
 * {@code statuses/update} path was received carrying the expected tweet text.
 *
 * <p><b>Committed data, not {@code @Transactional}.</b> {@code checkPromulgateMessage} is
 * {@code @Async}: it runs on a separate worker thread that opens its own transaction and can only see
 * rows that have actually been committed. So this test creates the promulgation type activation, the
 * {@link TwitterSettings} and the {@link Message} through committed
 * {@link Tx#requiringNew()} units of work, exactly like the committed-data Lucene and
 * mail-delivery ITs. {@link #cleanup()} then removes everything it created and restores the seeded
 * {@code twitter} type's original {@code active} flag, so the shared seed database is left pristine
 * and the test stays independent and repeatable.
 *
 * <p>The conditions {@code promulgateMessage} requires are all satisfied: the message is
 * {@code PUBLISHED}; it carries a {@link TwitterMessagePromulgation} with {@code promulgate=true} and a
 * non-blank tweet; the {@code twitter} {@link PromulgationType} is active; and a {@link TwitterSettings}
 * whose {@code credentialsValid()} is true (all four OAuth fields non-blank) exists for that type. The
 * message is given <em>no</em> geometry, so {@code computeLocation(...)} returns {@code null} and the
 * media-upload branch (which would fetch a map image over HTTP) is skipped — keeping the single
 * recorded request the status update itself.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class TwitterPromulgationHttpITTest extends ITTest {

    private static final String TWITTER = "twitter";
    private static final String TWEET_TEXT = "IT hello tweet";

    @Autowired
    TwitterPromulgationService twitterPromulgationService;

    @Autowired
    MessageService messageService;

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @PersistenceContext
    EntityManager em;

    /** Tracks committed rows so {@link #cleanup()} can remove them and keep the seed DB pristine. */
    private Integer createdMessageId;
    private Integer createdSettingsId;
    private boolean originalTypeActive;
    private boolean typeActiveChanged;

    /**
     * A fixed far-past publish date. Publishing takes a message number from the YEARLY sequence of the
     * series inside the publishing transaction, and this test's publish commits (in a committed
     * {@link Tx}), so its number stays taken; stamping a 2001 publish date keeps this test off the
     * current-year sequence other suites assert on.
     */
    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    @AfterEach
    void cleanup() {
        if (createdMessageId != null) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", createdMessageId)
                        .executeUpdate();
                // The Twitter promulgation row is owned by the message (cascade ALL + orphanRemoval),
                // so removing the message removes it too.
                Message m = em.find(Message.class, createdMessageId);
                if (m != null) {
                    em.remove(m);
                }
            });
            createdMessageId = null;
        }

        if (createdSettingsId != null) {
            Tx.requiringNew().run(() -> {
                TwitterSettings s = em.find(TwitterSettings.class, createdSettingsId);
                if (s != null) {
                    em.remove(s);
                }
            });
            createdSettingsId = null;
        }

        if (typeActiveChanged) {
            Tx.requiringNew().run(() -> {
                PromulgationType type = promulgationTypeService.getPromulgationType(TWITTER);
                if (type != null) {
                    type.setActive(originalTypeActive);
                    em.merge(type);
                }
            });
            typeActiveChanged = false;
        }
    }

    /**
     * Activates the seeded {@code twitter} promulgation type (recording the previous flag so it can be
     * restored) and creates a {@link TwitterSettings} with all four OAuth credentials populated, so
     * {@code credentialsValid()} is true. Runs in its own committed transaction.
     */
    private void seedActiveTwitterTypeWithValidCredentials() {
        Tx.requiringNew().run(() -> {
            PromulgationType type = promulgationTypeService.getPromulgationType(TWITTER);
            assertNotNull(type, "the seed must contain a 'twitter' promulgation type");
            originalTypeActive = type.isActive();
            if (!type.isActive()) {
                type.setActive(true);
                em.merge(type);
                typeActiveChanged = true;
            }
        });

        Tx.requiringNew().run(() -> {
            PromulgationType type = promulgationTypeService.getPromulgationType(TWITTER);
            TwitterSettings settings = new TwitterSettings();
            settings.setPromulgationType(type);
            settings.setApiKey("it-api-key");
            settings.setApiSecret("it-api-secret");
            settings.setAccessToken("it-access-token");
            settings.setAccessTokenSecret("it-access-token-secret");
            settings.setIncludeThumbnail(Boolean.FALSE);
            // A simple format that just emits the tweet verbatim, so the recorded body is predictable.
            settings.setFormat("${tweet}");
            em.persist(settings);
            em.flush();
            createdSettingsId = settings.getId();
        });

        assertNotNull(createdSettingsId, "the Twitter settings row must have been committed");
    }

    /**
     * Creates a committed, PUBLISHED dma-nw message carrying an active {@link TwitterMessagePromulgation}
     * ({@code promulgate=true}, non-blank tweet) bound to the seeded {@code twitter} type, and returns
     * its uid. No geometry is attached, so the tweet has no location and no media is uploaded.
     */
    private String createCommittedPublishedTwitterMessage() throws Exception {
        // 1) Create the draft (createMessage commits when run outside an enclosing transaction).
        Message draft = new Message();
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(Type.COASTAL_WARNING.getMainType());
        draft.setMessageSeries(series);
        draft.setType(Type.COASTAL_WARNING);
        draft.setAutoTitle(false);
        draft.setPublishDateFrom(pastPublishDate());

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        draft.addPart(part);
        MessageDesc d = draft.createDesc("en");
        d.setTitle("Twitter HTTP IT");

        Message created = messageService.createMessage(draft);
        createdMessageId = created.getId();
        String uid = created.getUid();

        // 2) Attach an active Twitter promulgation to the committed message in its own transaction.
        Tx.requiringNew().run(() -> {
            Message m = em.find(Message.class, createdMessageId);
            PromulgationType type = promulgationTypeService.getPromulgationType(TWITTER);

            TwitterMessagePromulgation promulgation = new TwitterMessagePromulgation();
            promulgation.setType(type);
            promulgation.setPromulgate(true);
            promulgation.setTweet(TWEET_TEXT);
            m.addPromulgation(promulgation);
            em.merge(m);
        });

        // 3) Walk the message to PUBLISHED in ONE committed transaction: the once-a-minute auto-publish
        // job only sees committed state, so the atomic walk never exposes a committed VERIFIED +
        // past-date row it could publish first (which would fail this walk with "Invalid status
        // transition PUBLISHED -> PUBLISHED").
        Tx.requiringNew().run(() -> {
            messageService.updateStatus(uid, Status.VERIFIED);
            messageService.updateStatus(uid, Status.PUBLISHED);
        });

        return uid;
    }

    // ------------------------------------------------------------------------------------------- //
    // The end-to-end success path: a PUBLISHED message is tweeted to the (stubbed) Twitter API.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void publishedMessageWithValidSettingsPostsStatusUpdateToTwitter() throws Exception {
        seedActiveTwitterTypeWithValidCredentials();
        String uid = createCommittedPublishedTwitterMessage();

        HttpStub stub = TwitterApiStub.stub();
        assertNotNull(stub, "the Twitter API stub must be running for this test");
        int before = stub.requestCount();

        // checkPromulgateMessage is @Async: it returns immediately and does the work on a worker
        // thread, so we kick it off and then poll the stub for the resulting status-update POST.
        twitterPromulgationService.checkPromulgateMessage(uid);
        awaitRequest(stub, before, 15_000);

        // If the async path did not deliver in time, give it one more push and a final wait window.
        if (stub.requestCount() <= before) {
            twitterPromulgationService.checkPromulgateMessage(uid);
            awaitRequest(stub, before, 15_000);
        }

        assertTrue(stub.requestCount() > before,
                "the Twitter promulgation must have POSTed a status update to the Twitter API stub");

        HttpStub.RecordedRequest statusUpdate = findStatusUpdate(stub, before);
        assertNotNull(statusUpdate,
                "a request to a 'statuses/update' path must have been recorded; recorded paths were "
                        + stub.requests().stream().map(HttpStub.RecordedRequest::path).toList());
        assertTrue("POST".equalsIgnoreCase(statusUpdate.method()),
                "the status update must be sent as an HTTP POST, was: " + statusUpdate.method());

        // twitter4j url-encodes the form body, so "IT hello tweet" arrives as "IT+hello+tweet".
        String body = statusUpdate.bodyString();
        assertTrue(body.contains("hello"),
                "the recorded request body must carry the tweet text; body was: " + body);
        assertTrue(body.contains("IT+hello+tweet") || body.contains("IT%20hello%20tweet"),
                "the recorded request body must carry the url-encoded tweet text; body was: " + body);
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Polls until the stub has recorded a request beyond {@code baseline}, or the timeout elapses. */
    private static void awaitRequest(HttpStub stub, int baseline, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (stub.requestCount() > baseline) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Finds the first recorded request after {@code baseline} whose path targets a status update. */
    private static HttpStub.RecordedRequest findStatusUpdate(HttpStub stub, int baseline) {
        List<HttpStub.RecordedRequest> all = stub.requests();
        for (int i = baseline; i < all.size(); i++) {
            HttpStub.RecordedRequest r = all.get(i);
            if (r.path() != null && r.path().contains("statuses/update")) {
                return r;
            }
        }
        return null;
    }
}
