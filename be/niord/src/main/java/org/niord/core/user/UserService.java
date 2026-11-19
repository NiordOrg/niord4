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

package org.niord.core.user;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.domain.Domain;
import org.niord.core.keycloak.KeycloakAdminException;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.core.service.BaseService;
import org.niord.core.user.vo.GroupVo;
import org.niord.core.user.vo.UserVo;
import org.niord.core.util.TextUtils;
import org.niord.core.web.NiordWebException;
import org.niord.model.DataFilter.UserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Defines the API for accessing users either from the database or from Keycloak
 */
@Service
@SuppressWarnings("unused")
public class UserService extends BaseService {

    public static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@"
                    + "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$");

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** The shortest (trimmed) name {@link #searchUsers(String)} searches for; a shorter one matches nothing. */
    static final int MIN_SEARCH_TERM_LENGTH = 2;

    /** The most users {@link #searchUsers(String)} returns. */
    static final int MAX_SEARCH_RESULTS = 25;

    @Inject
    TicketService ticketService;

    @Inject
    KeycloakIntegrationService keycloakIntegrationService;

    @Inject
    CurrentUserContext currentUserContext;

    @Inject
    UserCreationService userCreationService;

    @Inject
    UserDeletionService userDeletionService;

    /************************/
    /** Current User       **/
    /************************/

    /**
     * Returns the currently authenticated user.
     * If necessary the user is created and updated in the database
     *
     * @return the currently authenticated user
     */
    @Transactional
    public User currentUser() {
        if (currentUserContext.isAnonymous()) {

            // Check if the ticket service has resolved a ticket for the current thread
            TicketService.TicketData ticketData = ticketService.getTicketDataForCurrentThread();
            if (ticketData != null && StringUtils.isNotBlank(ticketData.getUser())) {
                return findByUsername(ticketData.getUser());
            } else {
                return null;
            }
        }

        // PORT: OidcJwtCallerPrincipal.getName() returned the "preferred_username" claim;
        // PORT: Jwt.getName() returns the subject, so read the claim explicitly to stay faithful.
        String username = currentUserContext.getCallerPrincipal().getClaimAsString("preferred_username");
        User user = findByUsername(username);

        if (user == null) {
            // New user: create the mirror row in its own short transaction (UserCreationService), so
            // a duplicate insert — a concurrent first request for the same user, or a second
            // get-or-create within this request — aborts only that transaction instead of failing
            // this one at commit with a duplicate key / HTTP 500.
            RuntimeException creationFailure = null;
            try {
                userCreationService.createUser(currentUserContext.getCallerPrincipal());
            } catch (RuntimeException e) {
                creationFailure = e;
                log.debug("User " + username + " was created concurrently: " + e.getMessage());
            }

            // Return the committed row — ours, or the creation-race winner's — managed in this transaction
            user = findByUsername(username);
            if (user == null) {
                throw creationFailure != null
                        ? creationFailure
                        : new IllegalStateException("Unable to create or resolve user " + username);
            }

        } else if (user.userChanged(currentUserContext.getCallerPrincipal())) {
            // User data updated
            user.copyToken(currentUserContext.getCallerPrincipal());
            user = saveEntity(user);
            log.info("Updated user " + user);
        }
        return user;
    }

    /**
     * Returns a user resolver that, for the duration of the current transaction only,
     * may be used by a DataFilter to check the current user.
     * @return a user resolver
     */
    public UserResolver userResolver() {
        return new CurrentTransactionUserResolver(this, currentUserContext);
    }

    /**
     * Test if the caller has a given security role.
     *
     * @param role The name of the security role.
     * @return True if the caller has the specified role.
     */
    public boolean isCallerInRole(String role) {
        if (currentUserContext.isAnonymous()) {

            // Check if the ticket service has resolved a ticket for the current thread
            TicketService.TicketData ticketData = ticketService.getTicketDataForCurrentThread();
            if (ticketData != null && StringUtils.isNotBlank(ticketData.getUser())) {
                // A role-less ticket (issued with no 'role' param) has null roles — grant no role
                // rather than NPE. A ticket only carries a role once TicketRestService verified the
                // issuing caller held it, so "no roles" means "never checked". TICKET1.
                String[] ticketRoles = ticketData.getRoles();
                return ticketRoles != null && Arrays.asList(ticketRoles).contains(role);
            }
            return false;
        }

        // PORT: identity.hasRole(role) -> match against the granted authorities. Re-wire the
        // PORT: exact Keycloak-role -> authority mapping (prefix handling) during the IT stage.
        return currentUserContext.currentUserRoles().contains(role);
    }

    /*************************/
    /** Database Users      **/
    /*************************/

    /**
     * Looks up the {@code User} with the given username
     *
     * @param username the user name
     * @return the user or null
     */
    public User findByUsername(String username) {
        if (StringUtils.isBlank(username)) {
            return null;
        }
        try {
            return em.createNamedQuery("User.findByUsername", User.class)
                    .setParameter("username", username)
                    .getSingleResult();
        } catch (NoResultException e) {
            // Only "no such user" maps to null; infrastructure failures must surface rather than
            // masquerade as an anonymous caller (a swallowed lookup failure turns into a duplicate
            // user insert).
            return null;
        }
    }

    /**
     * Looks up a {@code User} with the email.
     * <p>
     * An address is not unique among users (the column has no unique constraint, and data carried over from
     * the earlier product holds addresses that two users share), so one user or several mean "a user has this
     * address", never an error; of several, the one created first (the lowest id) is returned.
     *
     * @param email the user email
     * @return the user with the lowest id that has the email, or null if no user has it
     */
    public User findByEmail(String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }
        List<User> users = em.createNamedQuery("User.findByEmail", User.class)
                .setParameter("email", email)
                .setMaxResults(1)
                .getResultList();
        return users.isEmpty() ? null : users.getFirst();
    }

    /**
     * Looks up the {@code User}s with the given usernames
     *
     * @param usernames the user names
     * @return the users with the names, an empty list for none
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "none found"
     */
    public List<User> findByUsernames(Set<String> usernames) {
        if (usernames != null && !usernames.isEmpty()) {
            // Lower-case (not upper-case) to match the named query's "lower(u.username) in (:usernames)".
            // Under MySQL's case-insensitive collation the mismatched upper-casing happened to match; on
            // PostgreSQL's case-sensitive IN it matched nothing, silently breaking findByUsernames.
            usernames = usernames.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            return em.createNamedQuery("User.findByUsernames", User.class)
                    .setParameter("usernames", usernames)
                    .getResultList();
        }
        return Collections.emptyList();
    }

    /**
     * Searches for users whose user name, e-mail, first or last name contains the given name.
     * <p>
     * The search backs the user and e-mail autocomplete fields, which any signed-in user can call, so it
     * is bounded: the (trimmed) name must be at least {@link #MIN_SEARCH_TERM_LENGTH} characters, it is
     * matched as text (its LIKE wildcards are escaped), and at most {@link #MAX_SEARCH_RESULTS} users are
     * returned.
     *
     * @param name the name to match
     * @return the matching users
     */
    public List<User> searchUsers(String name) {
        String term = StringUtils.trimToEmpty(name);
        if (term.length() < MIN_SEARCH_TERM_LENGTH) {
            return Collections.emptyList();
        }
        return em.createNamedQuery("User.searchUsers", User.class)
                .setParameter("term", "%" + TextUtils.escapeLike(term.toLowerCase()) + "%")
                .setMaxResults(MAX_SEARCH_RESULTS)
                .getResultList();
    }

    /**
     * Returns a list of persisted users based on a list of template users
     * @param users the list of users to look up persisted users for
     * @return the list of corresponding persisted users
     */
    public List<User> persistedUsers(List<User> users) {
        return users.stream()
                .map(c -> findByUsername(c.getUsername()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Returns all users
     * @return all users
     */
    public List<User> allUsers() {
        return getAll(User.class);
    }

    /*************************/
    /** Keycloak Users      **/
    /*************************/

    /**
     * Adds the user to Keycloak and the local Niord DB
     * @param user the template user to add
     */
    public UserVo addKeycloakUser(UserVo user) throws Exception {
        keycloakIntegrationService.addKeycloakUser(user);
        return syncKeycloakUserWithNiord(user).toVo();
    }

    /**
     * Updates the user in Keycloak and the local Niord DB
     * @param user the template user to update
     */
    public UserVo updateKeycloakUser(UserVo user) throws Exception {
        keycloakIntegrationService.updateKeycloakUser(user);
        return syncKeycloakUserWithNiord(user).toVo();
    }

    /**
     * Synchronize the Keycloak user to the local Niord DB
     * @param userVo the Keycloak user
     * @return the updated user
     */
    private User syncKeycloakUserWithNiord(UserVo userVo) {

        User user = findByUsername(userVo.getUsername());

        if (user == null) {
            // New user
            user = new User(userVo);
            user = saveEntity(user);
            log.info("Created new user from Keycloak " + user.getUsername());

        } else {
            // User data updated
            user.copyUser(userVo);
            user = saveEntity(user);
            log.info("Updated user from Keycloak " + user.getUsername());
        }

        return user;
    }

    /**
     * Deletes the user from Keycloak, once Niord's own record of the user has stopped mailing the user
     * ({@link UserDeletionService#stopMailing}, committed first, so that a failure anywhere is repaired by deleting
     * again): the user is off every mailing list and the mails queued for the user, and the record's e-mail address
     * is cleared, so that the Manage Recipients drawer and the e-mail suggestions no longer offer the user. The record
     * itself stays, for the messages, comments and history the user made; a user created again with the same user
     * name gets the address back, but not the lists.
     *
     * @param userId the Keycloak ID of the user to delete
     */
    public void deleteKeycloakUser(String userId) throws Exception {
        String username = keycloakIntegrationService.getKeycloakUsername(userId);
        userDeletionService.stopMailing(username);
        keycloakIntegrationService.deleteKeycloakUser(userId);
    }

    /**
     * Returns the users from Keycloak
     * @return the users from Keycloak
     */
    public List<UserVo> searchKeycloakUsers(String search, int first, int max) {
        try {
            List<UserVo> users = keycloakIntegrationService.searchKeycloakUsers(search, first, max);

            // Merge the user data from Keycloak with local user data that is not stored in Keycloak
            // For now, this is only the "language" attribute
            Set<String> usernames = users.stream()
                    .map(UserVo::getUsername)
                    .collect(Collectors.toSet());
            Map<String, User> userMap = findByUsernames(usernames).stream()
                    .collect(Collectors.toMap(User::getUsername, Function.identity()));

            users.stream()
                .filter(u -> userMap.containsKey(u.getUsername()))
                .forEach(u -> u.setLanguage(userMap.get(u.getUsername()).getLanguage()));

            return users;
        } catch (Exception e) {
            log.error("Error reading Keycloak users: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Returns the domain groups from Keycloak
     * @return the domain groups
     */
    public List<GroupVo> getKeycloakGroups(Domain domain) {
        try {
            List<GroupVo> groups = keycloakIntegrationService.getKeycloakGroups();
            groups.forEach(g -> resolveKeycloakGroupAccess(domain, g));
            return groups;
        } catch (Exception e) {
            log.error("Error reading Keycloak groups: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** Determine which of the groups (and sub-groups) the user has access to **/
    private void resolveKeycloakGroupAccess(Domain domain, GroupVo group) {
        try {
            List<String> roles = keycloakIntegrationService.getKeycloakRoles(domain, group.getId());
            group.setActive(roles.stream().anyMatch(this::isCallerInRole));
        } catch (Exception e) {
            group.setActive(false);
            log.debug("Error reading Keycloak group access : " + e.getMessage());
        }
        if (group.getChildren() != null) {
            group.getChildren().forEach(g -> resolveKeycloakGroupAccess(domain, g));
        }
    }

    /**
     * Returns the user groups from Keycloak
     * @return the user groups
     */
    public List<GroupVo> getKeycloakUserGroups(Domain domain, String userId) {
        try {
            List<GroupVo> groups =  keycloakIntegrationService.getKeycloakUserGroups(userId);
            groups.forEach(g -> resolveKeycloakGroupAccess(domain, g));
            return groups;
        } catch (Exception e) {
            log.debug("Error reading Keycloak user groups: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Assign the user to the given group
     * @param userId the Keycloak user ID
     * @param groupId the Keycloak group ID
     * @throws NiordWebException when Keycloak refuses (403), knows no such user or group (404), or fails or
     *         cannot be reached (502): the change of access did not happen, and the caller must not be told it did
     */
    public void joinKeycloakGroup(String userId, String groupId) {
        try {
            keycloakIntegrationService.joinKeycloakGroup(userId, groupId);
        } catch (Exception e) {
            throw groupChangeFailed("add the user to the group", e);
        }
    }

    /**
     * Removes the user from the given group
     * @param userId the Keycloak user ID
     * @param groupId the Keycloak group ID
     * @throws NiordWebException as {@link #joinKeycloakGroup}
     */
    public void leaveKeycloakGroup(String userId, String groupId) {
        try {
            keycloakIntegrationService.leaveKeycloakGroup(userId, groupId);
        } catch (Exception e) {
            throw groupChangeFailed("remove the user from the group", e);
        }
    }

    /**
     * The answer to a group join or leave that Keycloak did not carry out: 403 when Keycloak refused it, 404 when
     * it knows no such user or group, and 502 when it failed or could not be reached. The old system logged the
     * failure and answered 204, so the administrator was told a change of access was done when it was not.
     */
    static NiordWebException groupChangeFailed(String action, Exception e) {
        log.warn("Keycloak did not " + action + ": " + e.getMessage());
        if (e instanceof KeycloakAdminException kc) {
            if (kc.getStatus() == 403) {
                return new NiordWebException("Keycloak refused to " + action + ", so the user's groups are unchanged",
                        403);
            }
            if (kc.getStatus() == 404) {
                return new NiordWebException("Keycloak has no such user or group, so the user's groups are unchanged",
                        404);
            }
            return new NiordWebException("Keycloak failed to " + action + " (it answered " + kc.getStatus() + ")",
                    502);
        }
        // The exception's own text (the admin client's, naming the connection) stays in the log
        return new NiordWebException("Keycloak could not be reached to " + action, 502);
    }
}
