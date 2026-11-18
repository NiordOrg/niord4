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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.domain.DomainService;
import org.niord.core.message.*;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.model.DataFilter;
import org.niord.model.message.MessageVo;
import org.niord.model.message.Status;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST interface for searching messages.
 */
@RestController
@RequestMapping("/rest/messages")
@Transactional
@PermitAll
@SuppressWarnings("unused")
public class MessageSearchRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageSearchRestService.class);

    /** The most short-id matches one {@code /search-message-ids} request returns. The type-ahead asks for 10. */
    static final int MAX_MESSAGE_ID_GROUP_COUNT = 50;

    /** The longest free-text query a caller without an identity may run; a longer one is cut to this length. */
    static final int MAX_ANONYMOUS_QUERY_LENGTH = 256;

    private final MessageService messageService;

    private final MessageSearchService messageSearchService;

    private final MessageTagService messageTagService;

    private final PublicationMemberSetSource memberSetSource;

    private final DomainService domainService;

    private final DomainLookupService domainLookupService;

    private final UserService userService;

    MessageSearchRestService(MessageService messageService, MessageSearchService messageSearchService, MessageTagService messageTagService, PublicationMemberSetSource memberSetSource, DomainService domainService, DomainLookupService domainLookupService, UserService userService) {
        this.messageService = messageService;
        this.messageSearchService = messageSearchService;
        this.messageTagService = messageTagService;
        this.memberSetSource = memberSetSource;
        this.domainService = domainService;
        this.domainLookupService = domainLookupService;
        this.userService = userService;
    }

    /** Returns the domain used for searching messages **/
    private Domain searchDomain(MessageSearchParams params) {
        Domain searchDomain = null;

        // Check if a domain parameter has been specified
        if (StringUtils.isNotBlank(params.getDomain())) {
            searchDomain = domainLookupService.findByDomainId(params.getDomain());
        }

        // Fall back to the current domain
        if (searchDomain == null) {
            searchDomain = domainService.currentDomain();
        }

        return searchDomain;
    }

    /**
     * Main search method.
     * Validates the message search parameters and enforces security.
     *
     * @param params the search parameters
     * @return the search result
     */
    private PagedSearchResultVo<Message> search(MessageSearchParams params) throws Exception {

        Domain currentDomain = domainService.currentDomain();
        Domain searchDomain = searchDomain(params);

        // Resolve publication= before any of the decisions below, and refuse
        // rather than widen.
        //
        // The tier comes from the CALLER, not from the endpoint: this class is
        // @PermitAll, so an anonymous request reaches it. Hard-coding the
        // internal tier here would let anyone who guesses an id resolve an OPEN
        // issue or a DRAFT publication -- the contents of an issue nobody has
        // released.
        boolean identifiedCaller = userService.currentUser() != null;
        PublicationMemberSetSource.Audience audience = identifiedCaller
                ? PublicationMemberSetSource.Audience.INTERNAL
                : PublicationMemberSetSource.Audience.PUBLIC;

        if (!identifiedCaller) {
            // The author and comment filters work on internal fields the public model withholds (who
            // created or edited a message, whether it has staff comments), so a caller without an
            // identity cannot filter on them. A signed-in or ticketed caller keeps them: the admin
            // print and export send a role-less ticket, which names the user but grants no role.
            params.username(null).userType(null).commentsType(null);

            // Free text from such a caller is a list of plain words: the index gets no query syntax
            // from it (wildcards, regular expressions, fuzzy or range terms), and its length is
            // bounded. Staff keep the full syntax.
            if (StringUtils.isNotBlank(params.getQuery())) {
                params.query(StringUtils.left(params.getQuery(), MAX_ANONYMOUS_QUERY_LENGTH))
                        .literalQuery(true);
            }
        }

        MemberSetDesignation designation =
                memberSetSource.designate(params.getPublications(), audience);
        params.memberSetDesignation(designation);
        boolean memberSetDesignated = designation.designatesMemberSet();

        // Enforce security rules - depends on whether the current user is in the context of a domain or not.
        if (searchDomain != null) {

            /* TODO: Reconsider if we should really restrict messages by domain areas and categories
            // If no areas specified, use the ones of the search domain
            if (params.getAreaIds().isEmpty() && !searchDomain.getAreas().isEmpty()) {
                params.areaIds(
                        searchDomain.getAreas().stream()
                                .map(BaseEntity::getId)
                                .collect(Collectors.toSet())
                );
            }

            // If no categories specified, use the ones of the search domain
            if (params.getCategoryIds().isEmpty() && !searchDomain.getCategories().isEmpty()) {
                params.categoryIds(
                        searchDomain.getCategories().stream()
                                .map(BaseEntity::getId)
                                .collect(Collectors.toSet())
                );
            }
            */

            // Validate that the specified tags are allowed for the current user.
            //
            // Only the tag= parameter reaches here now. Publication-derived tags
            // used to be folded in above and validated against the search domain
            // with them -- which truncated an official issue for an admin browsing
            // in another domain. A designated member set is served verbatim: it is
            // an explicit list, and 17 live publications have no domain at all.
            if (!params.getTags().isEmpty()) {
                String[] tagIds = params.getTags().toArray(new String[params.getTags().size()]);
                params.tags(messageTagService.findTags(searchDomain, tagIds).stream()
                        .map(MessageTag::getTagId)
                        .collect(Collectors.toSet()));
            }

            // If nothing designates a member set and no valid tags are specified,
            // impose restrictions on statuses.
            //
            // A UNION, not a replacement. getTags() is fed from two sources here:
            // the tag= parameter and, until now, the publication conversion. Drop
            // the conversion and simply swap the test, and every plain tag= search
            // has the restriction block re-imposed on it -- cross-domain tagged
            // messages disappear and non-public statuses are stripped, from a
            // parameter this redesign is not supposed to touch.
            if (!memberSetDesignated && params.getTags().isEmpty()) {

                Set<String> domainSeries = searchDomain.getMessageSeries().stream()
                        .map(MessageSeries::getSeriesId)
                        .collect(Collectors.toSet());

                // Restrict message series IDs to valid ones for the search domain
                params.seriesIds(
                        params.getSeriesIds().stream()
                                .filter(domainSeries::contains)
                                .collect(Collectors.toSet())
                );
                // If no message series is specified, use the ones of the search domain
                if (params.getSeriesIds().isEmpty()) {
                    params.seriesIds(domainSeries);
                }

                // Restrict statuses to the public ones (published, cancelled, expired) unless the caller
                // is an authenticated USER whose current domain matches the search domain. The NiordDomain
                // header alone (which sets the current domain) is not proof of authorization, so the role
                // check mirrors the canonical MessageRestService.checkMessageViewingAccess model.
                if (!userService.isCallerInRole(Roles.USER)
                        || currentDomain == null
                        || !Objects.equals(searchDomain.getDomainId(), currentDomain.getDomainId())) {
                    params.getStatuses().removeIf(s -> !s.isPublic());
                }

                // If no valid status is defined return only published messages
                if (params.getStatuses().isEmpty()) {
                    params.getStatuses().add(Status.PUBLISHED);
                }
            }

        } else {
            // Return published messages if nothing narrows the search. Same union:
            // a designated member set counts, and so does a plain tag=.
            if (!memberSetDesignated && params.getTags().isEmpty()) {
                params.statuses(Collections.singleton(Status.PUBLISHED));
            }
        }

        // SECAUD-6: A caller that is not an authenticated USER of the search domain must never receive
        // non-public statuses - not even by supplying a PUBLIC/TEMP tag, which skips the no-tag status
        // floor above. The NiordDomain header / domain query param alone (which sets the current/search
        // domain) is not proof of authorization, so this final floor mirrors the no-tag gate and
        // MessageRestService.checkMessageViewingAccess. An authorized domain USER is unaffected and can
        // still search/export a tag's messages in any status.
        //
        // A DESIGNATED MEMBER SET IS EXEMPT FROM THE FLOOR, NOT FROM THE CEILING. The floor used to
        // cover publication= too, back when a publication was just a bag of message tags anyone could
        // name. It no longer is: the designation above was resolved at the caller's own tier -- an
        // anonymous caller gets the PUBLIC tier and can only designate a PUBLISHED issue or an ACTIVE
        // publication in a publishing category -- and the member list it carries was frozen at publish
        // time. That list is the authoritative contents of the issue, and it is what the issue's own PDF
        // was rendered from. Flooring it to PUBLISHED empties every historical issue, whose 2,324
        // members are EXPIRED or CANCELLED and not one PUBLISHED, and does so quietly.
        //
        // What a designation does NOT buy is a non-public status. status= is caller-supplied, so a
        // designated search that skipped this block entirely would carry publication=<a PUBLISHED
        // issue>&status=DRAFT to any member that has since moved backwards out of the public set. An
        // unauthorized caller's statuses are therefore INTERSECTED with the post-publication set
        // {PUBLISHED, EXPIRED, CANCELLED} instead of being floored to PUBLISHED alone: DRAFT, VERIFIED
        // and DELETED never reach such a caller, through a designation or otherwise. Plain searches keep
        // the floor exactly as before, and an authorized domain USER is unaffected by either.
        boolean authorizedDomainUser = searchDomain != null
                && currentDomain != null
                && userService.isCallerInRole(Roles.USER)
                && Objects.equals(searchDomain.getDomainId(), currentDomain.getDomainId());
        if (!authorizedDomainUser) {
            params.getStatuses().removeIf(s -> !s.isPublic());
            if (params.getStatuses().isEmpty()) {
                // Nothing was asked for, or nothing survived the intersection. A plain search falls back
                // to PUBLISHED as it always has; a designated one falls back to the whole
                // post-publication set, because that IS the default for a frozen member list -- an
                // empty status set means "no status predicate", which would put the non-public statuses
                // back, and PUBLISHED alone would empty the historical issues this exemption exists for.
                if (memberSetDesignated) {
                    for (Status s : Status.values()) {
                        if (s.isPublic()) {
                            params.getStatuses().add(s);
                        }
                    }
                } else {
                    params.getStatuses().add(Status.PUBLISHED);
                }
            }
        }

        // Adjust the date interval so that is the beginning of the from-date and end of the to-date.
        if (currentDomain != null) {
            params.adjustDateInterval(currentDomain.timeZone());
        }

        // Perform the search. The search that lets a failure through: an empty answer here must mean zero messages,
        // in the editors' list, the print, the export and the message mail alike
        long t0 = System.currentTimeMillis();
        PagedSearchResultVo<Message> searchResult = messageSearchService.searchOrFail(params);

        // Record a textual description of the search
        String description = params.toString();
        searchResult.setDescription(description);

        log.debug(String.format("Search [%s] returns %d of %d messages in %d ms",
                description, searchResult.getData().size(), searchResult.getTotal(), System.currentTimeMillis() - t0));
        return searchResult;
    }

    /**
     * Main search method.
     * Validates the message search parameters and enforces security.
     *
     * @param params the search parameters
     * @return the search result
     */
    public PagedSearchResultVo<MessageVo> searchMessages(MessageSearchParams params) throws Exception {

        DataFilter filter = ("map".equalsIgnoreCase(params.getViewMode()))
                ? Message.MESSAGE_MAP_FILTER.lang(params.getLanguage())
                : Message.MESSAGE_DETAILS_FILTER.lang(params.getLanguage()).user(userService.userResolver());
        return search(params).map(m -> m.toVo(MessageVo.class, filter));
    }

    /**
     * Main search method.
     * Validates the message search parameters and enforces security.
     *
     * @param params the search parameters
     * @return the search result
     */
    public PagedSearchResultVo<SystemMessageVo> searchSystemMessages(MessageSearchParams params) throws Exception {

        // A caller without an identity (the anonymous export.zip) gets the public details: no
        // promulgations (their texts and settings are internal) and no unacknowledged-comment count,
        // which Message.toVo sets on every SystemMessageVo whatever the filter. The repository paths
        // stay: the export needs them to collect the attachments, which are served anonymously anyway.
        boolean identifiedCaller = userService.currentUser() != null;
        DataFilter filter = ("map".equalsIgnoreCase(params.getViewMode()))
                ? Message.MESSAGE_MAP_FILTER.lang(params.getLanguage())
                : (identifiedCaller ? Message.MESSAGE_DETAILS_AND_PROMULGATIONS_FILTER : Message.MESSAGE_DETAILS_FILTER)
                        .lang(params.getLanguage()).user(userService.userResolver());
        return search(params).map(m -> {
            SystemMessageVo vo = m.toVo(SystemMessageVo.class, filter);
            if (!identifiedCaller) {
                vo.setUnackComments(null);
            }
            return vo;
        });
    }

    /**
     * Main search method
     */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public PagedSearchResultVo<MessageVo> search(HttpServletRequest request) throws Exception {
        MessageSearchParams params = MessageSearchParams.instantiate(domainService.currentDomain(), request);
        return searchMessages(params);
    }

    /**
     * Returns a list of message IDs (database ID or shortId) that - possibly partially - matches
     * real text.
     *
     * <p>The endpoint stays anonymous, because the admin application's guest mode offers the
     * reference picker that uses it. A caller without the USER role therefore gets the same public
     * floor as {@code /search}: only published, cancelled and expired messages are matched, and
     * {@code deleted=true} is ignored. {@code maxGroupCount} is kept within
     * [1, {@value #MAX_MESSAGE_ID_GROUP_COUNT}] for every caller.
     *
     * @param lang the language to return the title in
     * @param txt the text to match
     * @param maxGroupCount the max number of matching message IDs to return.
     * @param includeText whether to include the search text as a match
     * @param deleted whether to include deleted messages in the result
     * @return the search result
     */
    @GetMapping(value = "/search-message-ids", produces = "application/json;charset=UTF-8")
    public List<MessageIdMatch> searchMessageIds(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "txt", required = false) String txt,
            @RequestParam(name = "maxGroupCount", defaultValue = "10") int maxGroupCount,
            @RequestParam(name = "includeText", defaultValue = "true") boolean includeText,
            @RequestParam(name = "deleted", defaultValue = "false") boolean deleted) {

        boolean publicOnly = !userService.isCallerInRole(Roles.USER);
        int groupCount = Math.clamp(maxGroupCount, 1, MAX_MESSAGE_ID_GROUP_COUNT);
        return messageService.searchMessageIds(lang, txt, groupCount, includeText, deleted && !publicOnly, publicOnly);
    }

}
