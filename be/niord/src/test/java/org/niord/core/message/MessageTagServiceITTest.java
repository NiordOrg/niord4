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
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.domain.DomainService;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.model.search.PagedSearchParamsVo.SortOrder;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageTagService} persistence, access-scoping and CRUD behaviour,
 * run against the seeded MySQL container of the {@link ITTest} infrastructure. No {@code MessageTag}
 * rows are seeded, so every test creates its own data with globally-unique name tokens.
 *
 * <p><b>Access-scoping context.</b> A plain service test has NO authenticated user and NO
 * thread-local domain, so {@code userService.currentUser() == null} and
 * {@code domainService.currentDomain() == null}. {@code MessageTagService.validateAccess} therefore
 * behaves as follows:
 * <ul>
 *   <li>{@code PUBLIC} tags are always visible.</li>
 *   <li>{@code PRIVATE} and {@code TEMP} tags are always filtered out (user is null) at the service
 *       layer: both belong to the user who created them.</li>
 *   <li>{@code DOMAIN} tags are filtered out unless the test either queries the explicit
 *       {@code findTags(Domain, tagIds)} overload or installs a thread-local domain via
 *       {@link DomainService#setDomainForCurrentThread(String)}.</li>
 * </ul>
 * The DOMAIN tests therefore install/remove a thread-local domain around the call and always clean
 * up in a {@code finally} block.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageTagServiceITTest extends ITTest {

    @Autowired
    MessageTagService messageTagService;

    @Autowired
    DomainService domainService;

    @Autowired
    DomainLookupService domainLookupService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached tag template of the given type with a unique name (tagId is auto-assigned). */
    private MessageTag newTag(MessageTagType type, String name) {
        MessageTag tag = new MessageTag();
        tag.setType(type);
        tag.setName(name);
        return tag;
    }

    /** Returns a globally-unique token to keep test data isolated from other tests and the seed. */
    private String token() {
        return "IT-Tag-" + UUID.randomUUID();
    }

    /** Persists a minimal DRAFT NW message and returns the managed entity. */
    private Message persistMessage() {
        Message m = new Message();
        String uid = UUID.randomUUID().toString();
        m.setUid(uid);
        m.setRepoPath("messages/it/" + uid);
        m.setMainType(MainType.NW);
        m.setType(Type.LOCAL_WARNING);
        m.setStatus(Status.DRAFT);
        em.persist(m);
        em.flush();
        return m;
    }

    private MessageTagSearchParams searchParams() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.maxSize(100);
        return p;
    }

    // ------------------------------------------------------------------------------------------- //
    // createMessageTag
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createMessageTagAssignsTagIdAndPersistsRow() {
        MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        assertNotNull(created.getTagId(), "createMessageTag must assign a tag ID");
        Integer id = created.getId();
        assertNotNull(id, "createMessageTag must assign a generated primary key");

        em.flush();
        em.clear();

        MessageTag reloaded = em.find(MessageTag.class, id);
        assertNotNull(reloaded, "the message tag row must exist in the database");
        assertEquals(MessageTagType.PUBLIC, reloaded.getType());
        assertEquals(created.getTagId(), reloaded.getTagId());
    }

    @Test
    @Transactional
    void createMessageTagDefaultsMessageCountToZero() {
        MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        Integer id = created.getId();
        em.flush();
        em.clear();

        MessageTag reloaded = em.find(MessageTag.class, id);
        assertEquals(0, reloaded.getMessageCount(), "a tag created without messages must have count 0");
    }

    @Test
    @Transactional
    void createMessageTagRejectsDuplicateTagId() {
        MessageTag first = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        em.flush();

        // A second PUBLIC tag re-using the same tagId must be rejected by the duplicate check.
        MessageTag dup = newTag(MessageTagType.PUBLIC, token());
        dup.setTagId(first.getTagId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageTagService.createMessageTag(dup));
        assertTrue(ex.getMessage().contains(first.getTagId()),
                "the duplicate-tagId error must mention the offending tag ID, but was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // findTag / findTags
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findTagReturnsPublicTag() {
        MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        em.flush();

        MessageTag found = messageTagService.findTag(created.getTagId());
        assertNotNull(found, "a PUBLIC tag must be visible without an authenticated user");
        assertEquals(created.getTagId(), found.getTagId());
    }

    @Test
    @Transactional
    void findTagFiltersOutPrivateTagWhenUserIsNull() {
        // PRIVATE tags require an authenticated owner; with no current user they are filtered out.
        MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PRIVATE, token()));
        Integer id = created.getId();
        em.flush();

        assertNull(messageTagService.findTag(created.getTagId()),
                "a PRIVATE tag must not be visible when there is no authenticated user");

        // ... but the row really was persisted - it is only hidden by access scoping.
        em.clear();
        assertNotNull(em.find(MessageTag.class, id),
                "the PRIVATE tag row must still exist in the database");
    }

    @Test
    @Transactional
    void findTagReturnsNullForUnknownTagId() {
        assertNull(messageTagService.findTag("IT-no-such-tag-" + UUID.randomUUID()));
    }

    @Test
    @Transactional
    void findTagsReturnsEmptyForNoIds() {
        assertTrue(messageTagService.findTags().isEmpty(),
                "findTags with no ids must return an empty list");
    }

    @Test
    @Transactional
    void findTagsReturnsOnlyVisibleTags() {
        MessageTag pub = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        MessageTag priv = messageTagService.createMessageTag(newTag(MessageTagType.PRIVATE, token()));
        em.flush();

        List<MessageTag> found = messageTagService.findTags(pub.getTagId(), priv.getTagId());
        assertEquals(1, found.size(), "only the PUBLIC tag is visible without a user/domain");
        assertEquals(pub.getTagId(), found.get(0).getTagId());
    }

    // ------------------------------------------------------------------------------------------- //
    // DOMAIN tags - findTags(Domain, ...) and the thread-local domain path
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void domainTagIsVisibleViaExplicitDomainOverload() {
        try {
            // Install the nw domain so createMessageTag stamps tag.domain = niord-nw.
            domainService.setDomainForCurrentThread("niord-nw");
            Domain nw = domainService.currentDomain();
            assertNotNull(nw, "the seeded niord-nw domain must resolve");

            MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.DOMAIN, token()));
            em.flush();

            // The explicit overload, passed the same domain, sees the DOMAIN tag.
            List<MessageTag> found = messageTagService.findTags(nw, created.getTagId());
            assertEquals(1, found.size(), "a DOMAIN tag must be visible for its own domain");
            assertEquals(created.getTagId(), found.get(0).getTagId());
        } finally {
            domainService.removeDomainForCurrentThread();
        }
    }

    @Test
    @Transactional
    void domainTagIsNotVisibleForADifferentDomain() {
        try {
            domainService.setDomainForCurrentThread("niord-nw");
            MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.DOMAIN, token()));
            em.flush();

            Domain otherDomain = domainLookupService.findByDomainId("niord-nm");
            assertNotNull(otherDomain, "the seeded niord-nm domain must resolve");

            List<MessageTag> found = messageTagService.findTags(otherDomain, created.getTagId());
            assertTrue(found.isEmpty(),
                    "a DOMAIN tag tied to niord-nw must not be visible for niord-nm");
        } finally {
            domainService.removeDomainForCurrentThread();
        }
    }

    @Test
    @Transactional
    void domainTagIsVisibleViaThreadLocalDomain() {
        try {
            domainService.setDomainForCurrentThread("niord-nw");
            MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.DOMAIN, token()));
            em.flush();

            // findTag uses the no-arg overload which resolves the current (thread-local) domain.
            MessageTag found = messageTagService.findTag(created.getTagId());
            assertNotNull(found, "a DOMAIN tag must be visible when its domain is the current thread domain");
            assertEquals(created.getTagId(), found.getTagId());
        } finally {
            domainService.removeDomainForCurrentThread();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // findTagsByTypeAndName - no access filtering
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findTagsByTypeAndNameMatchesByTypeAndName() {
        String name = token();
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, name));
        em.flush();

        List<MessageTag> found = messageTagService.findTagsByTypeAndName(MessageTagType.PUBLIC, name);
        assertEquals(1, found.size(), "findTagsByTypeAndName must match by exact name and type");
        assertEquals(name, found.get(0).getName());
    }

    @Test
    @Transactional
    void findTagsByTypeAndNameIgnoresMismatchedType() {
        String name = token();
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, name));
        em.flush();

        // Same name but searched as DOMAIN - the type predicate excludes it.
        assertTrue(messageTagService.findTagsByTypeAndName(MessageTagType.DOMAIN, name).isEmpty(),
                "a PUBLIC tag must not match a DOMAIN type-and-name query");
    }

    @Test
    @Transactional
    void findTagsByTypeAndNameReturnsEmptyForNullType() {
        assertTrue(messageTagService.findTagsByTypeAndName(null, "anything").isEmpty(),
                "a null type must yield an empty result");
    }

    @Test
    @Transactional
    void findTagsByTypeAndNameReturnsEmptyForEmptyNames() {
        assertTrue(messageTagService.findTagsByTypeAndName(MessageTagType.PUBLIC).isEmpty(),
                "an empty names array must yield an empty result");
    }

    // ------------------------------------------------------------------------------------------- //
    // findTagsByMessageId
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findTagsByMessageIdReturnsTagContainingMessage() {
        Message message = persistMessage();

        MessageTag template = newTag(MessageTagType.PUBLIC, token());
        template.setMessages(new ArrayList<>(List.of(message)));
        MessageTag created = messageTagService.createMessageTag(template);
        em.flush();

        List<MessageTag> found = messageTagService.findTagsByMessageId(message.getUid());
        assertEquals(1, found.size(), "the tag containing the message must be returned");
        assertEquals(created.getTagId(), found.get(0).getTagId());
    }

    @Test
    @Transactional
    void findTagsByMessageIdReturnsEmptyForBlankUid() {
        assertTrue(messageTagService.findTagsByMessageId("   ").isEmpty(),
                "a blank message UID must yield an empty result");
    }

    @Test
    @Transactional
    void findTagsByMessageIdReturnsEmptyForUnattachedMessage() {
        Message message = persistMessage();
        // No tag references the message.
        assertTrue(messageTagService.findTagsByMessageId(message.getUid()).isEmpty(),
                "a message that belongs to no tag must yield an empty result");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchMessageTags
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByNamePrefixReturnsMatchingPublicTags() {
        String prefix = token();
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-a"));
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-b"));
        // A PRIVATE tag with the same prefix must NOT match (no user/domain -> no PRIVATE predicate).
        messageTagService.createMessageTag(newTag(MessageTagType.PRIVATE, prefix + "-private"));
        em.flush();

        List<MessageTag> result = messageTagService.searchMessageTags(searchParams().name(prefix));
        assertEquals(2, result.size(), "only the two PUBLIC tags must match the name prefix");
        assertTrue(result.stream().allMatch(t -> t.getType() == MessageTagType.PUBLIC),
                "search without a user/domain must return only PUBLIC tags");
    }

    @Test
    @Transactional
    void searchByLockedFlagReturnsOnlyLockedTags() {
        String prefix = token();
        MessageTag lockedTemplate = newTag(MessageTagType.PUBLIC, prefix + "-locked");
        lockedTemplate.setLocked(true);
        messageTagService.createMessageTag(lockedTemplate);
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-unlocked"));
        em.flush();

        List<MessageTag> result = messageTagService.searchMessageTags(
                searchParams().name(prefix).locked(true));
        assertEquals(1, result.size(), "only the locked tag must match locked=true");
        assertTrue(result.get(0).isLocked());
        assertEquals(prefix + "-locked", result.get(0).getName());
    }

    @Test
    @Transactional
    void searchSortsByNameAscendingThenDescending() {
        String prefix = token();
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-b"));
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-a"));
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-c"));
        em.flush();

        MessageTagSearchParams asc = searchParams().name(prefix);
        asc.sortBy("name");
        asc.sortOrder(SortOrder.ASC);
        List<MessageTag> ascResult = messageTagService.searchMessageTags(asc);
        assertEquals(3, ascResult.size());
        assertEquals(prefix + "-a", ascResult.get(0).getName(), "ascending sort must place -a first");
        assertEquals(prefix + "-c", ascResult.get(2).getName(), "ascending sort must place -c last");

        MessageTagSearchParams desc = searchParams().name(prefix);
        desc.sortBy("name");
        desc.sortOrder(SortOrder.DESC);
        List<MessageTag> descResult = messageTagService.searchMessageTags(desc);
        assertEquals(3, descResult.size());
        assertEquals(prefix + "-c", descResult.get(0).getName(), "descending sort must place -c first");
        assertEquals(prefix + "-a", descResult.get(2).getName(), "descending sort must place -a last");
    }

    @Test
    @Transactional
    void searchCapsResultsAtMaxSize() {
        String prefix = token();
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-1"));
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-2"));
        messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, prefix + "-3"));
        em.flush();

        MessageTagSearchParams p = new MessageTagSearchParams().name(prefix);
        p.maxSize(2);
        List<MessageTag> result = messageTagService.searchMessageTags(p);
        assertEquals(2, result.size(), "maxSize must cap the number of returned tags");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateMessageTag
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateMessageTagPersistsMutableFields() {
        MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        Integer id = created.getId();
        em.flush();

        String newName = token();
        Date expiry = new Date(System.currentTimeMillis() + 3_600_000L);
        MessageTag template = new MessageTag();
        template.setTagId(created.getTagId());
        template.setType(MessageTagType.PUBLIC);
        template.setName(newName);
        template.setLocked(true);
        template.setExpiryDate(expiry);

        messageTagService.updateMessageTag(template);
        em.flush();
        em.clear();

        MessageTag reloaded = em.find(MessageTag.class, id);
        assertEquals(newName, reloaded.getName(), "update must persist the new name");
        assertTrue(reloaded.isLocked(), "update must persist the locked flag");
        assertNotNull(reloaded.getExpiryDate(), "update must persist the expiry date");
        assertEquals(MessageTagType.PUBLIC, reloaded.getType());
    }

    @Test
    @Transactional
    void updateMessageTagRejectsUnknownTagId() {
        MessageTag template = newTag(MessageTagType.PUBLIC, token());
        template.setTagId("IT-unknown-" + UUID.randomUUID());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageTagService.updateMessageTag(template));
        assertTrue(ex.getMessage().contains(template.getTagId()),
                "the not-found error must mention the missing tag ID, but was: " + ex.getMessage());
    }

    /** A template that changes only the type of the given stored tag */
    private static MessageTag retyped(MessageTag stored, MessageTagType type) {
        MessageTag template = new MessageTag();
        template.setTagId(stored.getTagId());
        template.setType(type);
        template.setName(stored.getName());
        template.setLocked(stored.isLocked());
        return template;
    }

    @Test
    @Transactional
    void aTypeChangeThatWouldLeaveTheTagWithNobodyToManageItIsRefused() {
        // TAG4: a PUBLIC tag with no user and no domain (as the publication code makes them). Turned PRIVATE or
        // TEMP it would be found by no user, turned DOMAIN by no domain. FAILS on the pre-fix code (every change
        // was taken).
        MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        Integer id = created.getId();
        em.flush();

        List<MessageTagType> refused = new ArrayList<>();
        for (MessageTagType type : new MessageTagType[] { MessageTagType.PRIVATE, MessageTagType.TEMP,
                MessageTagType.DOMAIN, null }) {
            try {
                messageTagService.updateMessageTag(retyped(created, type));
            } catch (IllegalArgumentException e) {
                refused.add(type);
            }
        }
        assertEquals(java.util.Arrays.asList(MessageTagType.PRIVATE, MessageTagType.TEMP, MessageTagType.DOMAIN,
                null), refused, "PRIVATE, TEMP, DOMAIN and no type");
        em.flush();
        em.clear();
        assertEquals(MessageTagType.PUBLIC, em.find(MessageTag.class, id).getType());
    }

    @Test
    @Transactional
    void aTypeChangeIsTakenWhenTheTagKeepsSomeoneToManageIt() {
        try {
            // A PUBLIC tag of niord-nw (no user: a service test has none)
            domainService.setDomainForCurrentThread("niord-nw");
            MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
            Integer id = created.getId();
            em.flush();

            // DOMAIN keeps it its domain's
            messageTagService.updateMessageTag(retyped(created, MessageTagType.DOMAIN));
            em.flush();
            em.clear();
            assertEquals(MessageTagType.DOMAIN, em.find(MessageTag.class, id).getType());

            // PRIVATE would still leave it with no user
            MessageTag stored = messageTagService.findTag(created.getTagId());
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> messageTagService.updateMessageTag(retyped(stored, MessageTagType.PRIVATE)));
            assertTrue(ex.getMessage().contains("no one could manage it"), ex.getMessage());
        } finally {
            domainService.removeDomainForCurrentThread();
        }
    }

    @Test
    @Transactional
    void anUpdateThatKeepsTheTypeOfATagWithNoOwnerPasses() {
        // unlockTag passes the stored tag itself: no type change, so nothing is checked
        MessageTag template = newTag(MessageTagType.PUBLIC, token());
        template.setLocked(true);
        MessageTag created = messageTagService.createMessageTag(template);
        Integer id = created.getId();
        em.flush();

        MessageTag stored = messageTagService.findTag(created.getTagId());
        stored.setLocked(false);
        messageTagService.updateMessageTag(stored);
        em.flush();
        em.clear();
        MessageTag reloaded = em.find(MessageTag.class, id);
        assertFalse(reloaded.isLocked());
        assertEquals(MessageTagType.PUBLIC, reloaded.getType());
    }

    // ------------------------------------------------------------------------------------------- //
    // belongsToAnotherDomain - the domain rule the tag endpoints and the message-archive import share
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void belongsToAnotherDomainIsTrueOnlyForAPublicTagOfADomainThatIsNotTheCurrentOne() {
        try {
            domainService.setDomainForCurrentThread("niord-nw");
            MessageTag publicNw = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
            MessageTag domainNw = messageTagService.createMessageTag(newTag(MessageTagType.DOMAIN, token()));
            em.flush();
            assertFalse(messageTagService.belongsToAnotherDomain(publicNw), "its own domain");

            domainService.setDomainForCurrentThread("niord-nm");
            assertTrue(messageTagService.belongsToAnotherDomain(publicNw), "another domain");
            assertFalse(messageTagService.belongsToAnotherDomain(domainNw),
                    "a DOMAIN tag is limited to its domain by the lookup, not by this rule");

            domainService.removeDomainForCurrentThread();
            assertTrue(messageTagService.belongsToAnotherDomain(publicNw), "no current domain is not the tag's");

            MessageTag noDomain = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
            em.flush();
            assertFalse(messageTagService.belongsToAnotherDomain(noDomain), "a PUBLIC tag of no domain");
            assertFalse(messageTagService.belongsToAnotherDomain(null), "no tag");
        } finally {
            domainService.removeDomainForCurrentThread();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteMessageTag
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteMessageTagRemovesRowAndReturnsTrue() {
        MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        Integer id = created.getId();
        em.flush();

        assertTrue(messageTagService.deleteMessageTag(created.getTagId()),
                "deleting an existing tag must return true");
        em.flush();
        em.clear();

        assertNull(em.find(MessageTag.class, id), "the deleted tag must be gone from the database");
    }

    @Test
    @Transactional
    void deleteMessageTagReturnsFalseForUnknownTagId() {
        assertFalse(messageTagService.deleteMessageTag("IT-unknown-" + UUID.randomUUID()),
                "deleting a non-existing tag must return false");
    }

    // ------------------------------------------------------------------------------------------- //
    // clearMessageTag
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void clearMessageTagEmptiesMessagesAndResetsCount() {
        Message message = persistMessage();
        MessageTag template = newTag(MessageTagType.PUBLIC, token());
        template.setMessages(new ArrayList<>(List.of(message)));
        MessageTag created = messageTagService.createMessageTag(template);
        Integer id = created.getId();
        em.flush();
        assertEquals(1, em.find(MessageTag.class, id).getMessageCount(),
                "precondition: the tag must hold one message");

        assertTrue(messageTagService.clearMessageTag(created.getTagId()),
                "clearing an existing tag must return true");
        em.flush();
        em.clear();

        MessageTag reloaded = em.find(MessageTag.class, id);
        assertEquals(0, reloaded.getMessageCount(), "clear must reset the message count to 0");
        assertTrue(reloaded.getMessages().isEmpty(), "clear must remove all messages from the tag");
    }

    @Test
    @Transactional
    void clearMessageTagReturnsFalseForUnknownTagId() {
        assertFalse(messageTagService.clearMessageTag("IT-unknown-" + UUID.randomUUID()),
                "clearing a non-existing tag must return false");
    }

    // ------------------------------------------------------------------------------------------- //
    // addMessageToTag / removeMessageFromTag
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void addMessageToTagThenRemoveUpdatesMessageCount() {
        Message message = persistMessage();
        MessageTag created = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        Integer id = created.getId();
        em.flush();

        messageTagService.addMessageToTag(created.getTagId(), List.of(message.getUid()));
        em.flush();
        em.clear();
        assertEquals(1, em.find(MessageTag.class, id).getMessageCount(),
                "adding a message must raise the message count to 1");

        messageTagService.removeMessageFromTag(created.getTagId(), List.of(message.getUid()));
        em.flush();
        em.clear();
        assertEquals(0, em.find(MessageTag.class, id).getMessageCount(),
                "removing the message must lower the message count back to 0");
    }

    @Test
    @Transactional
    void addMessageToUnknownTagThrows() {
        Message message = persistMessage();
        String unknown = "IT-unknown-" + UUID.randomUUID();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageTagService.addMessageToTag(unknown, List.of(message.getUid())));
        assertTrue(ex.getMessage().contains(unknown),
                "the error must mention the unknown tag ID, but was: " + ex.getMessage());
    }

    @Test
    @Transactional
    void removeMessageFromUnknownTagThrows() {
        Message message = persistMessage();
        String unknown = "IT-unknown-" + UUID.randomUUID();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> messageTagService.removeMessageFromTag(unknown, List.of(message.getUid())));
        assertTrue(ex.getMessage().contains(unknown),
                "the error must mention the unknown tag ID, but was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // createTempMessageTag
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createTempMessageTagSetsTypeAndFutureExpiry() {
        Date before = new Date();
        MessageTag created = messageTagService.createTempMessageTag(30, Collections.emptyList());
        Integer id = created.getId();
        em.flush();
        em.clear();

        MessageTag reloaded = em.find(MessageTag.class, id);
        assertEquals(MessageTagType.TEMP, reloaded.getType(), "a temp tag must have type TEMP");
        assertNotNull(reloaded.getExpiryDate(), "a temp tag must have an expiry date");
        assertTrue(reloaded.getExpiryDate().after(before),
                "the expiry date must be in the future");

        // 30 minutes from 'before' is the lower bound for the expiry; allow slack for clock drift.
        Calendar lowerBound = Calendar.getInstance();
        lowerBound.setTime(before);
        lowerBound.add(Calendar.MINUTE, 29);
        assertTrue(reloaded.getExpiryDate().after(lowerBound.getTime()),
                "a 30-minute TTL must push the expiry roughly 30 minutes out");
    }

    @Test
    @Transactional
    void createTempMessageTagDefaultsTtlWhenNull() {
        Date before = new Date();
        MessageTag created = messageTagService.createTempMessageTag(null, Collections.emptyList());
        Integer id = created.getId();
        em.flush();
        em.clear();

        MessageTag reloaded = em.find(MessageTag.class, id);
        // The default TTL is MessageTagService.TEMP_TAG_EXPIRY_MINUTES (5 minutes).
        Calendar lowerBound = Calendar.getInstance();
        lowerBound.setTime(before);
        lowerBound.add(Calendar.MINUTE, 4);
        Calendar upperBound = Calendar.getInstance();
        upperBound.setTime(before);
        upperBound.add(Calendar.MINUTE, 6);
        assertTrue(reloaded.getExpiryDate().after(lowerBound.getTime())
                        && reloaded.getExpiryDate().before(upperBound.getTime()),
                "a null TTL must default the expiry to ~5 minutes in the future");
    }

    @Test
    @Transactional
    void createTempMessageTagCountsResolvableMessages() {
        Message message = persistMessage();

        MessageTag created = messageTagService.createTempMessageTag(10, List.of(message.getUid()));
        Integer id = created.getId();
        assertEquals(1, created.getMessageCount(),
                "the temp tag must count the one resolvable message UID");
        em.flush();
        em.clear();

        assertEquals(1, em.find(MessageTag.class, id).getMessageCount(),
                "the persisted temp tag must hold one message");
    }

    @Test
    @Transactional
    void tempTagIsHiddenWhenUserIsNull() {
        // A TEMP tag belongs to the user who created it, like a PRIVATE one; with no current user it is
        // neither found by id nor listed by a TEMP search.
        MessageTag created = messageTagService.createTempMessageTag(10, Collections.emptyList());
        Integer id = created.getId();
        em.flush();

        assertNull(messageTagService.findTag(created.getTagId()),
                "a TEMP tag must not be visible when there is no authenticated user");
        assertTrue(messageTagService.searchMessageTags(
                        searchParams().types(java.util.Set.of(MessageTagType.TEMP))).isEmpty(),
                "a TEMP search must list nothing when there is no authenticated user");

        // ... but the row really was persisted - it is only hidden by access scoping.
        em.clear();
        assertNotNull(em.find(MessageTag.class, id), "the TEMP tag row must still exist in the database");
    }

    @Test
    @Transactional
    void createTempMessageTagWithEmptyUidsHasZeroCount() {
        MessageTag created = messageTagService.createTempMessageTag(10, Collections.emptyList());
        Integer id = created.getId();
        em.flush();
        em.clear();

        assertEquals(0, em.find(MessageTag.class, id).getMessageCount(),
                "a temp tag created from no UIDs must have message count 0");
    }

    // ------------------------------------------------------------------------------------------- //
    // removeExpiredMessageTags - the @Scheduled(cron="22 22 */1 * * ?") cron cleanup, invoked directly
    //
    // The package-private scheduled method is reachable from this same-package test. Only the cron
    // *firing time* is not asserted (the Quarkus scheduler is not driven); the cleanup behaviour — which
    // tags it deletes and which it spares — is the value, and is verified here by direct invocation.
    // See docs/untestable-code.md and docs/framework-coupled-tests.md.
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void removeExpiredMessageTagsDeletesOnlyTagsWithAPastExpiryDate() {
        // The named query is "expiryDate is not null and expiryDate < current_timestamp", so of three
        // otherwise identical PUBLIC tags only the past-expiry one must be purged. Day-sized offsets are
        // used so the DB-side current_timestamp comparison is robust to any JVM/MySQL timezone skew.
        Date past = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000L);    // 1 day ago
        Date future = new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000L);  // 1 day ahead

        MessageTag expired = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        expired.setExpiryDate(past);
        MessageTag notYetExpired = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        notYetExpired.setExpiryDate(future);
        MessageTag noExpiry = messageTagService.createMessageTag(newTag(MessageTagType.PUBLIC, token()));
        // noExpiry deliberately keeps a null expiryDate
        em.flush();

        Integer expiredId = expired.getId();
        Integer futureId = notYetExpired.getId();
        Integer noExpiryId = noExpiry.getId();

        messageTagService.removeExpiredMessageTags();
        em.flush();
        em.clear();

        assertNull(em.find(MessageTag.class, expiredId),
                "a tag whose expiryDate lies in the past must be purged by removeExpiredMessageTags");
        assertNotNull(em.find(MessageTag.class, futureId),
                "a tag whose expiryDate lies in the future must be left untouched");
        assertNotNull(em.find(MessageTag.class, noExpiryId),
                "a tag with no expiryDate must never be purged");
    }
}
