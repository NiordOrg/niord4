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
import org.niord.core.user.Roles;
import org.niord.model.DataFilter;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;

/**
 * REST interface for searching scheduled e-mails.
 */
@RestController
@RequestMapping("/rest/scheduled-mails")
@Transactional
@SuppressWarnings("unused")
public class ScheduledMailRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScheduledMailRestService.class);

    private final ScheduledMailService scheduledMailService;

    ScheduledMailRestService(ScheduledMailService scheduledMailService) {
        this.scheduledMailService = scheduledMailService;
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

}
