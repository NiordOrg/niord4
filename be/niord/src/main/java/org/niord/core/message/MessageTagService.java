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

import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Scheduled;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.db.CriteriaHelper;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.service.BaseService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import jakarta.persistence.criteria.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

import static org.niord.core.message.vo.MessageTagVo.MessageTagType.*;
import static org.niord.model.search.PagedSearchParamsVo.SortOrder.DESC;

/**
 * Business interface for managing message tags.
 *
 * Message tags can either be tied to a user or be shared, so the set of tags
 * that a specific user can work on, is her own tags + the shared tags.
 */
@Service
@SuppressWarnings("unused")
public class MessageTagService extends BaseService {

    public static final int TEMP_TAG_EXPIRY_MINUTES = 5; // 5 minutes

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageTagService.class);

    @Inject
    UserService userService;

    @Inject
    DomainService domainService;

    /**
     * Returns the message tag with the given ID
     *
     * @param tagId the tag ID
     * @return the message tag with the given tag identifier or null if not found
     */
    public MessageTag findTag(String tagId) {
        List<MessageTag> tags = findTags(tagId);
        return tags.isEmpty() ? null : tags.get(0);
    }

    /**
     * Returns the message tag with the given ID
     *
     * @param domain the domain to check for
     * @param tagId the tag ID
     * @return the message tag with the given tag identifier or null if not found
     */
    public MessageTag findTag(Domain domain, String tagId) {
        List<MessageTag> tags = findTags(domain, tagId);
        return tags.isEmpty() ? null : tags.get(0);
    }

    /**
     * Returns the message tags with the given tag IDs
     * @param tagIds the tag IDs
     * @return the message tags with the given tag identifiers
     */
    public List<MessageTag> findTags(String... tagIds) {
        Domain domain = domainService.currentDomain();
        return findTags(domain, tagIds);
    }

    /**
     * Returns the message tags with the given tag IDs
     * @param domain the domain to check for
     * @param tagIds the tag IDs
     * @return the message tags with the given tag identifiers
     */
    public List<MessageTag> findTags(Domain domain, String... tagIds) {
        if (tagIds == null || tagIds.length == 0) {
            return Collections.emptyList();
        }

        User user = userService.currentUser();

        Set<String> idSet = new HashSet<>(Arrays.asList(tagIds));
        return em.createNamedQuery("MessageTag.findTagsByTagIds", MessageTag.class)
                .setParameter("tagIds", idSet)
                .getResultList()
                .stream()
                .filter(t -> validateAccess(t, user, domain))
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Returns the message tags with the given type and names
     * @param type the tag type
     * @param names the tag names
     * @return the message tags with the given type and tag names
     */
    public List<MessageTag> findTagsByTypeAndName(MessageTagType type, String... names) {
        if (type == null || names == null || names.length == 0) {
            return Collections.emptyList();
        }

        Set<String> nameSet = new HashSet<>(Arrays.asList(names));
        return em.createNamedQuery("MessageTag.findTagsByTypeAndName", MessageTag.class)
                .setParameter("type", type)
                .setParameter("names", nameSet)
                .getResultList();
    }

    /**
     * Validate that the user has access to the given tag. PRIVATE and TEMP tags belong to the user who
     * created them: a TEMP tag is the admin application's short-lived selection for a print, an export, a
     * mail or an area sort - often of drafts - and only its creator ever uses it. DOMAIN tags belong to
     * their domain, and PUBLIC tags are shared.
     */
    private boolean validateAccess(MessageTag tag, User user, Domain domain) {
        if (tag.getType() == PRIVATE || tag.getType() == TEMP) {
            return user != null && tag.getUser() != null && user.getId().equals(tag.getUser().getId());
        } else if (tag.getType() == DOMAIN) {
            return domain != null && tag.getDomain() != null && domain.getId().equals(tag.getDomain().getId());
        }
        return true;
    }

    /**
     * Returns whether the tag is a PUBLIC tag that records a domain other than the current one. Everyone may
     * read such a tag, but it is that domain's list, so it is changed from that domain only: by the tag
     * endpoints and by the message-archive import, which adds messages to it. A PUBLIC tag without a domain (the
     * ones the publication code creates) belongs to no domain and is not another's; PRIVATE, TEMP and DOMAIN tags
     * are already limited to their owner or domain by {@link #findTags(Domain, String...)}.
     *
     * @param tag the tag, or null
     * @return true when the tag is a PUBLIC tag of another domain, or of a domain while no domain is current
     */
    public boolean belongsToAnotherDomain(MessageTag tag) {
        if (tag == null || tag.getType() != PUBLIC || tag.getDomain() == null) {
            return false;
        }
        Domain domain = domainService.currentDomain();
        return domain == null || !Objects.equals(domain.getId(), tag.getDomain().getId());
    }

    /**
     * Returns the message tags which contain the message with the given ID
     * @param messageUid the tag IDs
     * @return the message tags which contain the message with the given ID
     */
    public List<MessageTag> findTagsByMessageId(String messageUid) {
        if (StringUtils.isBlank(messageUid)) {
            return Collections.emptyList();
        }

        User user = userService.currentUser();
        Domain domain = domainService.currentDomain();
        return em.createNamedQuery("MessageTag.findTagsByMessageUid", MessageTag.class)
                .setParameter("messageUid", messageUid)
                .getResultList()
                .stream()
                .filter(t -> validateAccess(t, user, domain))
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Searches for message tags matching the given search parameters
     *
     * @param params the search parameters
     * @return the search result
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public List<MessageTag> searchMessageTags(MessageTagSearchParams params) {
        User user = userService.currentUser();
        Domain domain = domainService.currentDomain();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<MessageTag> query = cb.createQuery(MessageTag.class);

        Root<MessageTag> tagRoot = query.from(MessageTag.class);

        // Build the predicate
        CriteriaHelper<MessageTag> criteriaHelper = new CriteriaHelper<>(cb, query);

        // Name filtering
        criteriaHelper.like(tagRoot.get("name"), params.getName());

        // Locked filtering
        criteriaHelper.equals(tagRoot.get("locked"), params.getLocked());

        // Type filtering
        Set<MessageTagType> types = params.getTypes() != null ? new HashSet<>(params.getTypes()) : new HashSet<>();
        if (types.isEmpty()) {
            types.add(PUBLIC);
            types.add(DOMAIN);
            types.add(PRIVATE);
        }
        List<Predicate> typePredicates = new LinkedList<>();
        if (types.contains(PUBLIC)) {
            typePredicates.add(cb.equal(tagRoot.get("type"), MessageTagType.PUBLIC));
        }
        if (types.contains(DOMAIN) && domain != null) {
            Join<MessageTag, Domain> domains = tagRoot.join("domain", JoinType.LEFT);
            typePredicates.add(cb.and(
                    cb.equal(tagRoot.get("type"), MessageTagType.DOMAIN),
                    cb.equal(domains.get("id"), domain.getId())));
        }
        // PRIVATE and TEMP tags are listed to their creator only (see validateAccess), through one join
        Join<MessageTag, User> users = (types.contains(PRIVATE) || types.contains(TEMP)) && user != null
                ? tagRoot.join("user", JoinType.LEFT)
                : null;
        if (types.contains(PRIVATE) && users != null) {
            typePredicates.add(cb.and(
                    cb.equal(tagRoot.get("type"), MessageTagType.PRIVATE),
                    cb.equal(users.get("id"), user.getId())));
        }
        if (types.contains(TEMP) && users != null) {
            typePredicates.add(cb.and(
                    cb.equal(tagRoot.get("type"), MessageTagType.TEMP),
                    cb.equal(users.get("id"), user.getId())));
        }
        criteriaHelper.add(cb.or(typePredicates.toArray(new Predicate[typePredicates.size()])));

        // Compute the sorting
        List<Order> sortOrders = new ArrayList<>();
        Order nameAscSortOrder = cb.asc(cb.lower(tagRoot.get("name")));
        if (params.sortByType()) {
            Expression sortBy = tagRoot.get("type");
            sortOrders.add(params.getSortOrder() == DESC ? cb.desc(sortBy) : cb.asc(sortBy));
            sortOrders.add(nameAscSortOrder);
        } else if (params.sortByCreated()) {
            Expression sortBy = tagRoot.get("created");
            sortOrders.add(params.getSortOrder() == DESC ? cb.desc(sortBy) : cb.asc(sortBy));
            sortOrders.add(nameAscSortOrder);
        } else if (params.sortByExpiryDate()) {
            Expression sortBy = tagRoot.get("expiryDate");
            sortOrders.add(params.getSortOrder() == DESC ? cb.desc(sortBy) : cb.asc(sortBy));
            sortOrders.add(nameAscSortOrder);
        } else if (params.sortByMessageCount()) {
            Expression sortBy = tagRoot.get("messageCount");
            sortOrders.add(params.getSortOrder() == DESC ? cb.desc(sortBy) : cb.asc(sortBy));
            sortOrders.add(nameAscSortOrder);
        } else {
            if (StringUtils.isNotBlank(params.getName())) {
                sortOrders.add(cb.asc(cb.locate(cb.lower(tagRoot.get("name")), params.getName().toLowerCase())));
            }
            String name = StringUtils.defaultIfBlank(params.getName(), "");
            Expression sortBy = cb.lower(tagRoot.get("name"));
            sortOrders.add(params.getSortOrder() == DESC ? cb.desc(sortBy) : cb.asc(sortBy));
        }

        // Complete the query. No DISTINCT: the only joins (domain, user) are to-one LEFT joins that cannot
        // fan the tag root into duplicate rows, so DISTINCT was redundant. It also broke on PostgreSQL, which
        // (unlike MySQL) requires every ORDER BY expression of a SELECT DISTINCT to appear in the select list
        // — and the name-relevance ordering (lower/locate) is not selected.
        query.select(tagRoot)
                .where(criteriaHelper.where())
                .orderBy(sortOrders);

        // Execute the query and update the search result
        return em.createQuery(query)
                .setMaxResults(params.getMaxSize())
                .getResultList();
    }

    /**
     * Creates a new message tag from the given template
     * @param tag the new message tag
     * @return the persisted message tag
     */
    @Transactional
    public MessageTag createMessageTag(MessageTag tag) {

        // Ensure that is has a proper tag ID
        tag.checkAssignTagId();

        MessageTag original = findTag(tag.getTagId());
        if (original != null) {
            throw new IllegalArgumentException("Cannot create message tag with duplicate message tag IDs"
                    + tag.getTagId());
        }

        tag.setUser(userService.currentUser());
        tag.setDomain(domainService.currentDomain());

        // Replace the messages with the persisted messages
        tag.setMessages(persistedList(Message.class, tag.getMessages()));

        log.info("Creating new message tag " + tag.getTagId());
        return saveEntity(tag);
    }

    /**
     * Creates a new temporary, short-lived message tag from the given messages
     * @param ttl time-to-live in minutes
     * @param messageUids the new message UIDs
     * @return the persisted message tag
     */
    @Transactional
    public MessageTag createTempMessageTag(Integer ttl, List<String> messageUids) {
        // Compute expiry time
        int minutes = ttl == null ? TEMP_TAG_EXPIRY_MINUTES : ttl;
        Calendar expiryTime = Calendar.getInstance();
        expiryTime.add(Calendar.MINUTE, minutes);

        // Create the temporary
        MessageTag tag = new MessageTag();
        tag.setType(TEMP);
        tag.setExpiryDate(expiryTime.getTime());
        tag.setUser(userService.currentUser());
        tag.setDomain(domainService.currentDomain());

        // Add the messages to the tag
        tag.setMessages(messagesForUids(messageUids));
        tag.updateMessageCount();

        tag = saveEntity(tag);
        log.info("Created temp message tag " + tag.getTagId() + " for " + tag.getMessageCount() + " messages");
        return tag;
    }

    /**
     * Updates an existing message tag from the given template. The tag keeps its user and its domain. A change
     * of type is refused, for every caller, when it would leave the tag with nobody to manage it: PRIVATE or TEMP
     * on a tag with no user (no one would find it) and DOMAIN on a tag with no domain (no domain would find it),
     * and a missing type. Who may change a type at all is the caller's to check (the tag endpoint lets only an
     * admin, or the owner of a PRIVATE or TEMP tag, do it). Only a change of type is checked, so a template that
     * is the stored tag itself (an unlock) passes as before.
     *
     * @param tag the message tag to update
     * @return the persisted message tag
     * @throws IllegalArgumentException when the tag does not exist or the caller cannot see it, or the type change
     *                                  is refused
     */
    @Transactional
    public MessageTag updateMessageTag(MessageTag tag) {
        MessageTag original = findTag(tag.getTagId());
        if (original == null) {
            throw new IllegalArgumentException("Cannot update non-existing message tag"
                    + tag.getTagId());
        }
        if (tag.getType() != original.getType()) {
            checkTypeChange(original, tag.getType());
        }

        original.setExpiryDate(tag.getExpiryDate());
        original.setType(tag.getType());
        original.setName(tag.getName());
        original.setLocked(tag.isLocked());

        log.info("Updating message tag " + original.getTagId());
        return saveEntity(original);
    }

    /**
     * Refuses a type that would leave the stored tag with nobody to manage it.
     *
     * @param original the stored tag
     * @param type the new type
     * @throws IllegalArgumentException when the type is missing, or would make the tag one that nobody finds
     */
    private static void checkTypeChange(MessageTag original, MessageTagType type) {
        if (type == null) {
            throw new IllegalArgumentException("A tag must have a type");
        }
        if ((type == PRIVATE || type == TEMP) && original.getUser() == null) {
            throw new IllegalArgumentException("A tag without a user cannot be made " + type
                    + ": no one could manage it");
        }
        if (type == DOMAIN && original.getDomain() == null) {
            throw new IllegalArgumentException("A tag without a domain cannot be made DOMAIN: no one could manage it");
        }
    }

    /**
     * Deletes the message tag with the given tag ID
     * @param tagId the ID of the message tag to delete
     * @return if the message tag was deleted
     */
    @Transactional
    public boolean deleteMessageTag(String tagId) {

        MessageTag original = findTag(tagId);
        if (original != null) {
            log.info("Removing message tag " + tagId);
            remove(original);
            return true;
        }
        return false;
    }

    /**
     * Clears all messages from the message tag with the given tag ID
     * @param tagId the ID of the message tag to clear
     * @return if the message tag was deleted
     */
    @Transactional
    public boolean clearMessageTag(String tagId) {

        MessageTag original = findTag(tagId);
        if (original != null) {
            log.info("Clearing message tag " + tagId);
            original.getMessages().clear();
            original.updateMessageCount();
            // NB: 'original' is already a managed entity (loaded via findTag in this transaction),
            // so its dirty state — including the now-empty @ManyToMany messages collection — is
            // flushed automatically. We deliberately do NOT call saveEntity()/em.merge() here:
            // under Hibernate ORM 7 (the Spring Boot port; Quarkus used Hibernate 6) merging an
            // already-managed entity whose collection was originally assigned via setMessages(new
            // ArrayList(...)) (as createMessageTag does) suppresses the join-table DELETE, so the
            // scalar messageCount would be reset to 0 while the MessageTag_Message rows survived,
            // leaving the tag's messages non-empty on reload. Relying on automatic dirty-checking
            // of the managed entity issues the DELETE correctly.
            return true;
        }
        return false;
    }

    /**
     * Adds messages to the given tag
     * @param tagId the ID of the message tag to add the message to
     * @param messageUids the UIDs of the messages to add
     * @return the updated message tag
     */
    @Transactional
    public MessageTag addMessageToTag(String tagId, List<String> messageUids) {
        MessageTag tag = findTag(tagId);
        if (tag == null) {
            throw new IllegalArgumentException("No message tag with ID " + tagId);
        }

        int prevMsgCnt = tag.getMessages().size();
        for (Message message : messagesForUids(messageUids)) {
            if (!tag.getMessages().contains(message)) {
                tag.getMessages().add(message);
            }
        }

        if (tag.getMessages().size() != prevMsgCnt) {
            tag.updateMessageCount();
            tag = saveEntity(tag);
            log.info("Added " + (tag.getMessages().size() - prevMsgCnt) + " messages to tag " + tag.getName());
        }

        return tag;
    }

    /**
     * Removes messages from the given tag
     * @param tagId the ID of the message tag to remove the message from
     * @param messageUids the UIDs the messages to remove
     * @return the updated message tag
     */
    @Transactional
    public MessageTag removeMessageFromTag(String tagId, List<String> messageUids) {
        MessageTag tag = findTag(tagId);
        if (tag == null) {
            throw new IllegalArgumentException("No message tag with ID " + tagId);
        }

        int prevMsgCnt = tag.getMessages().size();
        for (Message message : messagesForUids(messageUids)) {
            if (tag.getMessages().contains(message)) {
                tag.getMessages().remove(message);
            }
        }

        if (tag.getMessages().size() != prevMsgCnt) {
            tag.updateMessageCount();
            tag = saveEntity(tag);
            log.info("Removed " + (prevMsgCnt - tag.getMessages().size()) + " messages from tag " + tag.getName());
        }

        return tag;
    }

    /**
     * Returns the messages with the given UIDs
     *
     * @param uids the list of message UIDs
     * @return the messages with the given UIDs
     */
    public List<Message> messagesForUids(List<String> uids) {
        if (uids == null || uids.isEmpty()) {
            return Collections.emptyList();
        }
        return em.createNamedQuery("Message.findByUids", Message.class)
                .setParameter("uids", uids)
                .getResultList();
    }

    /**
     * Every hour, expired message tags will be removed
     */
    @Scheduled(cron="22 22 */1 * * ?")
    void removeExpiredMessageTags() {
        List<MessageTag> expiredTags = em.createNamedQuery("MessageTag.findExpiredMessageTags", MessageTag.class)
                .getResultList();
        if (!expiredTags.isEmpty()) {
            expiredTags.forEach(this::remove);
            log.info("Removed " + expiredTags.size() + " expired message tags");
        }
    }

}
