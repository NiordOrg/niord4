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

import org.niord.core.message.MessageParamFilter;
import org.niord.core.message.MessageParamFilterService;
import org.niord.core.message.vo.MessageParamFilterVo;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Allows an authenticated user to fetch and save message list filters.
 */
@RestController
@RequestMapping("/rest/filters")
@Transactional
@PermitAll
public class MessageParamFilterRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageParamFilterRestService.class);

    private final MessageParamFilterService messageParamFilterService;

    private final UserService userService;

    MessageParamFilterRestService(MessageParamFilterService messageParamFilterService, UserService userService) {
        this.messageParamFilterService = messageParamFilterService;
        this.userService = userService;
    }

    /** Validates that the user is logged in, but does not check any specific roles **/
    private void checkUserLoggedIn() {
        User user = userService.currentUser();
        if (user == null) {
            throw new NiordWebException(403);
        }
    }

    /** Returns all message list filters */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<MessageParamFilterVo> getMessageFilters() {
        // Must be logged in
        checkUserLoggedIn();

        return messageParamFilterService.getMessageFiltersForUser().stream()
                .map(MessageParamFilter::toVo)
                .collect(Collectors.toList());
    }

    /** Returns the filter with the given ID */
    @GetMapping(value = "/filter/{filterId}", produces = "application/json;charset=UTF-8")
    public MessageParamFilterVo getMessageFilter(@PathVariable("filterId") Integer filterId) {
        // Must be logged in
        checkUserLoggedIn();

        MessageParamFilter filter = messageParamFilterService.findById(filterId);
        return filter == null ? null : filter.toVo();
    }

    /**
     * Creates a new filter from the given template, or updates the user's filter of the same name. Both spellings
     * are mapped, as for the other creates: the administration app posts to {@code /filter/}.
     */
    @PostMapping(value = {"/filter", "/filter/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public MessageParamFilterVo createMessageFilter(@RequestBody MessageParamFilterVo filter) {
        // Must be logged in
        checkUserLoggedIn();

        log.info("Creating new message filter " + filter);
        return messageParamFilterService.createOrUpdateMessageFilter(new MessageParamFilter(filter)).toVo();
    }

    /** Deletes the filter with the given ID */
    @DeleteMapping("/filter/{filterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMessageFilter(@PathVariable("filterId") Integer filterId) {
        // Must be logged in
        checkUserLoggedIn();

        log.info("Deleting message filter " + filterId);
        messageParamFilterService.deleteMessageFilter(filterId);
    }
}
