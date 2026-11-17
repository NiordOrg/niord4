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
package org.niord.core.message;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.promulgation.BaseMessagePromulgation;
import org.niord.core.promulgation.NavtexMessagePromulgation;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.core.user.TicketService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.model.search.PagedSearchParamsVo.SortOrder;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.core.message.MessageSearchParams.CommentsType.ANY;
import static org.niord.core.message.MessageSearchParams.CommentsType.ANY_UNACK;
import static org.niord.core.message.MessageSearchParams.CommentsType.OWN;
import static org.niord.core.message.MessageSearchParams.CommentsType.OWN_UNACK;
import static org.niord.core.message.MessageSearchParams.UserType.CREATED_BY;
import static org.niord.core.message.MessageSearchParams.UserType.LAST_UPDATED_BY;
import static org.niord.core.message.MessageSearchParams.UserType.UPDATED_BY;

/**
 * Integration tests for the {@link MessageService#search(MessageSearchParams)} /
 * {@code searchPagedMessageIds(...)} predicate branches that the sibling
 * {@code MessageSearchServiceITTest} does <em>not</em> cover:
 *
 * <ul>
 *   <li><b>Comments filter</b> ({@code OWN}, {@code OWN_UNACK}, {@code ANY}, {@code ANY_UNACK}) and the
 *       interaction with the current user resolved from the {@link TicketService} thread-local.</li>
 *   <li><b>Referenced-message recursion</b> — {@code findReferencedMessageIds} /
 *       {@code findReferencingMessageIds} reachable from {@code messageId} within a clamped
 *       {@code referenceLevels} (0..N).</li>
 *   <li><b>Area lineage-prefix matching</b> — a child-area message matched when searching by the
 *       ancestor area.</li>
 *   <li><b>Promulgation-type filter</b> — {@code promulgate=true} plus a {@code PromulgationType.typeId}
 *       in the requested set.</li>
 *   <li><b>Paging boundaries</b> — total count is computed before slicing and is independent of the page.</li>
 *   <li><b>User-via-{@code MessageHistory} filter</b> — the {@code UPDATED_BY} branch joins through
 *       {@code MessageHistory.user} rather than the denormalized {@code createdBy}/{@code lastUpdatedBy}.</li>
 * </ul>
 *
 * <p>Mirrors the in-transaction pattern of {@code MessageSearchServiceITTest}: no {@code Message} rows are
 * seeded, every test builds its own data with {@code createMessage(...)} (which flushes) and searches in the
 * SAME transaction, so the criteria query sees exactly the flushed-but-uncommitted rows. Each test is wrapped
 * in {@link TestTransaction} so its writes roll back, keeping the shared seed database pristine and the tests
 * order-independent.
 *
 * <p>The current user (needed by the comments filter and the user filter) is established the way the
 * production code resolves it when there is no Keycloak identity: a {@code User} row is persisted, a ticket is
 * issued for it, and the ticket is resolved onto the current thread so that
 * {@code UserService.currentUser()} returns it via {@code findByUsername(...)}. The thread-local is cleared in
 * {@link #clearTicket()} after every test.
 *
 * <p>Deliberately NOT covered (see test summary / skipped notes): the geometry/extent (MySQL spatial)
 * predicate and the free-text Lucene {@code query(...)} path, neither of which can be exercised reliably
 * against in-transaction, uncommitted rows.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageSearchFilterMatrixITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSearchService messageSearchService;

    @Autowired
    AreaService areaService;

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @Autowired
    UserService userService;

    @Autowired
    TicketService ticketService;

    @PersistenceContext
    EntityManager em;

    /** Always clear any thread-local ticket data set during a test so the current-user seam never leaks. */
    @AfterEach
    void clearTicket() {
        ticketService.removeTicketForCurrentThread();
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers (mirroring MessageSearchServiceITTest)
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached DRAFT message template - createMessage substitutes the persisted series. */
    private Message newDraft(String seriesId, Type type, String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries(seriesId));
        msg.setType(type);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Persists the given draft template and returns the managed message. */
    private Message create(Message draft) {
        try {
            return messageService.createMessage(draft);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Convenience: create a draft NW (COASTAL_WARNING) message in series dma-nw. */
    private Message createNw(String title) {
        return create(newDraft("dma-nw", Type.COASTAL_WARNING, title));
    }

    /** Runs the criteria search and returns the matched message ids in result order. */
    private List<Integer> searchIds(MessageSearchParams params) {
        return messageSearchService.search(params).getData().stream()
                .map(Message::getId)
                .collect(Collectors.toList());
    }

    /** Persists a user with a globally-unique username and returns the managed entity. */
    private User persistUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + "-" + UUID.randomUUID());
        u.setFirstName("F");
        u.setLastName("L");
        em.persist(u);
        em.flush();
        return u;
    }

    /**
     * Makes {@code userService.currentUser()} resolve to the given user for the rest of the test by issuing a
     * ticket for it and resolving that ticket onto the current thread - the same path the production code
     * uses for non-Keycloak (ticket) requests. Returns the resolved current user so the test can assert the
     * seam actually took effect.
     */
    private User becomeCurrentUser(User user) {
        String ticket = ticketService.createTicket(null, user);
        assertTrue(ticketService.resolveTicketForCurrentThread(ticket),
                "the freshly issued ticket must resolve onto the current thread");
        User current = userService.currentUser();
        assertNotNull(current, "currentUser() must resolve via the ticket thread-local");
        assertEquals(user.getId(), current.getId(),
                "the resolved current user must be the user the ticket was issued for");
        return current;
    }

    /** Persists a comment (optionally acknowledged) directly, mirroring CommentServiceITTest. */
    private Comment persistComment(Message message, User author, User acknowledgedBy) {
        Comment c = new Comment();
        c.setMessage(message);
        c.setUser(author);
        c.setComment("comment text");
        c.setCreated(new Date());
        if (acknowledgedBy != null) {
            c.setAcknowledgedBy(acknowledgedBy);
            c.setAcknowledgeDate(new Date());
        }
        em.persist(c);
        em.flush();
        return c;
    }

    // ------------------------------------------------------------------------------------------- //
    // Comments filter: ANY / ANY_UNACK (no current user required)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void commentsAnyMatchesMessagesWithAnyCommentAndExcludesUncommented() {
        Message commented = createNw("any-commented");
        Message uncommented = createNw("any-uncommented");
        User author = persistUser("author");
        persistComment(commented, author, null);

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .commentsType(ANY));

        assertTrue(ids.contains(commented.getId()),
                "ANY must match a message that has at least one comment");
        assertFalse(ids.contains(uncommented.getId()),
                "ANY must exclude a message with no comments (LEFT-join comment id is null)");
    }

    @Test
    @Transactional
    void commentsAnyUnackMatchesOnlyUnacknowledgedComments() {
        Message withUnack = createNw("anyunack-open");
        Message withAck = createNw("anyunack-acked");
        User author = persistUser("author");
        User acker = persistUser("acker");
        persistComment(withUnack, author, null);          // not acknowledged
        persistComment(withAck, author, acker);            // acknowledged

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .commentsType(ANY_UNACK));

        assertTrue(ids.contains(withUnack.getId()),
                "ANY_UNACK must match a message whose comment is not acknowledged");
        assertFalse(ids.contains(withAck.getId()),
                "ANY_UNACK must exclude a message whose only comment is acknowledged");
    }

    // ------------------------------------------------------------------------------------------- //
    // Comments filter: OWN / OWN_UNACK (current user drives the predicate)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void commentsOwnMatchesOnlyCurrentUsersComments() {
        Message mine = createNw("own-mine");
        Message theirs = createNw("own-theirs");
        User me = persistUser("me");
        User other = persistUser("other");
        persistComment(mine, me, null);
        persistComment(theirs, other, null);

        becomeCurrentUser(me);

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .commentsType(OWN));

        assertTrue(ids.contains(mine.getId()),
                "OWN must match a message commented on by the current user");
        assertFalse(ids.contains(theirs.getId()),
                "OWN must exclude a message commented on only by another user");
    }

    @Test
    @Transactional
    void commentsOwnUnackMatchesOnlyCurrentUsersUnacknowledgedComments() {
        Message mineOpen = createNw("ownunack-mine-open");
        Message mineAcked = createNw("ownunack-mine-acked");
        Message theirsOpen = createNw("ownunack-theirs-open");
        User me = persistUser("me");
        User other = persistUser("other");
        persistComment(mineOpen, me, null);               // mine, unacknowledged -> match
        persistComment(mineAcked, me, other);             // mine, acknowledged -> excluded
        persistComment(theirsOpen, other, null);          // not mine -> excluded

        becomeCurrentUser(me);

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .commentsType(OWN_UNACK));

        assertTrue(ids.contains(mineOpen.getId()),
                "OWN_UNACK must match my own unacknowledged comment");
        assertFalse(ids.contains(mineAcked.getId()),
                "OWN_UNACK must exclude my own comment once acknowledged");
        assertFalse(ids.contains(theirsOpen.getId()),
                "OWN_UNACK must exclude another user's unacknowledged comment");
    }

    @Test
    @Transactional
    void commentsOwnWithoutCurrentUserMatchesNothing() {
        // MSG3: with NO current user, a user-scoped "show only MY comments" filter cannot identify a user
        // and must fail closed (match nothing) rather than silently returning every message.
        Message commented = createNw("own-nouser-commented");
        Message uncommented = createNw("own-nouser-uncommented");
        User author = persistUser("author");
        persistComment(commented, author, null);

        // Deliberately do NOT call becomeCurrentUser(...): currentUser() is null here.
        assertNull(userService.currentUser(),
                "currentUser() must be null when no ticket/identity is established");

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .commentsType(OWN));

        // The always-false predicate excludes BOTH the commented and the uncommented message.
        assertFalse(ids.contains(commented.getId()),
                "with no current user the OWN filter matches nothing, so the commented message is excluded");
        assertFalse(ids.contains(uncommented.getId()),
                "with no current user the OWN filter matches nothing, so an uncommented message is excluded");
    }

    @Test
    @Transactional
    void commentsOwnUnackWithoutCurrentUserMatchesNothing() {
        // MSG3: OWN_UNACK fails closed too when there is no current user.
        Message withUnack = createNw("ownunack-nouser-unack");
        Message uncommented = createNw("ownunack-nouser-uncommented");
        User author = persistUser("author");
        persistComment(withUnack, author, null);          // an unacknowledged comment by some author

        assertNull(userService.currentUser(),
                "currentUser() must be null when no ticket/identity is established");

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .commentsType(OWN_UNACK));

        assertFalse(ids.contains(withUnack.getId()),
                "with no current user the OWN_UNACK filter matches nothing, so the commented message is excluded");
        assertFalse(ids.contains(uncommented.getId()),
                "with no current user the OWN_UNACK filter matches nothing, so an uncommented message is excluded");
    }

    // ------------------------------------------------------------------------------------------- //
    // Referenced-message recursion (messageId + referenceLevels)
    // ------------------------------------------------------------------------------------------- //

    /** Creates a published NW so it has a resolvable short id, then returns it. */
    private Message publishNw(String title) {
        try {
            Message draft = newDraft("dma-nw", Type.COASTAL_WARNING, title);
            draft.setPublishDateFrom(date2001());
            Message msg = create(draft);
            messageService.updateStatus(msg.getUid(), Status.VERIFIED);
            return messageService.updateStatus(msg.getUid(), Status.PUBLISHED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Date date2001() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2001, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Builds a draft that references the given target message uid with a REFERENCE-type reference. */
    private Message createReferencing(String title, String targetUid) {
        Message draft = newDraft("dma-nw", Type.COASTAL_WARNING, title);
        Reference ref = new Reference();
        ref.setMessageId(targetUid);
        ref.setType(ReferenceType.REFERENCE);
        draft.addReference(ref);
        return create(draft);
    }

    @Test
    @Transactional
    void referencedMessageSearchIncludesTheMessageItselfAndItsDirectReferences() {
        // A -> B (A references B). Searching by A's uid with the default levels (1) must return both A and
        // B: A because it is the message itself, B because it is referenced by A.
        Message b = createNw("ref-target-B");
        Message a = createReferencing("ref-source-A", b.getUid());
        em.flush();

        List<Integer> ids = searchIds(new MessageSearchParams().messageId(a.getUid()));

        assertTrue(ids.contains(a.getId()), "the referenced-message search must include the message itself");
        assertTrue(ids.contains(b.getId()), "the search must include a message referenced by the queried one");
    }

    @Test
    @Transactional
    void referencedMessageSearchIncludesReferencingMessages() {
        // A -> B. Searching by B's uid must also return A, because A *references* B
        // (findReferencingMessageIds walks the inverse direction via Message.findByReference).
        Message b = createNw("refing-target-B");
        Message a = createReferencing("refing-source-A", b.getUid());
        em.flush();

        List<Integer> ids = searchIds(new MessageSearchParams().messageId(b.getUid()));

        assertTrue(ids.contains(b.getId()), "the search must include the queried message itself");
        assertTrue(ids.contains(a.getId()),
                "the search must include a message that references the queried one");
    }

    @Test
    @Transactional
    void referenceLevelsZeroLimitsToTheMessageItself() {
        // A -> B with levels = 0: only A (the message itself) is returned; the recursion into A's references
        // and the inverse search are both gated behind levels > 0, so B must be excluded.
        Message b = createNw("level0-target-B");
        Message a = createReferencing("level0-source-A", b.getUid());
        em.flush();

        List<Integer> ids = searchIds(new MessageSearchParams()
                .messageId(a.getUid())
                .referenceLevels(0));

        assertTrue(ids.contains(a.getId()), "levels=0 must still return the queried message itself");
        assertFalse(ids.contains(b.getId()),
                "levels=0 must NOT follow references, so the referenced message is excluded");
    }

    @Test
    @Transactional
    void referenceLevelsTwoFollowsTransitiveReferences() {
        // A -> B -> C. Searching by A's uid with levels = 2 must reach C transitively (A, B and C),
        // whereas levels = 1 reaches only A and B.
        Message c = createNw("level2-C");
        Message b = createReferencing("level2-B", c.getUid());
        Message a = createReferencing("level2-A", b.getUid());
        em.flush();

        List<Integer> oneLevel = searchIds(new MessageSearchParams()
                .messageId(a.getUid())
                .referenceLevels(1));
        assertTrue(oneLevel.contains(a.getId()));
        assertTrue(oneLevel.contains(b.getId()), "levels=1 reaches the direct reference B");
        assertFalse(oneLevel.contains(c.getId()),
                "levels=1 must NOT reach the transitive reference C");

        List<Integer> twoLevels = searchIds(new MessageSearchParams()
                .messageId(a.getUid())
                .referenceLevels(2));
        assertTrue(twoLevels.contains(a.getId()));
        assertTrue(twoLevels.contains(b.getId()));
        assertTrue(twoLevels.contains(c.getId()),
                "levels=2 must follow the transitive A->B->C reference chain down to C");
    }

    @Test
    @Transactional
    void referenceLevelsAboveClampAreEquivalentToTheClampCeiling() {
        // levels is clamped to [0,5]. A->B->C with a huge requested level must behave like the ceiling and
        // still reach C (and no exception is thrown for the out-of-range request).
        Message c = createNw("clamp-C");
        Message b = createReferencing("clamp-B", c.getUid());
        Message a = createReferencing("clamp-A", b.getUid());
        em.flush();

        List<Integer> ids = searchIds(new MessageSearchParams()
                .messageId(a.getUid())
                .referenceLevels(99));
        assertTrue(ids.contains(a.getId()));
        assertTrue(ids.contains(b.getId()));
        assertTrue(ids.contains(c.getId()),
                "an over-large referenceLevels must be clamped (to 5) and still resolve the chain");
    }

    @Test
    @Transactional
    void referencedMessageSearchResolvesByShortId() {
        // The recursion resolves references by UID or short id; verify the short-id path. B is published so
        // it has a short id; A references B by that short id.
        Message b = publishNw("shortid-target-B");
        String shortId = b.getShortId();
        assertNotNull(shortId, "a published NW must have a short id");
        Message a = createReferencing("shortid-source-A", shortId);
        em.flush();

        // Search by B's short id: must include B itself and A (which references B by short id).
        List<Integer> ids = searchIds(new MessageSearchParams().messageId(shortId));
        assertTrue(ids.contains(b.getId()), "searching by short id must include the message with that short id");
        assertTrue(ids.contains(a.getId()),
                "searching by short id must include a message referencing it by short id");
    }

    @Test
    @Transactional
    void unknownReferencedMessageIdMatchesNothing() {
        createNw("unknown-ref-noise");
        PagedSearchResultVo<Message> result = messageSearchService.search(
                new MessageSearchParams().messageId("no-such-message-" + UUID.randomUUID()));
        assertEquals(0, result.getTotal(),
                "an unresolvable messageId yields an empty referenced-id set and matches nothing");
    }

    // ------------------------------------------------------------------------------------------- //
    // Area lineage-prefix matching (ancestor area matches child-area messages)
    // ------------------------------------------------------------------------------------------- //

    /**
     * Persists a two-level area hierarchy (parent -&gt; child) with the production lineage format
     * {@code /root-id/.../id/} so that {@code child.lineage} starts with {@code parent.lineage}. Built
     * directly via the {@link EntityManager} so the test fully controls the hierarchy and does not depend on
     * the (root-only) seeded areas. The areas roll back with the surrounding {@link TestTransaction}.
     *
     * @return {@code [parent, child]}
     */
    private Area[] persistAreaHierarchy() {
        Area parent = new Area();
        em.persist(parent);
        em.flush();                                   // assigns the parent id
        parent.setLineage("/" + parent.getId() + "/");
        em.flush();

        Area child = new Area();
        parent.addChild(child);                       // wires the parent/child relationship
        em.persist(child);
        em.flush();                                   // assigns the child id
        child.setLineage(parent.getLineage() + child.getId() + "/");
        em.flush();

        return new Area[]{parent, child};
    }

    @Test
    @Transactional
    void searchByAncestorAreaMatchesChildAreaMessageViaLineagePrefix() {
        // A message tagged with the CHILD area must be matched when searching by the PARENT (ancestor) area
        // id, because the predicate is like(area.lineage, parent.lineage + '%') and the child lineage is
        // prefixed by the parent lineage.
        Area[] hierarchy = persistAreaHierarchy();
        Area parent = hierarchy[0];
        Area child = hierarchy[1];
        String parentId = String.valueOf(parent.getId());

        // Sanity: findByAreaId(parentId) must resolve the parent, and the child lineage must be prefixed by
        // the parent lineage - exactly what the search predicate relies on.
        assertNotNull(areaService.findByAreaId(parentId), "the persisted parent area must be resolvable by id");
        assertTrue(child.getLineage().startsWith(parent.getLineage()),
                "the child area lineage must be prefixed by the parent area lineage");

        Message childAreaMsg = newDraft("dma-nw", Type.COASTAL_WARNING, "lineage-child");
        childAreaMsg.setAreas(List.of(child));
        childAreaMsg = create(childAreaMsg);

        Message noAreaMsg = createNw("lineage-noarea");

        // Searching by the PARENT id must match the message in the CHILD area (lineage prefix), ...
        List<Integer> parentSearch = searchIds(new MessageSearchParams().areaIds(Set.of(parentId)));
        assertTrue(parentSearch.contains(childAreaMsg.getId()),
                "a message in the child area must be matched when searching by the ancestor area");
        assertFalse(parentSearch.contains(noAreaMsg.getId()),
                "a message with no area must not match an ancestor-area filter");

        // ... and searching by the CHILD id must also match the child-area message (its own lineage).
        List<Integer> childSearch = searchIds(
                new MessageSearchParams().areaIds(Set.of(String.valueOf(child.getId()))));
        assertTrue(childSearch.contains(childAreaMsg.getId()),
                "a message in the child area must also be matched when searching by the child area itself");
    }

    // ------------------------------------------------------------------------------------------- //
    // Promulgation-type filter
    // ------------------------------------------------------------------------------------------- //

    /** Attaches a persisted NAVTEX promulgation (seeded 'navtex' type) to the message. */
    private void attachNavtexPromulgation(Message message, boolean promulgate) {
        PromulgationType navtex = promulgationTypeService.getPromulgationType("navtex");
        assertNotNull(navtex, "the seeded 'navtex' promulgation type must exist");
        NavtexMessagePromulgation p = new NavtexMessagePromulgation();
        p.setType(navtex);
        p.setPromulgate(promulgate);
        message.addPromulgation(p);
        em.persist(p);
        em.flush();
    }

    @Test
    @Transactional
    void searchByPromulgationTypeMatchesPromulgatingMessageOfThatType() {
        Message promulgating = createNw("prom-navtex-on");
        Message notPromulgating = createNw("prom-navtex-off");
        Message noPromulgation = createNw("prom-none");
        attachNavtexPromulgation(promulgating, true);
        attachNavtexPromulgation(notPromulgating, false);

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .promulgationTypes(Set.of("navtex")));

        assertTrue(ids.contains(promulgating.getId()),
                "a message with a promulgating NAVTEX promulgation must match the navtex type filter");
        assertFalse(ids.contains(notPromulgating.getId()),
                "a message whose NAVTEX promulgation has promulgate=false must be excluded");
        assertFalse(ids.contains(noPromulgation.getId()),
                "a message with no promulgation must be excluded from a promulgation-type filter");
    }

    @Test
    @Transactional
    void searchByDifferentPromulgationTypeExcludesNavtexMessage() {
        Message navtexMsg = createNw("prom-other-type");
        attachNavtexPromulgation(navtexMsg, true);

        // The message promulgates via NAVTEX, but we filter by 'twitter' -> it must be excluded.
        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .promulgationTypes(Set.of("twitter")));

        assertFalse(ids.contains(navtexMsg.getId()),
                "a NAVTEX-promulgating message must not match a 'twitter' promulgation-type filter");
    }

    // ------------------------------------------------------------------------------------------- //
    // Paging boundaries (total independent of the returned slice)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void pagingTotalIsIndependentOfPageAndSlicesCorrectly() {
        // Five distinct messages in dma-nw, deterministically ordered by id ascending.
        Message m0 = createNw("page-0");
        Message m1 = createNw("page-1");
        Message m2 = createNw("page-2");
        Message m3 = createNw("page-3");
        Message m4 = createNw("page-4");
        List<Integer> ourOrder = List.of(m0.getId(), m1.getId(), m2.getId(), m3.getId(), m4.getId());

        // The dma-nw series is shared with the committed-row REST ITs (MessageRest*ITTest), whose
        // best-effort @AfterEach cleanup can leave behind a stray committed dma-nw row. A stray has a
        // smaller (older) id than our in-transaction rows, so an ascending-id sort places it ahead of ours
        // and shifts the page boundaries - which is why we assert against the actual unpaged order rather
        // than the brittle absolute "first two ids are m0,m1". We still verify the two real contracts:
        //   (a) total is identical on every page (the "independent of page" property), and
        //   (b) the maxSize=2 pages slice the full ordered result with no gaps/overlaps (the "slices
        //       correctly" property) - reconstructing the full ordered id list and, within it, exactly our
        //       five rows in ascending-id order.

        // Independent unpaged probe: the full ordered result and the canonical total.
        MessageSearchParams probeParams = new MessageSearchParams().seriesIds(Set.of("dma-nw"));
        probeParams.sortBy("id");
        probeParams.sortOrder(SortOrder.ASC);
        probeParams.maxSize(Integer.MAX_VALUE);
        probeParams.page(0);
        PagedSearchResultVo<Message> probe = messageSearchService.search(probeParams);
        int expectedTotal = (int) probe.getTotal();
        List<Integer> fullOrder = probe.getData().stream().map(Message::getId).collect(Collectors.toList());

        assertEquals(expectedTotal, fullOrder.size(),
                "the unpaged result must contain exactly 'total' rows");
        assertEquals(ourOrder, onlyOurs(probe, ourOrder),
                "the unpaged search must contain exactly our five dma-nw rows in ascending-id order");
        assertTrue(expectedTotal >= 5, "the unpaged total must include at least our five rows");

        // Walk every maxSize=2 page and reconstruct the full result; total must never change.
        List<Integer> reconstructed = new java.util.ArrayList<>();
        int pageCount = (expectedTotal + 1) / 2;   // ceil(total / 2)
        for (int p = 0; p < pageCount; p++) {
            MessageSearchParams pageParams = new MessageSearchParams().seriesIds(Set.of("dma-nw"));
            pageParams.sortBy("id");
            pageParams.sortOrder(SortOrder.ASC);
            pageParams.maxSize(2);
            pageParams.page(p);
            PagedSearchResultVo<Message> r = messageSearchService.search(pageParams);
            assertEquals(expectedTotal, r.getTotal(),
                    "total must be the full unpaged count on every page (independent of the requested page)");
            assertTrue(r.getData().size() <= 2, "no page may exceed maxSize");
            r.getData().forEach(m -> reconstructed.add(m.getId()));
        }
        assertEquals(fullOrder, reconstructed,
                "concatenating the maxSize=2 pages must reproduce the full ordered result with no gaps or overlaps");

        // A page past the end -> empty slice, but the full total is still reported.
        MessageSearchParams pBeyond = new MessageSearchParams().seriesIds(Set.of("dma-nw"));
        pBeyond.sortBy("id");
        pBeyond.sortOrder(SortOrder.ASC);
        pBeyond.maxSize(2);
        pBeyond.page(pageCount + 50);
        PagedSearchResultVo<Message> rBeyond = messageSearchService.search(pBeyond);
        assertEquals(expectedTotal, rBeyond.getTotal(), "a page past the end must still report the full total");
        assertTrue(rBeyond.getData().isEmpty(), "a page past the end must contain no rows");
    }

    /** Filters a result's data ids down to (and in the order of) our expected ids, ignoring stray rows. */
    private List<Integer> onlyOurs(PagedSearchResultVo<Message> result, List<Integer> expected) {
        return result.getData().stream()
                .map(Message::getId)
                .filter(expected::contains)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // User-via-MessageHistory filter (UPDATED_BY default joins through history.user)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByUsernameViaHistoryMatchesMessageWhoseHistoryRecordsThatUser() {
        // The UPDATED_BY (default) userType joins Message -> history -> user. createMessage writes a
        // MessageHistory entry stamped with the CURRENT user, so a message created while a given user is
        // current must be matched by a username filter for that user, and a message created with no current
        // user (null history user) must not.
        User me = persistUser("hist-user");
        becomeCurrentUser(me);

        Message mine = createNw("history-mine");
        em.flush();

        // Clear the current user before creating the "other" message so its history user is null.
        ticketService.removeTicketForCurrentThread();
        assertNull(userService.currentUser(),
                "clearing the ticket must reset the current user to null");
        Message anon = createNw("history-anon");
        em.flush();

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .username(me.getUsername())
                .userType(UPDATED_BY));

        assertTrue(ids.contains(mine.getId()),
                "a message whose history records the user must match a UPDATED_BY username filter");
        assertFalse(ids.contains(anon.getId()),
                "a message with no history user must not match the username filter");
    }

    @Test
    @Transactional
    void searchByUsernameViaHistoryDefaultsToUpdatedByWhenUserTypeOmitted() {
        // When userType is omitted it defaults to UPDATED_BY (the history-join branch). A message created
        // while the user is current must match a bare username filter.
        User me = persistUser("hist-default-user");
        becomeCurrentUser(me);

        Message mine = createNw("history-default-mine");
        em.flush();

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .username(me.getUsername()));   // no userType -> defaults to UPDATED_BY (history join)

        assertTrue(ids.contains(mine.getId()),
                "a missing userType must default to the UPDATED_BY history-join semantics");
    }

    @Test
    @Transactional
    void searchByCreatedByUserMatchesDenormalizedCreatedByColumn() {
        // The CREATED_BY branch joins the denormalized Message.createdBy column (not history). createMessage
        // stamps createdBy with the current user, so a message created while the user is current matches a
        // CREATED_BY filter for that user, and one created anonymously does not.
        User me = persistUser("created-by-user");
        becomeCurrentUser(me);
        Message mine = createNw("createdby-mine");
        em.flush();

        ticketService.removeTicketForCurrentThread();
        Message anon = createNw("createdby-anon");
        em.flush();

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .username(me.getUsername())
                .userType(CREATED_BY));

        assertTrue(ids.contains(mine.getId()),
                "a message created by the user must match a CREATED_BY username filter");
        assertFalse(ids.contains(anon.getId()),
                "a message created with no current user must not match the CREATED_BY filter");
    }

    @Test
    @Transactional
    void searchByLastUpdatedByUserMatchesDenormalizedColumn() {
        // The LAST_UPDATED_BY branch joins Message.lastUpdatedBy. createMessage does NOT set lastUpdatedBy
        // (only updateStatus / updateMessage do), so we drive a status change while the user is current to
        // stamp lastUpdatedBy, then assert the LAST_UPDATED_BY filter matches.
        User me = persistUser("lastupd-user");
        becomeCurrentUser(me);
        Message mine = createNw("lastupd-mine");
        try {
            messageService.updateStatus(mine.getUid(), Status.VERIFIED);  // stamps lastUpdatedBy = me
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        em.flush();

        List<Integer> ids = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .username(me.getUsername())
                .userType(LAST_UPDATED_BY));

        assertTrue(ids.contains(mine.getId()),
                "a message last updated by the user must match a LAST_UPDATED_BY username filter");
    }

    // ------------------------------------------------------------------------------------------- //
    // Kind filter (phase 4 of docs/s124-cancellation-plan.md)
    //
    // The criteria layer only filters when kinds are set: programmatic searches
    // (new MessageSearchParams()) see every kind, which is what the internal flows (expiry
    // sweeper, firing exercises, mirroring) rely on. Query-driven searches get the WARNING
    // default applied in MessageSearchParams.instantiate() - covered by the params unit test.
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void kindFilterMatchesTheKindColumnAndUnfilteredSearchesSeeAllKinds() {
        Message warning = createNw("kind-plain-warning");
        Message cancellationTemplate = newDraft("dma-nw", Type.COASTAL_WARNING, "kind-cancellation");
        cancellationTemplate.setKind(MessageKind.CANCELLATION);
        Message cancellation = create(cancellationTemplate);

        // Programmatic search without kinds - internal callers see all kinds
        List<Integer> unfiltered = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw")));
        assertTrue(unfiltered.contains(warning.getId()),
                "an unfiltered search must return ordinary warnings");
        assertTrue(unfiltered.contains(cancellation.getId()),
                "an unfiltered (programmatic) search must return CANCELLATION rows too");

        // kinds=WARNING - the default instantiate() applies for query-driven searches
        List<Integer> warningsOnly = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .kinds(MessageKind.WARNING));
        assertTrue(warningsOnly.contains(warning.getId()));
        assertFalse(warningsOnly.contains(cancellation.getId()),
                "kinds=WARNING must hide CANCELLATION rows");

        // kinds=CANCELLATION - the explicit opt-in view
        List<Integer> cancellationsOnly = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .kinds(MessageKind.CANCELLATION));
        assertFalse(cancellationsOnly.contains(warning.getId()),
                "kinds=CANCELLATION must hide ordinary warnings");
        assertTrue(cancellationsOnly.contains(cancellation.getId()));

        // Mixed kinds - the REST parameter accepts any set
        List<Integer> mixed = searchIds(new MessageSearchParams()
                .seriesIds(Set.of("dma-nw"))
                .kinds(MessageKind.WARNING, MessageKind.CANCELLATION));
        assertTrue(mixed.contains(warning.getId()));
        assertTrue(mixed.contains(cancellation.getId()));
    }

    @Test
    @Transactional
    void messageIdReferenceLookupsAreKindIndependent() {
        // The search service ANDs the resolved reference-ID set with every other predicate, so
        // instantiate() exempts messageId lookups from the WARNING default: a cancellation
        // warning must appear in the reference tree of the message it cancels.
        Message source = createNw("kind-ref-source");
        Message cancellationTemplate = newDraft("dma-nw", Type.COASTAL_WARNING, "kind-ref-cancellation");
        cancellationTemplate.setKind(MessageKind.CANCELLATION);
        Reference reference = new Reference();
        reference.setMessageId(source.getUid());
        reference.setType(ReferenceType.CANCELLATION);
        cancellationTemplate.addReference(reference);
        Message cancellation = create(cancellationTemplate);

        // No kinds (what instantiate() produces for messageId lookups) - the whole tree returns
        List<Integer> tree = searchIds(new MessageSearchParams()
                .messageId(source.getUid())
                .referenceLevels(1));
        assertTrue(tree.contains(source.getId()));
        assertTrue(tree.contains(cancellation.getId()),
                "the cancellation warning must appear in the reference tree of the message it cancels");

        // An explicitly requested kind still applies to reference lookups
        List<Integer> warningsOnly = searchIds(new MessageSearchParams()
                .messageId(source.getUid())
                .referenceLevels(1)
                .kinds(MessageKind.WARNING));
        assertTrue(warningsOnly.contains(source.getId()));
        assertFalse(warningsOnly.contains(cancellation.getId()),
                "an explicit kind filter must still narrow a reference lookup");
    }
}
