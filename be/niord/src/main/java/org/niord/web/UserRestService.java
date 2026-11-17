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

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.domain.DomainService;
import org.niord.core.user.Roles;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.core.user.vo.GroupVo;
import org.niord.core.user.vo.UserVo;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for accessing users.
 */
@RestController
@RequestMapping("/rest/users")
@Transactional
@PermitAll
public class UserRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserRestService.class);

    private final UserService userService;

    private final DomainService domainService;

    UserRestService(UserService userService, DomainService domainService) {
        this.userService = userService;
        this.domainService = domainService;
    }

    /**
     * Returns all users, with their user names and e-mail addresses, across every domain.
     * ADMIN rather than USER: no screen of the admin application needs the full directory.
     */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public List<UserVo> all() {
        return userService.allUsers().stream()
                .map(User::toVo)
                .collect(Collectors.toList());
    }

    /** Returns all users that matches the given name */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public List<UserVo> search(@RequestParam(name = "name", required = false) String name) {
        return userService.searchUsers(name).stream()
                .map(User::toVo)
                .collect(Collectors.toList());
    }

    /** Returns email addresses whose users match the given name */
    @GetMapping(value = "/search-emails", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public List<String> searchEmails(@RequestParam(name = "name", required = false) String name) {

        List<String> emails = new ArrayList<>();
        if (StringUtils.isNotBlank(name) && UserService.EMAIL_PATTERN.matcher(name).matches()) {
            emails.add(name);
        }

        userService.searchUsers(name).stream()
                .map(u -> emailSuggestion(u.getName(), u.getEmail()))
                .filter(Objects::nonNull)
                .forEach(emails::add);

        return emails;
    }

    /**
     * The {@code Name <address>} suggestion for one user. A name holding an RFC 822 special character (a comma, colon,
     * semicolon and the like) is quoted, so the suggestion parses back as exactly one mailbox: the comment endpoint
     * refuses an entry that parses into several. Any other name is left as it is, so the suggestions look as they always
     * did, letters such as æ, ø and å included. A user without an e-mail address, such as a deleted user, gives none.
     */
    static String emailSuggestion(String name, String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }
        if (StringUtils.isBlank(name)) {
            return email;
        }
        String displayName = StringUtils.containsAny(name, RFC822_SPECIALS)
                ? "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : name;
        return displayName + " <" + email + ">";
    }

    /** The characters that must not appear unquoted in the display name of an RFC 822 address. */
    private static final String RFC822_SPECIALS = "()<>@,;:\\\".[]";

    /** Returns all users that matches the given name */
    @GetMapping(value = "/kc-users", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public List<UserVo> searchKeycloakUsers(
            @RequestParam(name = "search", defaultValue = "") String search,
            @RequestParam(name = "first", defaultValue = "0") int first,
            @RequestParam(name = "max", defaultValue = "20") int max) {
        return userService.searchKeycloakUsers(search, first, max);
    }

    /** Returns all users that matches the given name */
    @GetMapping(value = "/kc-groups", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public List<GroupVo> getKeycloakGroups() {
        return userService.getKeycloakGroups(domainService.currentDomain());
    }

    /** Returns the domain roles assigned to the given group */
    @GetMapping(value = "/kc-user/{userId}/kc-groups", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public List<GroupVo> getKeycloakUserGroups(@PathVariable("userId") String userId) {
        return userService.getKeycloakUserGroups(domainService.currentDomain(), userId);
    }

    /**
     * Assign the user to the given group
     * @param userId the Keycloak user ID
     * @param groupId the Keycloak group ID
     */
    @PutMapping("/kc-user/{userId}/kc-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.ADMIN)
    public void joinKeycloakGroup(@PathVariable("userId") String userId, @PathVariable("groupId") String groupId) {
        log.info("Joining Keycloak user " + userId + " to group " + groupId);
        userService.joinKeycloakGroup(userId, groupId);
    }

    /**
     * Removes the user from the given group
     * @param userId the Keycloak user ID
     * @param groupId the Keycloak group ID
     */
    @DeleteMapping("/kc-user/{userId}/kc-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.ADMIN)
    public void leaveKeycloakGroup(@PathVariable("userId") String userId, @PathVariable("groupId") String groupId) {
        log.info("Removing Keycloak user " + userId + " from group " + groupId);
        userService.leaveKeycloakGroup(userId, groupId);
    }

    /**
     * Adds the user to Keycloak and the local Niord DB
     * @param user the template user to add
     */
    @PostMapping(value = {"/kc-user", "/kc-user/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public UserVo addKeycloakUser(@RequestBody UserVo user) throws Exception {
        log.info("Creating Keycloak user " + user.getUsername());
        return userService.addKeycloakUser(user);
    }

    /**
     * Updates the user in Keycloak and the local Niord DB
     * @param user the template user to update
     */
    @PutMapping(value = "/kc-user/{userId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public UserVo updateKeycloakUser(@PathVariable("userId") String userId, @RequestBody UserVo user) throws Exception {
        if (!Objects.equals(userId, user.getKeycloakId())) {
            throw new NiordWebException(400);
        }
        log.info("Updating Keycloak user " + user.getUsername());
        return userService.updateKeycloakUser(user);
    }

    /**
     * Deletes the user from Keycloak
     * @param userId the user to delete
     */
    @DeleteMapping("/kc-user/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.ADMIN)
    public void deleteKeycloakUser(@PathVariable("userId") String userId) throws Exception {
        log.info("Deleting Keycloak user " + userId);
        userService.deleteKeycloakUser(userId);
    }

}
