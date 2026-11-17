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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.service.BaseService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.slf4j.Logger;

import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;

/**
 * Business interface for managing persisted message parameter filters
 */
@Service
@SuppressWarnings("unused")
public class MessageParamFilterService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageParamFilterService.class);

    private final UserService userService;

    MessageParamFilterService(UserService userService) {
        this.userService = userService;
    }

    /**
     * Returns the message filters with the given identifier
     * @param id the message filter
     * @return the message filters with the given identifier or null if not found
     */
    public MessageParamFilter findById(Integer id) {
        User user = userService.currentUser();

        // Look up the given filter
        MessageParamFilter filter = getByPrimaryKey(MessageParamFilter.class, id);

        // Defense-in-depth: an existing filter must only ever be returned to its owner. These methods
        // normally sit behind @RolesAllowed, so the current user is non-null; but if a null user reaches
        // here (anonymous, no ticket) we must reject the caller as unauthenticated (HTTP 401) rather
        // than dereference null in the ownership check below, which surfaced an opaque HTTP 500 via a
        // NullPointerException (MSGSVC2). The "filter != null" guard mirrors the ownership check so the
        // unknown-id path still returns null unchanged.
        if (filter != null && user == null) {
            throw new NiordWebException("No current user", 401);
        }

        // Check that the current user is the owner of the filter. An ownership violation is an
        // authorization failure, so signal it as HTTP 403
        // rather than an unchecked IllegalArgumentException, which fell through to an opaque HTTP 500
        // (WEB2). The message carries only the username + id, not the filter name, so nothing leaks.
        if (filter != null && !user.getId().equals(filter.getUser().getId())) {
            throw new NiordWebException("User " + user.getUsername() + " does not own filter " + id, 403);
        }
        return filter;
    }

    /**
     * Returns the message filter for the current user with the given name
     * @param name the name of the message filter to look up
     * @return the matching message filter or null
     */
    public MessageParamFilter findByCurrentUserAndName(String name) {
        User user = userService.currentUser();

        try {
            return em.createNamedQuery("MessageParamFilter.findByUserAndName", MessageParamFilter.class)
                    .setParameter("user", user)
                    .setParameter("name", name)
                    .getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns all message filters for the current user with the given IDs
     * @param ids the message filter IDs of the message filters to look up
     * @return the list of all message filters for the current user with the given IDs
     */
    public List<MessageParamFilter> findByCurrentUserAndIds(Integer... ids) {
        User user = userService.currentUser();

        Set<Integer> filterIds = new HashSet<>(Arrays.asList(ids));
        return em.createNamedQuery("MessageParamFilter.findByUserAndIds", MessageParamFilter.class)
                .setParameter("user", user)
                .setParameter("ids", filterIds)
                .getResultList();
    }

    /**
     * Returns all message filters for the current user
     * @return the list of all message filters for the current user
     */
    public List<MessageParamFilter> getMessageFiltersForUser() {
        User user = userService.currentUser();
        return em.createNamedQuery("MessageParamFilter.findByUser", MessageParamFilter.class)
                .setParameter("user", user)
                .getResultList();
    }

    /**
     * Creates a new message filter for the current user from the given template.
     * If a filter with the given name already exists, update this filter instead.
     * @param filter the new message filter
     * @return the persisted message filter
     */
    @Transactional
    public MessageParamFilter createOrUpdateMessageFilter(MessageParamFilter filter) {
        MessageParamFilter original;

        // The name is required: refused with a sentence (400), not left to the database's NOT NULL (500)
        if (StringUtils.isBlank(filter.getName())) {
            throw new IllegalArgumentException("A filter needs a name");
        }

        // Search for an existing filter with the same ID or name
        if (filter.getId() != null) {
            original = findById(filter.getId());
            if (original == null) {
                throw new IllegalArgumentException("Invalid filter id  " + filter.getId());
            }
        } else {
            original = findByCurrentUserAndName(filter.getName());
        }

        // Update an existing filter
        if (original != null) {
            original.setName(filter.getName());
            original.setParameters(filter.getParameters());
            return saveEntity(original);
        }

        // Create a new filter
        filter.setUser(userService.currentUser());
        return saveEntity(filter);
    }

    /**
     * Deletes the message filter for the current user with the given ID
     * @param id the message filter to delete
     * @return if the message filter was deleted
     */
    @Transactional
    public boolean deleteMessageFilter(Integer id) {
        User user = userService.currentUser();

        MessageParamFilter original = findById(id);
        if (original != null) {
            log.info("Removing message filter " + id);
            remove(original);
            return true;
        }
        return false;
    }

}
