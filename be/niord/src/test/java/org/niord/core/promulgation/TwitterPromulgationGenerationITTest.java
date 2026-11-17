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
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.TwitterMessagePromulgationVo;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.TwitterApiStub;
import org.niord.test.http.HttpStub;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the non-wire behaviour of {@link TwitterPromulgationService}:
 *
 * <ul>
 *   <li>the {@code generateMessagePromulgation} title/promulgate rules,</li>
 *   <li>the {@code createSettings}/{@code updateSettings} persistence + duplicate/missing guards, and</li>
 *   <li>the <em>degraded</em> branches of {@code checkPromulgateMessage} that must <b>not</b> post
 *       anything to Twitter (status not PUBLISHED, invalid credentials, no settings).</li>
 * </ul>
 *
 * <p>This is the sibling of {@link TwitterPromulgationHttpITTest}, which asserts the single
 * end-to-end success path (a real status update reaches the stubbed Twitter API). This class
 * deliberately covers everything around that happy path. To assert the <em>negative</em>
 * promulgation cases we reuse the same {@link TwitterApiStub} wiring and assert that the
 * stub's recorded-request count is left unchanged: if the service had reached
 * {@code twitter.updateStatus(...)} a {@code statuses/update} POST would have been recorded.
 *
 * <p><b>Committed data, not {@code @Transactional}.</b> {@code checkPromulgateMessage} is
 * {@code @Async}: it runs on a worker thread that opens its own transaction and can only see
 * committed rows. So those tests create the {@link Message} and any {@link TwitterSettings} through
 * committed {@link Tx#requiringNew()} units of work, exactly like the sibling, and
 * {@link #cleanup()} removes everything created and restores the seeded {@code twitter} type's
 * original {@code active} flag so the shared seed database is left pristine.
 *
 * <p>{@code generateMessagePromulgation} performs no I/O and writes nothing, so those tests build
 * detached value objects and assert directly on the returned VO without touching the database.
 *
 * <p>{@code createSettings}/{@code updateSettings} commit in their own {@code @Transactional}
 * method; the created row is tracked and removed in {@link #cleanup()}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class TwitterPromulgationGenerationITTest extends ITTest {

    private static final String TWITTER = "twitter";

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
     * series inside the publishing transaction, and this test's publishes commit (in a committed
     * {@link Tx}), so their numbers stay taken; stamping a 2001 publish date keeps these publishes off
     * the current-year sequence other suites assert on.
     */
    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /**
     * A publish date far enough in the future that {@code ScheduledMessageService} cannot reach it.
     * That job's {@code checkForPublishableMessages} cron ({@code "7 * * * * ?"}) fires once a minute
     * and system-publishes <em>every</em> VERIFIED message whose {@code publishDateFrom} is in the past.
     * A message left at VERIFIED with {@link #pastPublishDate()} is therefore a live race: if the tick
     * lands inside the test's settle window the message is published behind the test's back, the JMS
     * status broadcast reaches {@code TwitterMessageListener}, and the tweet this test asserts must
     * never be sent is sent after all. So only the messages this test actually walks to PUBLISHED get
     * the past date (to stay off the current-year number sequence); one that must stay VERIFIED gets
     * a future date, which the job's {@code publishDateFrom <= now} search can never match.
     *
     * <p>Year 2999 matches {@code ScheduledMessageServiceITTest.FUTURE_PUBLISH_DATE}, which already uses
     * that year for this same "not yet due, so the scheduler must leave it alone" idiom — one greppable
     * future year across the suite rather than two.
     */
    private static Date futurePublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2999, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    @AfterEach
    void cleanup() {
        if (createdMessageId != null) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", createdMessageId)
                        .executeUpdate();
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

    // ------------------------------------------------------------------------------------------- //
    // generateMessagePromulgation: the title -> promulgate/tweet rule
    // ------------------------------------------------------------------------------------------- //

    /** A detached promulgation type carrying just typeId + language + requirement. */
    private static PromulgationType detachedTwitterType(PromulgationType.Requirement requirement) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(TWITTER);
        vo.setServiceId(TWITTER);
        vo.setName("Twitter");
        // Leave language null so getLanguage(type) resolves to the configured default language and the
        // null-language message desc below matches regardless of the deployment's language config.
        vo.setRequirement(requirement);
        return new PromulgationType(vo);
    }

    /** A system message VO carrying a single description with the given (possibly blank/null) title. */
    private static SystemMessageVo messageWithTitle(String title) {
        SystemMessageVo message = new SystemMessageVo();
        MessageDescVo desc = new MessageDescVo();
        // A null language matches any requested language in ILocalizable.getDesc(...).
        desc.setLang(null);
        desc.setTitle(title);
        message.checkCreateDescs().add(desc);
        return message;
    }

    private static TwitterMessagePromulgationVo generate(TwitterPromulgationService service,
                                                         SystemMessageVo message,
                                                         PromulgationType type) throws Exception {
        BaseMessagePromulgationVo result = service.generateMessagePromulgation(message, type);
        assertNotNull(result, "generateMessagePromulgation must always return a promulgation VO");
        assertTrue(result instanceof TwitterMessagePromulgationVo,
                "generateMessagePromulgation must return a TwitterMessagePromulgationVo");
        return (TwitterMessagePromulgationVo) result;
    }

    @Test
    void generateMessagePromulgation_withTitle_setsPromulgateTrueAndTweetIsTitle() throws Exception {
        TwitterMessagePromulgationVo vo = generate(
                twitterPromulgationService,
                messageWithTitle("A title that becomes the tweet"),
                detachedTwitterType(PromulgationType.Requirement.OPTIONAL));

        assertTrue(vo.isPromulgate(),
                "a non-blank title must set promulgate=true");
        assertEquals("A title that becomes the tweet", vo.getTweet(),
                "the tweet must be taken verbatim from the message title");
    }

    @Test
    void generateMessagePromulgation_blankTitle_optionalType_noTweetAndNoPromulgate() throws Exception {
        // requirement = OPTIONAL (not MANDATORY) -> promulgate must be false, tweet must stay unset.
        TwitterMessagePromulgationVo vo = generate(
                twitterPromulgationService,
                messageWithTitle("   "),
                detachedTwitterType(PromulgationType.Requirement.OPTIONAL));

        assertFalse(vo.isPromulgate(),
                "a blank title with a non-MANDATORY type must leave promulgate=false");
        assertNull(vo.getTweet(),
                "a blank title must NOT produce a tweet");
    }

    @Test
    void generateMessagePromulgation_noDesc_optionalType_noTweetAndNoPromulgate() throws Exception {
        // No description at all -> getDesc(...) returns null -> title null -> same as blank title.
        TwitterMessagePromulgationVo vo = generate(
                twitterPromulgationService,
                new SystemMessageVo(),
                detachedTwitterType(PromulgationType.Requirement.DEFAULT));

        assertFalse(vo.isPromulgate(),
                "a missing description with a non-MANDATORY type must leave promulgate=false");
        assertNull(vo.getTweet(),
                "a missing description must NOT produce a tweet");
    }

    @Test
    void generateMessagePromulgation_blankTitle_mandatoryType_promulgatesWithoutTweet() throws Exception {
        // requirement = MANDATORY -> promulgate becomes true even though there is no tweet text.
        TwitterMessagePromulgationVo vo = generate(
                twitterPromulgationService,
                messageWithTitle(""),
                detachedTwitterType(PromulgationType.Requirement.MANDATORY));

        assertTrue(vo.isPromulgate(),
                "a blank title with a MANDATORY type must still set promulgate=true");
        assertNull(vo.getTweet(),
                "a MANDATORY promulgation with a blank title must NOT carry a tweet");
    }

    // ------------------------------------------------------------------------------------------- //
    // createSettings / updateSettings: persistence + duplicate/missing guards
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached TwitterSettings template bound to the seeded twitter type. */
    private static TwitterSettings settingsTemplate(String apiKey,
                                                    String apiSecret,
                                                    String accessToken,
                                                    String accessTokenSecret,
                                                    String format,
                                                    Boolean includeThumbnail) {
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(TWITTER);
        TwitterSettings settings = new TwitterSettings();
        settings.setPromulgationType(new PromulgationType(typeVo));
        settings.setApiKey(apiKey);
        settings.setApiSecret(apiSecret);
        settings.setAccessToken(accessToken);
        settings.setAccessTokenSecret(accessTokenSecret);
        settings.setFormat(format);
        settings.setIncludeThumbnail(includeThumbnail);
        return settings;
    }

    @Test
    void createSettings_persistsRowFindableByTypeWithPersistedFields() throws Exception {
        TwitterSettings created = twitterPromulgationService.createSettings(
                settingsTemplate("IT-key", "IT-secret", "IT-token", "IT-token-secret",
                        "${tweet}", Boolean.TRUE));
        createdSettingsId = created.getId();
        assertNotNull(createdSettingsId, "createSettings must return a persisted (identified) entity");

        // Reload independently from the database and assert the actual persisted state.
        Tx.requiringNew().run(() -> {
            TwitterSettings reloaded = em.find(TwitterSettings.class, createdSettingsId);
            assertNotNull(reloaded, "the created settings row must exist in the database");
            assertEquals("IT-key", reloaded.getApiKey(), "apiKey must be persisted");
            assertEquals("IT-secret", reloaded.getApiSecret(), "apiSecret must be persisted");
            assertEquals("IT-token", reloaded.getAccessToken(), "accessToken must be persisted");
            assertEquals("IT-token-secret", reloaded.getAccessTokenSecret(),
                    "accessTokenSecret must be persisted");
            assertEquals("${tweet}", reloaded.getFormat(), "format must be persisted");
            assertEquals(Boolean.TRUE, reloaded.getIncludeThumbnail(), "includeThumbnail must be persisted");
            assertEquals(TWITTER, reloaded.getPromulgationType().getTypeId(),
                    "createSettings must bind the row to the seeded twitter promulgation type");
        });

        // The service's own lookup must also find it.
        TwitterSettings found = twitterPromulgationService.getSettings(TWITTER);
        assertNotNull(found, "getSettings must find the row createSettings persisted");
        assertEquals(createdSettingsId, found.getId(), "getSettings must return the row just created");
    }

    @Test
    void createSettings_whenSettingsAlreadyExist_throwsIllegalArgumentAndDoesNotInsertSecondRow() throws Exception {
        TwitterSettings first = twitterPromulgationService.createSettings(
                settingsTemplate("k1", "s1", "t1", "ts1", "${tweet}", Boolean.FALSE));
        createdSettingsId = first.getId();
        assertNotNull(createdSettingsId, "the first createSettings call must persist a row");

        // A second create for the same type must be rejected.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                twitterPromulgationService.createSettings(
                        settingsTemplate("k2", "s2", "t2", "ts2", "${tweet}", Boolean.TRUE)),
                "createSettings must reject a second settings row for the same promulgation type");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains(TWITTER),
                "the rejection message should name the offending promulgation type; was: " + ex.getMessage());

        // Confirm no second row was inserted and the first row is untouched.
        Tx.requiringNew().run(() -> {
            long count = em.createQuery(
                    "select count(t) from TwitterSettings t where t.promulgationType.typeId = :typeId", Long.class)
                    .setParameter("typeId", TWITTER)
                    .getSingleResult();
            assertEquals(1L, count, "exactly one TwitterSettings row must exist after the rejected create");
            TwitterSettings reloaded = em.find(TwitterSettings.class, createdSettingsId);
            assertNotNull(reloaded, "the original row must still exist");
            assertEquals("k1", reloaded.getApiKey(),
                    "the rejected create must not have overwritten the original row");
        });
    }

    @Test
    void updateSettings_whenSettingsExist_updatesFieldsButKeepsType() throws Exception {
        TwitterSettings created = twitterPromulgationService.createSettings(
                settingsTemplate("old-key", "old-secret", "old-token", "old-token-secret",
                        "old-format", Boolean.FALSE));
        createdSettingsId = created.getId();
        assertNotNull(createdSettingsId, "createSettings must persist the row that updateSettings updates");

        TwitterSettings updated = twitterPromulgationService.updateSettings(
                settingsTemplate("new-key", "new-secret", "new-token", "new-token-secret",
                        "new-format", Boolean.TRUE));
        assertEquals(createdSettingsId, updated.getId(),
                "updateSettings must update the existing row in place, not create a new one");

        // Reload independently and assert the new state.
        Tx.requiringNew().run(() -> {
            TwitterSettings reloaded = em.find(TwitterSettings.class, createdSettingsId);
            assertNotNull(reloaded, "the row must still exist after updateSettings");
            assertEquals("new-key", reloaded.getApiKey(), "apiKey must be updated");
            assertEquals("new-secret", reloaded.getApiSecret(), "apiSecret must be updated");
            assertEquals("new-token", reloaded.getAccessToken(), "accessToken must be updated");
            assertEquals("new-token-secret", reloaded.getAccessTokenSecret(),
                    "accessTokenSecret must be updated");
            assertEquals("new-format", reloaded.getFormat(), "format must be updated");
            assertEquals(Boolean.TRUE, reloaded.getIncludeThumbnail(), "includeThumbnail must be updated");
            assertEquals(TWITTER, reloaded.getPromulgationType().getTypeId(),
                    "updateSettings must leave the promulgation type unchanged");

            long count = em.createQuery(
                    "select count(t) from TwitterSettings t where t.promulgationType.typeId = :typeId", Long.class)
                    .setParameter("typeId", TWITTER)
                    .getSingleResult();
            assertEquals(1L, count, "updateSettings must not create a second row");
        });
    }

    @Test
    void updateSettings_whenNoSettingsExist_throwsIllegalArgumentAndInsertsNothing() {
        // The seed contains no TwitterSettings for the twitter type, so updateSettings must reject.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                twitterPromulgationService.updateSettings(
                        settingsTemplate("k", "s", "t", "ts", "${tweet}", Boolean.FALSE)),
                "updateSettings must reject an update when no settings exist for the type");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains(TWITTER),
                "the rejection message should name the promulgation type; was: " + ex.getMessage());

        // Confirm nothing was inserted as a side effect.
        Tx.requiringNew().run(() -> {
            long count = em.createQuery(
                    "select count(t) from TwitterSettings t where t.promulgationType.typeId = :typeId", Long.class)
                    .setParameter("typeId", TWITTER)
                    .getSingleResult();
            assertEquals(0L, count, "a rejected updateSettings must not insert a settings row");
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // checkPromulgateMessage: degraded branches must NOT post to Twitter
    // ------------------------------------------------------------------------------------------- //

    /**
     * Activates the seeded {@code twitter} type (recording the previous flag so it can be restored).
     * Runs in its own committed transaction so the async worker thread can see the flag.
     */
    private void activateSeededTwitterType() {
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
    }

    /** Persists a committed TwitterSettings whose credentials are INVALID (one OAuth field blank). */
    private void seedInvalidCredentialsSettings() {
        Tx.requiringNew().run(() -> {
            PromulgationType type = promulgationTypeService.getPromulgationType(TWITTER);
            TwitterSettings settings = new TwitterSettings();
            settings.setPromulgationType(type);
            settings.setApiKey("it-api-key");
            settings.setApiSecret("");            // blank -> credentialsValid() == false
            settings.setAccessToken("it-access-token");
            settings.setAccessTokenSecret("it-access-token-secret");
            settings.setIncludeThumbnail(Boolean.FALSE);
            settings.setFormat("${tweet}");
            em.persist(settings);
            em.flush();
            createdSettingsId = settings.getId();
        });
        assertNotNull(createdSettingsId, "the invalid-credentials Twitter settings row must be committed");
    }

    /**
     * Creates a committed dma-nw message carrying an active {@link TwitterMessagePromulgation}
     * ({@code promulgate=true}, non-blank tweet) bound to the seeded {@code twitter} type, walked to
     * the given target status. No geometry is attached.
     *
     * <p>The publish date is chosen by target status: PUBLISHED messages get {@link #pastPublishDate()}
     * so their number comes off the 2001 sequence, while a message that must <em>stay</em> VERIFIED gets
     * {@link #futurePublishDate()} so the once-a-minute auto-publish job cannot publish it mid-test.
     * The split is load-bearing, not stylistic: a future date is not an option for the PUBLISHED
     * fixtures because {@code MessageService.updateStatus} stomps a future {@code publishDateFrom} back
     * to {@code now} when it publishes, which would put their numbers on the current-year sequence other
     * suites assert starts at 1. Past-for-PUBLISHED and future-for-VERIFIED is the only stable pairing.
     */
    private String createCommittedTwitterMessage(Status targetStatus) throws Exception {
        Message draft = new Message();
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(Type.COASTAL_WARNING.getMainType());
        draft.setMessageSeries(series);
        draft.setType(Type.COASTAL_WARNING);
        draft.setAutoTitle(false);
        draft.setPublishDateFrom(
                targetStatus == Status.PUBLISHED ? pastPublishDate() : futurePublishDate());

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        draft.addPart(part);
        MessageDesc d = draft.createDesc("en");
        d.setTitle("Twitter generation IT");

        Message created = messageService.createMessage(draft);
        createdMessageId = created.getId();
        String uid = created.getUid();

        Tx.requiringNew().run(() -> {
            Message m = em.find(Message.class, createdMessageId);
            PromulgationType type = promulgationTypeService.getPromulgationType(TWITTER);

            TwitterMessagePromulgation promulgation = new TwitterMessagePromulgation();
            promulgation.setType(type);
            promulgation.setPromulgate(true);
            promulgation.setTweet("IT no-op tweet");
            m.addPromulgation(promulgation);
            em.merge(m);
        });

        // Walk through the legal lifecycle up to the requested target status. The walk to PUBLISHED is
        // ONE committed transaction: the auto-publish job only sees committed state, so the atomic
        // DRAFT -> VERIFIED -> PUBLISHED walk never exposes the committed VERIFIED + past-date row the
        // job could seize between two separate commits (losing that race fails the test's own publish
        // with "Invalid status transition PUBLISHED -> PUBLISHED"). The stay-VERIFIED fixture commits
        // alone; its future publish date keeps the job away for as long as the fixture lives.
        if (targetStatus == Status.VERIFIED) {
            messageService.updateStatus(uid, Status.VERIFIED);
        } else if (targetStatus == Status.PUBLISHED) {
            Tx.requiringNew().run(() -> {
                messageService.updateStatus(uid, Status.VERIFIED);
                messageService.updateStatus(uid, Status.PUBLISHED);
            });
        }
        return uid;
    }

    /**
     * Runs the async promulgation and asserts that, after a bounded settle window, the stub recorded
     * NO new requests beyond {@code baseline} — i.e. the degraded branch returned before any tweet.
     */
    private void assertNoTwitterRequest(HttpStub stub, String uid, int baseline) {
        twitterPromulgationService.checkPromulgateMessage(uid);
        // Give the async worker ample time to run and (wrongly) post, if it were going to.
        settle(2_000);
        assertEquals(baseline, stub.requestCount(),
                "no request must reach the Twitter API on the degraded path; recorded paths were "
                        + stub.requests().stream().map(HttpStub.RecordedRequest::path).toList());
    }

    @Test
    void checkPromulgateMessage_whenStatusVerifiedNotPublished_postsNothing() throws Exception {
        activateSeededTwitterType();
        // Valid settings exist, but the only thing missing is PUBLISHED status.
        seedValidCredentialsSettings();
        String uid = createCommittedTwitterMessage(Status.VERIFIED);

        HttpStub stub = TwitterApiStub.stub();
        assertNotNull(stub, "the Twitter API stub must be running for this test");
        int before = stub.requestCount();

        assertNoTwitterRequest(stub, uid, before);
    }

    @Test
    void checkPromulgateMessage_whenPublishedButCredentialsInvalid_postsNothing() throws Exception {
        activateSeededTwitterType();
        seedInvalidCredentialsSettings();
        String uid = createCommittedTwitterMessage(Status.PUBLISHED);

        HttpStub stub = TwitterApiStub.stub();
        assertNotNull(stub, "the Twitter API stub must be running for this test");
        int before = stub.requestCount();

        assertNoTwitterRequest(stub, uid, before);
    }

    @Test
    void checkPromulgateMessage_whenPublishedButNoSettings_postsNothing() throws Exception {
        activateSeededTwitterType();
        // Deliberately do NOT seed any TwitterSettings: getSettings(type) returns null.
        String uid = createCommittedTwitterMessage(Status.PUBLISHED);

        // Guard the assumption: the degraded path under test is "no settings".
        assertNull(twitterPromulgationService.getSettings(TWITTER),
                "this test requires that no TwitterSettings exist for the twitter type");

        HttpStub stub = TwitterApiStub.stub();
        assertNotNull(stub, "the Twitter API stub must be running for this test");
        int before = stub.requestCount();

        assertNoTwitterRequest(stub, uid, before);
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Persists a committed TwitterSettings whose four OAuth credentials are all valid. */
    private void seedValidCredentialsSettings() {
        Tx.requiringNew().run(() -> {
            PromulgationType type = promulgationTypeService.getPromulgationType(TWITTER);
            TwitterSettings settings = new TwitterSettings();
            settings.setPromulgationType(type);
            settings.setApiKey("it-api-key");
            settings.setApiSecret("it-api-secret");
            settings.setAccessToken("it-access-token");
            settings.setAccessTokenSecret("it-access-token-secret");
            settings.setIncludeThumbnail(Boolean.FALSE);
            settings.setFormat("${tweet}");
            em.persist(settings);
            em.flush();
            createdSettingsId = settings.getId();
        });
        assertNotNull(createdSettingsId, "the valid-credentials Twitter settings row must be committed");
    }

    /** Sleeps for the given number of milliseconds, restoring the interrupt flag on interruption. */
    private static void settle(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
