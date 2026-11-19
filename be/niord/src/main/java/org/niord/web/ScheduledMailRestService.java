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

package org.niord.web;

import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailSearchParams;
import org.niord.core.mail.ScheduledMailService;
import org.niord.core.mail.vo.ScheduledMailVo;
import org.niord.core.mail.vo.SendAgainRequestVo;
import org.niord.core.mail.vo.SendAgainResultVo;
import org.niord.core.user.Roles;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.core.util.TimeUtils;
import org.niord.model.DataFilter;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * REST interface for searching scheduled e-mails, and for sending them again.
 */
@RestController
@RequestMapping("/rest/scheduled-mails")
@Transactional
@SuppressWarnings("unused")
public class ScheduledMailRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScheduledMailRestService.class);

    /** The most mails the user's own list returns */
    static final int MY_MAILS_MAX = 100;

    /** The longest period, in days, the user's own list covers */
    static final int MY_MAILS_MAX_DAYS = 30;

    private final ScheduledMailService scheduledMailService;

    private final UserService userService;

    ScheduledMailRestService(ScheduledMailService scheduledMailService, UserService userService) {
        this.scheduledMailService = scheduledMailService;
        this.userService = userService;
    }

    /**
     * Returns the current user's own queued mails of the last {@code days} days (1 to 30, a value outside is
     * clamped), newest first, at most 100, without the mail's text.
     * <p>
     * A mail is the user's own when the address it was queued with as its sender is the user's address now (the
     * mail dialog and a comment's mail record it). The answer holds each mail's status, attempts and last error,
     * so the sender learns whether the mail was sent; the last error is the mail server's own message, which may
     * name the mail relay's host and port. A user without a name and an e-mail address has no sender address and
     * gets an empty list.
     */
    @GetMapping(value = "/mine", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public List<ScheduledMailVo> mine(@RequestParam(name = "days", defaultValue = "7") int days) {
        User user = userService.currentUser();
        if (user == null) {
            return List.of();
        }
        int period = Math.clamp(days, 1, MY_MAILS_MAX_DAYS);
        Date since = TimeUtils.add(new Date(), Calendar.DATE, -period);
        DataFilter dataFilter = DataFilter.get();
        return scheduledMailService.findBySender(user.computeInternetAddress(), since, MY_MAILS_MAX).stream()
                .map(m -> {
                    // The user is not shown the user name of the system administrator who sent the mail again
                    ScheduledMailVo mail = m.toVo(dataFilter);
                    mail.setSentAgainBy(null);
                    return mail;
                })
                .toList();
    }

    /**
     * Searches scheduled mails based on the given search parameters
     */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public PagedSearchResultVo<ScheduledMailVo> search(
            @RequestParam(name = "recipient", required = false) String recipient,
            @RequestParam(name = "sender", required = false) String sender,
            @RequestParam(name = "subject", required = false) String subject,
            @RequestParam(name = "status", required = false) ScheduledMail.Status status,
            @RequestParam(name = "from", required = false) Long from,
            @RequestParam(name = "to", required = false) Long to,
            @RequestParam(name = "maxSize", defaultValue = "100") int maxSize,
            @RequestParam(name = "page", defaultValue = "0") int page) {

        ScheduledMailSearchParams params = new ScheduledMailSearchParams()
                .recipient(recipient)
                .sender(sender)
                .subject(subject)
                .status(status)
                .from(from)
                .to(to);
        params.maxSize(maxSize).page(page);

        DataFilter dataFilter = DataFilter.get();
        return scheduledMailService.search(params)
                .map(m -> m.toVo(dataFilter));
    }

    /**
     * Returns the scheduled mail with the given ID
     */
    @GetMapping(value = "/scheduled-mail/{scheduledMailId}", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public ScheduledMailVo getScheduledMail(@PathVariable("scheduledMailId") Integer scheduledMailId) throws Exception {
        ScheduledMail mail = scheduledMailService.getScheduledMail(scheduledMailId);
        if (mail == null) {
            throw new NiordWebException(404);
        }
        return mail.toVo(DataFilter.get().fields(DataFilter.DETAILS));
    }

    /**
     * Sends held, sent or failed mails again, as the Mail Queue page asks: each named mail is put back in the queue
     * as it was made, and the sender job sends it within a minute or two under the mail filter in force then
     * ({@link ScheduledMailService#sendAgain(List, String)}). At most {@link ScheduledMailService#SEND_AGAIN_MAX}
     * mails a request.
     * <p>
     * 400 for a request that names no mail, too many, a mail without its id or status, a status other than HELD, SENT
     * or ERROR, or a mail twice; 409 while the mail filter is LOG or empty; 401 without a current user, who is
     * recorded on each mail. Otherwise 200 with the ids queued and the mails refused, each with its reason (GONE,
     * CHANGED, RECORD, or FILTER: a sent mail the mail filter, a list of addresses, would keep from a recipient).
     */
    @PostMapping(value = "/send-again",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public SendAgainResultVo sendAgain(@RequestBody SendAgainRequestVo request) {
        User user = userService.currentUser();
        if (user == null) {
            throw new NiordWebException(401);
        }
        return scheduledMailService.sendAgain(request.getMails(), user.getUsername());
    }

}
