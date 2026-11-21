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
import org.niord.core.web.NiordWebException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.user.TicketService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.test.ITTest;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageParamFilterService} — focused on persistence and, above all,
 * the per-user data-isolation guarantees of the service. Every filter is owned by exactly one
 * {@link User}, and the service must never let one user read, update or delete another user's filter.
 *
 * <p><b>Current-user seam.</b> A plain service test has no Keycloak identity, so
 * {@code userService.currentUser()} returns {@code null} on its own. Since the filter service calls
 * {@code user.getId()} this would NPE. We therefore establish a current user through the validated
 * ticket seam: persist a {@link User}, issue a one-time ticket for it and resolve that ticket for the
 * current thread (see {@link #persistUser}/{@link #loginAs}). {@code currentUser()} then resolves the
 * thread-local ticket back to the persisted user. The ticket is cleared after every test.
 *
 * <p>Each test runs inside {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent. Persisted state is verified by reloading rows via
 * {@code em.flush()/em.clear()/em.find(...)}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageParamFilterServiceITTest extends ITTest {

    @Autowired
    MessageParamFilterService filterService;

    @Autowired
    UserService userService;

    @Autowired
    TicketService ticketService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Current-user seam helpers
    // ------------------------------------------------------------------------------------------- //

    /** Persists a fresh user with a globally-unique username so tests never collide. */
    private User persistUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setFirstName("Test");
        u.setLastName("User");
        u.setEmail(username + "@example.com");
        em.persist(u);
        em.flush();
        return u;
    }

    /** Makes {@code userService.currentUser()} resolve to the given user via the ticket seam. */
    private void loginAs(User u) {
        String ticket = ticketService.createTicket(null, u);
        assertTrue(ticketService.resolveTicketForCurrentThread(ticket),
                "the freshly-issued ticket must resolve for the current thread");
    }

    /** Switches the current user mid-test: drops the previous ticket then logs in as the new user. */
    private void switchTo(User u) {
        ticketService.removeTicketForCurrentThread();
        loginAs(u);
    }

    @AfterEach
    void clearTicket() {
        ticketService.removeTicketForCurrentThread();
    }

    /** Returns a globally-unique username token to keep users isolated across tests/runs. */
    private String uniqueUsername() {
        return "u-" + UUID.randomUUID();
    }

    /** Builds a detached filter template (no id, no owner). */
    private MessageParamFilter newFilter(String name, String parameters) {
        MessageParamFilter f = new MessageParamFilter();
        f.setName(name);
        f.setParameters(parameters);
        return f;
    }

    // ------------------------------------------------------------------------------------------- //
    // createOrUpdateMessageFilter — create path
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createAssignsCurrentUserAndPersists() {
        User a = persistUser(uniqueUsername());
        loginAs(a);

        String name = "filter-" + UUID.randomUUID();
        MessageParamFilter saved = filterService.createOrUpdateMessageFilter(
                newFilter(name, "status=PUBLISHED"));

        Integer id = saved.getId();
        assertNotNull(id, "creating a filter must assign a generated primary key");
        assertNotNull(saved.getUser(), "a created filter must be stamped with an owner");
        assertEquals(a.getId(), saved.getUser().getId(),
                "the created filter must be owned by the current user");

        em.flush();
        em.clear();

        MessageParamFilter reloaded = em.find(MessageParamFilter.class, id);
        assertNotNull(reloaded, "the filter row must exist in the database");
        assertEquals(name, reloaded.getName());
        assertEquals("status=PUBLISHED", reloaded.getParameters());
        assertEquals(a.getId(), reloaded.getUser().getId(),
                "the persisted owner must be the current user");
    }

    @Test
    @Transactional
    void createWithExistingNameUpdatesInsteadOfDuplicating() {
        User a = persistUser(uniqueUsername());
        loginAs(a);

        String name = "dup-" + UUID.randomUUID();
        MessageParamFilter first = filterService.createOrUpdateMessageFilter(
                newFilter(name, "status=DRAFT"));
        Integer firstId = first.getId();

        // Re-using the same name must update the existing filter, not create a second row.
        MessageParamFilter second = filterService.createOrUpdateMessageFilter(
                newFilter(name, "status=PUBLISHED"));
        assertEquals(firstId, second.getId(),
                "re-using an existing filter name must return the same filter id");

        em.flush();
        em.clear();

        List<MessageParamFilter> all = filterService.getMessageFiltersForUser();
        assertEquals(1, all.size(), "creating with an existing name must not duplicate the filter");
        assertEquals("status=PUBLISHED", all.get(0).getParameters(),
                "the existing filter's parameters must be overwritten with the new value");
    }

    @Test
    @Transactional
    void saveUnderANameThatDiffersOnlyInCaseReplacesTheFilter() {
        User a = persistUser(uniqueUsername());
        loginAs(a);

        String suffix = UUID.randomUUID().toString();
        MessageParamFilter first = filterService.createOrUpdateMessageFilter(
                newFilter("baltic drafts " + suffix, "status=DRAFT"));
        Integer firstId = first.getId();
        em.flush();

        // The admin app's save sends a name and no id: another spelling of the name replaces the filter
        MessageParamFilter second = filterService.createOrUpdateMessageFilter(
                newFilter("Baltic Drafts " + suffix.toUpperCase(), "status=PUBLISHED"));
        assertEquals(firstId, second.getId(),
                "saving under a name that differs only in case must replace the filter, keeping its id");

        em.flush();
        em.clear();

        List<MessageParamFilter> all = filterService.getMessageFiltersForUser();
        assertEquals(1, all.size(), "saving under another spelling of a name must not make a second filter");
        assertEquals("Baltic Drafts " + suffix.toUpperCase(), all.get(0).getName(),
                "the replaced filter takes the name as typed now");
        assertEquals("status=PUBLISHED", all.get(0).getParameters(),
                "the replaced filter takes the new parameters");
    }

    @Test
    @Transactional
    void aUserWithFiltersWhoseNamesDifferOnlyInCaseGetsTheExactOneOrElseTheOldest() {
        User a = persistUser(uniqueUsername());
        loginAs(a);

        // Two filters whose names differ only in case, as data saved before the look-up ignored case can hold
        String suffix = UUID.randomUUID().toString();
        MessageParamFilter older = newFilter("north sea " + suffix, "q=older");
        older.setUser(a);
        em.persist(older);
        em.flush();
        MessageParamFilter newer = newFilter("NORTH SEA " + suffix.toUpperCase(), "q=newer");
        newer.setUser(a);
        em.persist(newer);
        em.flush();

        assertEquals(older.getId(), filterService.findByCurrentUserAndName("north sea " + suffix).getId(),
                "the filter whose name is exactly the given one is found");
        assertEquals(newer.getId(),
                filterService.findByCurrentUserAndName("NORTH SEA " + suffix.toUpperCase()).getId(),
                "the filter whose name is exactly the given one is found, even when it is the newer");
        MessageParamFilter neither = filterService.findByCurrentUserAndName("North Sea " + suffix);
        assertNotNull(neither, "a spelling that matches neither exactly finds one of them");
        assertEquals(older.getId(), neither.getId(), "without an exact match the oldest filter is found");
    }

    @Test
    @Transactional
    void createWithIdUpdatesExistingFilter() {
        User a = persistUser(uniqueUsername());
        loginAs(a);

        MessageParamFilter created = filterService.createOrUpdateMessageFilter(
                newFilter("orig-" + UUID.randomUUID(), "q=0"));
        Integer id = created.getId();
        em.flush();
        em.clear();

        MessageParamFilter upd = new MessageParamFilter();
        upd.setId(id);
        upd.setName("renamed");
        upd.setParameters("q=1");

        MessageParamFilter result = filterService.createOrUpdateMessageFilter(upd);
        assertEquals(id, result.getId(),
                "updating by id must keep the same primary key");

        em.flush();
        em.clear();

        MessageParamFilter reloaded = em.find(MessageParamFilter.class, id);
        assertEquals("renamed", reloaded.getName(), "update-by-id must persist the new name");
        assertEquals("q=1", reloaded.getParameters(), "update-by-id must persist the new parameters");
    }

    @Test
    @Transactional
    void createWithUnknownIdThrows() {
        User a = persistUser(uniqueUsername());
        loginAs(a);

        MessageParamFilter bad = new MessageParamFilter();
        bad.setId(999999);
        bad.setName("x");
        bad.setParameters("q=1");

        // findById finds nothing -> createOrUpdate rejects the unknown id.
        assertThrows(IllegalArgumentException.class,
                () -> filterService.createOrUpdateMessageFilter(bad),
                "creating/updating with an unknown filter id must be rejected");
    }

    // ------------------------------------------------------------------------------------------- //
    // getMessageFiltersForUser — owner scoping
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getMessageFiltersForUserReturnsOnlyOwn() {
        User a = persistUser(uniqueUsername());
        User b = persistUser(uniqueUsername());

        loginAs(a);
        String nameA = "fA-" + UUID.randomUUID();
        filterService.createOrUpdateMessageFilter(newFilter(nameA, "status=PUBLISHED"));

        switchTo(b);
        String nameB = "fB-" + UUID.randomUUID();
        filterService.createOrUpdateMessageFilter(newFilter(nameB, "status=DRAFT"));

        // B sees only B's filter.
        List<MessageParamFilter> bFilters = filterService.getMessageFiltersForUser();
        assertEquals(1, bFilters.size(), "user B must see only their own filter");
        assertEquals(nameB, bFilters.get(0).getName());

        // A sees only A's filter.
        switchTo(a);
        List<MessageParamFilter> aFilters = filterService.getMessageFiltersForUser();
        assertEquals(1, aFilters.size(), "user A must see only their own filter");
        assertEquals(nameA, aFilters.get(0).getName());
    }

    // ------------------------------------------------------------------------------------------- //
    // findById — ownership guard (key data-isolation test)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByIdOwnerSucceedsAndNonOwnerThrows() {
        User a = persistUser(uniqueUsername());
        User b = persistUser(uniqueUsername());

        loginAs(a);
        MessageParamFilter fA = filterService.createOrUpdateMessageFilter(
                newFilter("own-" + UUID.randomUUID(), "status=PUBLISHED"));
        Integer id = fA.getId();
        em.flush();

        // A non-owner must not be able to look up A's filter by id. The ownership guard now signals
        // this as a 403 NiordWebException (WEB2), not a bare IllegalArgumentException.
        switchTo(b);
        NiordWebException nonOwnerRead = assertThrows(NiordWebException.class,
                () -> filterService.findById(id),
                "a non-owner must not be able to read another user's filter by id");
        assertEquals(403, nonOwnerRead.getStatusCode().value(),
                "a non-owner read is forbidden -> HTTP 403");

        // The owner can.
        switchTo(a);
        MessageParamFilter found = filterService.findById(id);
        assertNotNull(found, "the owner must be able to read their own filter");
        assertEquals(id, found.getId());
    }

    @Test
    @Transactional
    void findByIdWithNoCurrentUserIsRejectedNotNpe() {
        // Create a filter as a real owner, then drop the ticket so currentUser() resolves to null.
        User a = persistUser(uniqueUsername());
        loginAs(a);
        MessageParamFilter fA = filterService.createOrUpdateMessageFilter(
                newFilter("anon-" + UUID.randomUUID(), "status=PUBLISHED"));
        Integer id = fA.getId();
        em.flush();

        // Anonymous (null current user) + an existing filter must be rejected cleanly as unauthenticated
        // (401) rather than NPE-ing on user.getId() and surfacing an opaque HTTP 500 (MSGSVC2 fix).
        ticketService.removeTicketForCurrentThread();
        NiordWebException ex = assertThrows(NiordWebException.class,
                () -> filterService.findById(id),
                "a null current user must be rejected, not cause a NullPointerException");
        assertEquals(401, ex.getStatusCode().value(),
                "a null current user is unauthenticated -> HTTP 401");

        // The unknown-id path is unchanged: with no filter to gate on it still returns null, not throws.
        assertNull(filterService.findById(987654321),
                "an unknown id with a null user must still return null (behaviour-preserving guard)");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByCurrentUserAndName — match / null / isolation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByCurrentUserAndNameReturnsMatchOrNull() {
        User a = persistUser(uniqueUsername());
        User b = persistUser(uniqueUsername());

        loginAs(a);
        String name = "named-" + UUID.randomUUID();
        filterService.createOrUpdateMessageFilter(newFilter(name, "status=PUBLISHED"));
        em.flush();

        MessageParamFilter match = filterService.findByCurrentUserAndName(name);
        assertNotNull(match, "a filter owned by the current user must be found by name");
        assertEquals(name, match.getName());

        assertNull(filterService.findByCurrentUserAndName("missing-" + UUID.randomUUID()),
                "an unknown name must yield null");

        // B must not find A's named filter.
        switchTo(b);
        assertNull(filterService.findByCurrentUserAndName(name),
                "another user must not find a filter by a name they do not own");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByCurrentUserAndIds — own-and-given-ids scoping
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByCurrentUserAndIdsReturnsOnlyOwnGivenIds() {
        User a = persistUser(uniqueUsername());
        User b = persistUser(uniqueUsername());

        loginAs(a);
        MessageParamFilter f1 = filterService.createOrUpdateMessageFilter(
                newFilter("f1-" + UUID.randomUUID(), "q=1"));
        MessageParamFilter f2 = filterService.createOrUpdateMessageFilter(
                newFilter("f2-" + UUID.randomUUID(), "q=2"));
        MessageParamFilter f3 = filterService.createOrUpdateMessageFilter(
                newFilter("f3-" + UUID.randomUUID(), "q=3"));
        em.flush();

        List<MessageParamFilter> result = filterService.findByCurrentUserAndIds(f1.getId(), f3.getId());
        assertEquals(2, result.size(), "only the two requested own filters must be returned");
        List<Integer> ids = result.stream().map(MessageParamFilter::getId).collect(Collectors.toList());
        assertTrue(ids.contains(f1.getId()), "the result must contain the requested f1 id");
        assertTrue(ids.contains(f3.getId()), "the result must contain the requested f3 id");
        assertFalse(ids.contains(f2.getId()), "the un-requested f2 id must not be returned");

        // B requesting A's ids gets nothing.
        switchTo(b);
        assertTrue(filterService.findByCurrentUserAndIds(f1.getId(), f3.getId()).isEmpty(),
                "another user must not retrieve filters owned by someone else, even by id");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteMessageFilter — own / non-owner / unknown
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteRemovesOwnFilter() {
        User a = persistUser(uniqueUsername());
        loginAs(a);

        MessageParamFilter f = filterService.createOrUpdateMessageFilter(
                newFilter("del-" + UUID.randomUUID(), "status=PUBLISHED"));
        Integer id = f.getId();
        em.flush();

        assertTrue(filterService.deleteMessageFilter(id),
                "deleting an existing own filter must return true");

        em.flush();
        em.clear();

        assertNull(em.find(MessageParamFilter.class, id),
                "the deleted filter must be gone from the database");
        assertTrue(filterService.getMessageFiltersForUser().isEmpty(),
                "the user must have no filters left after deleting their only one");
    }

    @Test
    @Transactional
    void deleteNonOwnerThrowsAndLeavesRow() {
        User a = persistUser(uniqueUsername());
        User b = persistUser(uniqueUsername());

        loginAs(a);
        MessageParamFilter fA = filterService.createOrUpdateMessageFilter(
                newFilter("keep-" + UUID.randomUUID(), "status=PUBLISHED"));
        Integer id = fA.getId();
        em.flush();

        // B must not be able to delete A's filter. The ownership guard (via findById) now signals this
        // as a 403 NiordWebException (WEB2), not a bare IllegalArgumentException.
        switchTo(b);
        NiordWebException nonOwnerDelete = assertThrows(NiordWebException.class,
                () -> filterService.deleteMessageFilter(id),
                "a non-owner must not be able to delete another user's filter");
        assertEquals(403, nonOwnerDelete.getStatusCode().value(),
                "a non-owner delete is forbidden -> HTTP 403");

        // The row must still be there for A.
        switchTo(a);
        em.flush();
        em.clear();
        assertNotNull(em.find(MessageParamFilter.class, id),
                "a rejected non-owner delete must leave the filter row untouched");
    }

    @Test
    @Transactional
    void deleteUnknownReturnsFalse() {
        User a = persistUser(uniqueUsername());
        loginAs(a);

        assertFalse(filterService.deleteMessageFilter(888888),
                "deleting a non-existing filter must return false");
    }
}
