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

package org.niord.web;

import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.Message;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagSearchParams;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.MessageTagVo;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.model.search.PagedSearchParamsVo.SortOrder;
import org.slf4j.Logger;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.niord.core.message.vo.MessageTagVo.MessageTagType.DOMAIN;
import static org.niord.core.message.vo.MessageTagVo.MessageTagType.PRIVATE;
import static org.niord.core.message.vo.MessageTagVo.MessageTagType.PUBLIC;
import static org.niord.core.message.vo.MessageTagVo.MessageTagType.TEMP;

/**
 * API for accessing message tags, used for grouping a fixed set of messages.
 */
@RestController
@RequestMapping("/rest/tags")
@Transactional
@PermitAll
public class MessageTagRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageTagRestService.class);

    private final MessageTagService messageTagService;

    private final DomainService domainService;

    private final UserService userService;

    MessageTagRestService(MessageTagService messageTagService, DomainService domainService, UserService userService) {
        this.messageTagService = messageTagService;
        this.domainService = domainService;
        this.userService = userService;
    }

    /** Returns the tags with the given IDs */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public List<MessageTagVo> searchTags(
            @RequestParam(name = "name", defaultValue = "") String name,
            @RequestParam(name = "locked", required = false) Boolean locked,
            @RequestParam(name = "type", required = false) Set<MessageTagVo.MessageTagType> types,
            @RequestParam(name = "sortBy", defaultValue = "name") String sortBy,
            @RequestParam(name = "sortOrder", defaultValue = "ASC") SortOrder sortOrder,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {

        // Leave DOMAIN out of the query - rather than filtering the result - so the limit still holds. An
        // empty type set means PUBLIC + DOMAIN + PRIVATE to the service, so it is spelled out here.
        if (!mayViewDomainTags()) {
            types = (types == null || types.isEmpty())
                    ? EnumSet.of(PUBLIC, PRIVATE)
                    : types.stream().filter(t -> t != DOMAIN).collect(Collectors.toSet());
            if (types.isEmpty()) {
                return List.of();
            }
        }

        MessageTagSearchParams params = new MessageTagSearchParams();
        params.name(name)
                .locked(locked)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .maxSize(limit);
        if (types != null) {
            params.types(types);
        }

        return messageTagService.searchMessageTags(params).stream()
                .map(MessageTag::toVo)
                .collect(Collectors.toList());
    }

    /** Returns the tags which contain the message with the given ID */
    @GetMapping(value = "/message/{messageUid}", produces = "application/json;charset=UTF-8")
    public List<MessageTagVo> findTagsByMessageId(
            @PathVariable("messageUid") String messageUid,
            @RequestParam(name = "locked", required = false) Boolean locked
    ) {
        boolean domainTags = mayViewDomainTags();
        return messageTagService.findTagsByMessageId(messageUid).stream()
                .filter(m -> locked == null || m.isLocked() == locked)
                .filter(t -> domainTags || t.getType() != DOMAIN)
                .map(MessageTag::toVo)
                .collect(Collectors.toList());
    }

    /** Returns the tags with the given IDs */
    @GetMapping(value = "/tag/{tagIds}", produces = "application/json;charset=UTF-8")
    public List<MessageTagVo> getTags(@PathVariable("tagIds") String tagIds) {
        boolean domainTags = mayViewDomainTags();
        return messageTagService.findTags(tagIds.split(",")).stream()
                .filter(t -> domainTags || t.getType() != DOMAIN)
                .map(MessageTag::toVo)
                .collect(Collectors.toList());
    }

    /** Creates a new tag from the given template; a tag without a type or a name answers 400 */
    @PostMapping(value = {"/tag", "/tag/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public MessageTagVo createTag(@RequestBody MessageTagVo tag) {
        if (tag.getType() == null) {
            throw new NiordWebException("A tag must have a type", 400);
        }
        if (tag.getName() == null || tag.getName().isBlank()) {
            throw new NiordWebException("A tag must have a name", 400);
        }
        return messageTagService.createMessageTag(new MessageTag(tag)).toVo();
    }

    /**
     * Updates a tag from the given template. A tag without a type or a name answers 400, as a create does. The
     * type of a DOMAIN or PUBLIC tag is changed by an admin only (403 otherwise); a PRIVATE or TEMP tag's type is
     * its owner's, since only its owner finds it. A type that would leave the tag with nobody to manage it is
     * refused by {@code MessageTagService.updateMessageTag} for everyone (400).
     */
    @PutMapping(value = "/tag/{tagId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public MessageTagVo updateTag(@PathVariable("tagId") String tagId, @RequestBody MessageTagVo tag) {
        if (!Objects.equals(tagId, tag.getTagId())) {
            throw new NiordWebException(400);
        }
        if (tag.getType() == null) {
            throw new NiordWebException("A tag must have a type", 400);
        }
        if (tag.getName() == null || tag.getName().isBlank()) {
            throw new NiordWebException("A tag must have a name", 400);
        }
        MessageTag stored = requireModifiable(tagId);
        if (stored != null && stored.getType() != tag.getType()
                && stored.getType() != PRIVATE && stored.getType() != TEMP
                && !userService.isCallerInRole(Roles.ADMIN)) {
            throw new NiordWebException("Only admins can change the type of a shared tag", 403);
        }
        return messageTagService.updateMessageTag(new MessageTag(tag)).toVo();
    }

    /** Deletes the tag with the given ID */
    @DeleteMapping("/tag/{tagId}")
    @RolesAllowed(Roles.USER)
    public boolean deleteTag(@PathVariable("tagId") String tagId) {
        log.info("Deleting tag " + tagId);
        requireModifiable(tagId);
        return messageTagService.deleteMessageTag(tagId);
    }

    /**
     * Unlocks the tag with the given ID. An admin unlocks a PUBLIC tag that records a domain from that
     * domain only, as {@link #requireModifiable} requires for every other change; an unknown tag, or one
     * the caller cannot see, answers 404.
     */
    @PutMapping(value = "/unlock-tag/{tagId}", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public MessageTagVo unlockTag(@PathVariable("tagId") String tagId) {
        MessageTag tag = messageTagService.findTag(tagId);
        if (tag == null) {
            throw new NiordWebException("No such tag", 404);
        }
        requireTagDomain(tag);
        if (tag.isLocked()) {
            tag.setLocked(false);
            messageTagService.updateMessageTag(tag);
            log.info("Unlocked tag " + tagId);
        }
        return tag.toVo();
    }

    /** Creates a new tag from the given template */
    @PostMapping(value = {"/temp-tag", "/temp-tag/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public MessageTagVo createTempTag(@RequestParam(name = "ttl", required = false) Integer ttl, @RequestBody List<String> messageUids) {
        checkMessageViewingAccess(messageUids);
        return messageTagService.createTempMessageTag(ttl, messageUids).toVo();
    }

    /** Clears messages from the given tag */
    @DeleteMapping("/tag/{tagId}/messages")
    @RolesAllowed(Roles.USER)
    public boolean clearTag(@PathVariable("tagId") String tagId) {
        log.info("Clearing tag " + tagId);
        requireModifiable(tagId);
        return messageTagService.clearMessageTag(tagId);
    }

    /** Adds messages to the given tag */
    @PutMapping(value = "/tag/{tagId}/add-messages",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public MessageTagVo addMessageToTag(@PathVariable("tagId") String tagId, @RequestBody List<String> messageUids) {
        log.info("Adding messages " + messageUids + " to tag " + tagId);

        requireModifiable(tagId);
        checkMessageViewingAccess(messageUids);
        return messageTagService.addMessageToTag(tagId, messageUids).toVo();
    }

    /** Removes messages from the given tag */
    @PutMapping(value = "/tag/{tagId}/remove-messages",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public MessageTagVo removeMessageFromTag(@PathVariable("tagId") String tagId, @RequestBody List<String> messageUids) {
        log.info("Removing messages " + messageUids + " from tag " + tagId);

        requireModifiable(tagId);
        checkMessageViewingAccess(messageUids);
        return messageTagService.removeMessageFromTag(tagId, messageUids).toVo();
    }

    /**
     * Returns whether the caller may see DOMAIN tags. {@code MessageTagService} already limits a DOMAIN tag
     * to the current domain, but that domain is named by the unauthenticated {@code NiordDomain} header;
     * the user role is what proves the caller belongs to it, since {@code NiordDomainFilter} only adds the
     * roles the verified token holds on the domain the header names.
     */
    private boolean mayViewDomainTags() {
        return userService.isCallerInRole(Roles.USER);
    }

    /**
     * Checks that the caller may change the tag with the given ID - its fields, its messages or its
     * existence - and throws a 403 otherwise:
     * <ul>
     *     <li>A locked tag is changed by an admin only. Publication tags are PUBLIC and locked; the lock is
     *         what keeps a released issue's message list fixed. The persisted flag is checked, not the
     *         request body's, so any user may still lock a tag, but only an admin can unlock one - here or
     *         through {@code /unlock-tag}.</li>
     *     <li>Except a PRIVATE or TEMP tag, which is its owner's, locked or not. {@code MessageTagService}
     *         finds such a tag for its owner only, so the caller is the owner; and no admin can find it, so
     *         an owner who locked it could otherwise never unlock, change or delete it again.</li>
     *     <li>A PUBLIC tag that records a domain is changed from that domain only. Everyone may read it, but
     *         it is that domain's list. A PUBLIC tag without a domain (the ones the publication code
     *         creates) has only the lock to protect it; PRIVATE, TEMP and DOMAIN tags are already limited
     *         to their owner or domain by {@code MessageTagService}.</li>
     * </ul>
     * An unknown tag, or one the caller cannot see, passes, and the service answers as it always has.
     *
     * @param tagId the ID of the tag to change
     * @return the stored tag, or null when it is unknown or the caller cannot see it
     */
    private MessageTag requireModifiable(String tagId) {
        MessageTag tag = messageTagService.findTag(tagId);
        if (tag == null) {
            return null;
        }
        boolean ownersTag = tag.getType() == PRIVATE || tag.getType() == TEMP;
        if (tag.isLocked() && !ownersTag && !userService.isCallerInRole(Roles.ADMIN)) {
            throw new NiordWebException("Only admins can change a locked tag", 403);
        }
        requireTagDomain(tag);
        return tag;
    }

    /**
     * Checks that a PUBLIC tag that records a domain is changed from that domain, and throws a 403
     * otherwise. The domain part of {@link #requireModifiable}, which {@code /unlock-tag} applies too; the
     * test itself is {@code MessageTagService.belongsToAnotherDomain}, which the message-archive import shares.
     *
     * @param tag the tag to change
     */
    private void requireTagDomain(MessageTag tag) {
        if (messageTagService.belongsToAnotherDomain(tag)) {
            throw new NiordWebException("The tag belongs to another domain", 403);
        }
    }

    /**
     * Validates that the current user has viewing access to each of the messages with the given UIDs,
     * i.e. that each message is either public (published, cancelled or expired), belongs to a message
     * series of the user's current domain, or is already associated with a public tag. Mirrors
     * {@code MessageRestService.checkMessageViewingAccess}; the user role that gate asks for is
     * already guaranteed by the {@code @RolesAllowed(Roles.USER)} on the calling endpoints.<br>
     * Throws a 403 response code error if access is denied to any of the messages.
     * <p>
     * TAG-3: without this check any authenticated USER could group arbitrary message UIDs — including
     * other domains' unpublished drafts — into a tag, and e.g. leak such a draft by adding it to a
     * PUBLIC tag, which grants everyone viewing access to it. Unknown UIDs resolve to no message and
     * are left for the service to ignore silently, matching the existing endpoint behaviour.
     *
     * @param messageUids the UIDs of the messages to validate access to
     */
    private void checkMessageViewingAccess(List<String> messageUids) {
        Domain domain = domainService.currentDomain();
        for (Message message : messageTagService.messagesForUids(messageUids)) {
            // 1) Always grant access if the message is published, cancelled or expired
            if (message.getStatus().isPublic()) {
                continue;
            }
            // 2) Grant access if the current domain of the user matches the message series of the message
            if (domain != null && domain.containsMessageSeries(message.getMessageSeries())) {
                continue;
            }
            // 3) Grant access if the message is associated with a public tag
            if (message.getTags().stream().anyMatch(t -> t.getType() == PUBLIC)) {
                continue;
            }
            throw new NiordWebException(403);
        }
    }

}
