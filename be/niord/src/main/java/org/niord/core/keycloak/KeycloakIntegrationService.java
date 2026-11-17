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
package org.niord.core.keycloak;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.niord.core.NiordApp;
import org.niord.core.domain.Domain;
import org.niord.core.settings.Setting.Type;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.user.Roles;
import org.niord.core.user.CurrentUserContext;
import org.niord.core.user.vo.GroupVo;
import org.niord.core.user.vo.UserVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Provides an interface for Keycloak integration.
 * <p>
 * "Domains" in Niord are mapped to Keycloak bearer-only clients.
 * <p>
 * All authenticated admin operations (clients, roles, users, groups) are performed through the official
 * {@code keycloak-admin-client} Java client ({@link #adminCall}), constructed per request with the current
 * caller's bearer token.
 * <p>
 * The realm is the deployment's, read from the token issuer ({@link #realm()}): one Keycloak can serve several
 * deployments, each in a realm of its own (the shared Keycloak of the test cluster, realms {@code niord-dk} and
 * {@code niord-gl}). {@link #browserUrl()} is the Keycloak the browser reaches, which
 * {@code GET /rest/settings/keycloak} hands the web app at start.
 */
@Service
public class KeycloakIntegrationService {

    /** The realm when the token issuer names none: the base product's own */
    public static final String KEYCLOAK_REALM       = "niord";

    /** The Spring property naming the token issuer: {@code <Keycloak URL>/realms/<realm>} */
    public static final String ISSUER_URI_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    @Inject
    CurrentUserContext currentUserContext;

    /**
     * The token issuer the resource server validates, {@code <Keycloak URL>/realms/<realm>}. It names the realm
     * the deployment uses and the Keycloak the browser reaches, so both are read from it ({@link #realm()},
     * {@link #browserUrl()}): one deployment value, and the realm cannot drift from the issuer the tokens carry.
     * Empty when the property is not set, as in a unit test; then the realm is {@link #KEYCLOAK_REALM}.
     */
    @Value("${" + ISSUER_URI_PROPERTY + ":}")
    String issuerUri;

        @Setting(value = "authServerUrl", defaultValue = "/auth", description = "The Keycloak URL. Read at start-up.")
    String authServerUrl;

    /**
     * Whether the admin client trusts any TLS certificate and host name on the Keycloak connection. Off
     * by default: the client forwards the caller's bearer token, so the connection must be verified. For
     * a development setup whose Keycloak has a certificate the JVM does not trust; set it as an
     * environment variable or system property, it is read once at start-up.
     */
        @Setting(value = "authServerTrustAllCertificates", defaultValue = "false", type = Type.Boolean, editable = false,
            description = "Development only: trust any TLS certificate and host name on the Keycloak admin connection")
    Boolean authServerTrustAllCertificates;

    @Inject
    NiordApp app;

    private Logger log = LoggerFactory.getLogger(KeycloakIntegrationService.class);

    /******************************/
    /** Keycloak configuration   **/
    /******************************/

    /**
     * Computes the fully-qualified URL to the Keycloak server. A relative {@code authServerUrl} is
     * resolved against the configured {@code baseUri} - never against the Host of the request, which
     * the caller chooses, since the admin client sends the caller's bearer token to this URL.
     */
    private String resolveAuthServerUrl() {
        String url = authServerUrl;
        if (StringUtils.isBlank(url)) {
            throw new RuntimeException("No authServerUrl setting defined");
        }

        // Handle relative auth server url
        if (!url.toLowerCase().startsWith("http")) {
            String baseUri = StringUtils.defaultString(app.getBaseUri());
            if (!url.startsWith("/") && !baseUri.endsWith("/")) {
                url = "/" + url;
            } else if (url.startsWith("/") && baseUri.endsWith("/")) {
                url = url.substring(1);
            }
            url = baseUri + url;
        }

        return url;
    }

    /**
     * Returns the realm of this deployment: the last segment of the token issuer, or {@link #KEYCLOAK_REALM}
     * when the issuer is not set or does not name one.
     */
    public String realm() {
        return StringUtils.defaultIfBlank(realmOf(issuerUri), KEYCLOAK_REALM);
    }

    /**
     * Returns the Keycloak URL the browser reaches, taken from the token issuer: the issuer without its
     * {@code /realms/<realm>} tail. Null when the issuer is not set or does not end in {@code /realms/<realm>}.
     */
    public String browserUrl() {
        String realm = realmOf(issuerUri);
        if (realm == null) {
            return null;
        }
        String issuer = StringUtils.stripEnd(StringUtils.trimToEmpty(issuerUri), "/");
        String url = issuer.substring(0, issuer.length() - "/realms/".length() - realm.length());
        return url.isEmpty() ? null : url;
    }

    /** The realm an issuer names: the segment after its final {@code /realms/}, or null when there is none */
    static String realmOf(String issuerUri) {
        String issuer = StringUtils.stripEnd(StringUtils.trimToEmpty(issuerUri), "/");
        int at = issuer.lastIndexOf("/realms/");
        if (at < 0) {
            return null;
        }
        String realm = issuer.substring(at + "/realms/".length());
        return realm.isEmpty() || realm.contains("/") ? null : realm;
    }

    /********************************/
    /** Keycloak admin client      **/
    /********************************/

    /**
     * Builds a Keycloak admin client bound to the current caller's bearer token.
     * <p>
     * The client forwards the caller's token, so TLS is verified against the JVM's trust store. Only the
     * {@code authServerTrustAllCertificates} setting, meant for a development setup, switches that off.
     *
     * @return an admin client the caller must {@code close()}
     */
    private Keycloak keycloakAdmin() {
        // PORT: getCallerPrincipal() returns a Spring Security Jwt (was a Quarkus principal); a non-null
        // value marks the caller as authenticated.
        Jwt principal = currentUserContext.getCallerPrincipal();
        if (principal == null) {
            throw new IllegalStateException("User not authenticated");
        }
        return Keycloak.getInstance(
                resolveAuthServerUrl(),          // base url; the client appends /admin/realms/{realm}
                realm(),
                null, null,                      // username, password (unused with a pre-issued token)
                null, null,                      // clientId, clientSecret (unused)
                null,                            // sslContext (the JVM default)
                null,                            // custom Jackson provider (default)
                trustAllCertificates(),          // disableTrustManager: off unless a development setup asks
                currentUserContext.getKeycloakAccessToken(),   // pre-issued bearer token
                null);                           // scope
    }

    /** Whether the admin client skips TLS verification: only when the setting explicitly asks for it */
    boolean trustAllCertificates() {
        return Boolean.TRUE.equals(authServerTrustAllCertificates);
    }

    /**
     * Runs an admin operation against the Niord realm, normalizing any Keycloak error response into a
     * {@link KeycloakAdminException} carrying the status (its message the "status = NNN" contract the callers
     * expect) and closing the underlying client afterwards.
     */
    private <R> R adminCall(AdminOp<R> op) throws Exception {
        try (Keycloak keycloak = keycloakAdmin()) {
            return op.apply(keycloak.realm(realm()));
        } catch (WebApplicationException e) {
            int status = e.getResponse() != null ? e.getResponse().getStatus() : -1;
            throw new KeycloakAdminException("Unable to execute Keycloak admin request, status = " + status,
                    status, e);
        }
    }

    /** An admin operation performed against the {@link RealmResource} of the Niord realm. */
    @FunctionalInterface
    private interface AdminOp<R> {
        R apply(RealmResource realm) throws Exception;
    }

    /********************************/
    /** Keycloak clients (domains) **/
    /********************************/

    /**
     * Returns the keycloak client for the given domain
     * @param realm the Niord realm resource
     * @param domainId the domain
     * @return the keycloak client for the given domain, or null if not found
     */
    private ClientRepresentation getKeycloakDomainClient(RealmResource realm, String domainId) {
        return realm.clients().findAll().stream()
                .filter(c -> domainId.equals(c.getClientId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the list of Keycloak domain IDs
     * @return the list of Keycloak domain IDs
     */
    public Set<String> getKeycloakDomainIds() throws Exception {
        return adminCall(realm -> realm.clients().findAll()
                .stream()
                .map(ClientRepresentation::getClientId)
                .collect(Collectors.toSet()));
    }

    /**
     * Creates a new Keycloak client based on the given domain template
     * @param domain the domain template
     * @return if the domain was successfully created
     */
    public boolean createKeycloakDomain(Domain domain) throws Exception {
        return adminCall(realm -> {

            // If the domain already exists, bail out
            if (getKeycloakDomainClient(realm, domain.getDomainId()) != null) {
                log.warn("Domain " + domain.getDomainId() + " already exists");
                return false;
            }

            // Create a template for the new client.
            // Jackson 3 removed ObjectMapper.readValue(URL, ...); read the classpath template via its stream.
            ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
            ClientRepresentation client;
            try (InputStream is = getClass().getResourceAsStream("/keycloak-client-template.json")) {
                client = mapper.readValue(is, ClientRepresentation.class);
            }

            // Instantiate it from the domain
            client.setId(null);
            client.setClientId(domain.getDomainId());
            client.setName(domain.getName());

            // Create the client in Keycloak
            try (Response response = realm.clients().create(client)) {
                if (response.getStatus() < 200 || response.getStatus() > 299) {
                    log.error("Failed creating Keycloak domain client " + domain.getDomainId()
                            + ", status = " + response.getStatus());
                    return false;
                }
            }
            log.info("Created Keycloak domain client " + domain.getDomainId());

            // Get hold of the newly created client (with a proper ID)
            ClientRepresentation created = getKeycloakDomainClient(realm, domain.getDomainId());
            ClientResource clientResource = realm.clients().get(created.getId());

            // Define the list of roles to set up for the client
            RoleRepresentation[] roleReps = new RoleRepresentation[] {
                    new RoleRepresentation(Roles.USER, "User", false),
                    new RoleRepresentation(Roles.EDITOR, "Editor", false),
                    new RoleRepresentation(Roles.ADMIN, "Administrator", false),
                    new RoleRepresentation(Roles.SYSADMIN, "System administrator", false),
            };

            // Create the roles in Keycloak.
            // All roles, bar the first, are composite roles that include all previously defined roles
            List<RoleRepresentation> prevRoles = new ArrayList<>();
            for (RoleRepresentation role : roleReps) {
                // Post the new role
                clientResource.roles().create(role);
                log.info("Created role " + role.getName() + " for client " + domain.getDomainId());

                // Fetch the newly created role, in order to retrieve its ID
                RoleRepresentation createdRole = clientResource.roles().get(role.getName()).toRepresentation();

                // The roles are ordered, so that a role is a composite of its previous roles
                if (!prevRoles.isEmpty()) {
                    clientResource.roles().get(createdRole.getName()).addComposites(new ArrayList<>(prevRoles));
                }
                prevRoles.add(createdRole);
            }

            return true;
        });
    }

    /******************************/
    /** Keycloak users & groups  **/
    /******************************/

    /**
     * Adds the user to Keycloak and the local Niord DB
     * @param user the template user to add
     */
    public void addKeycloakUser(UserVo user) throws Exception {

        adminCall(realm -> {
            UserRepresentation userRep = new UserRepresentation();
            userRep.setUsername(user.getUsername());
            userRep.setEmail(user.getEmail());
            userRep.setFirstName(user.getFirstName());
            userRep.setLastName(user.getLastName());
            userRep.setEnabled(true);
            if (user.getKeycloakActions() != null) {
                userRep.setRequiredActions(user.getKeycloakActions());
            }

            try (Response response = realm.users().create(userRep)) {
                // UsersResource.create() returns the response instead of throwing, so a rejected
                // creation (e.g. 409 duplicate username/email, 400 validation) must be detected and
                // propagated here — otherwise the caller would sync a user Keycloak never created.
                if (response.getStatus() < 200 || response.getStatus() > 299) {
                    throw new Exception("Unable to create Keycloak user " + user.getUsername()
                            + ", status = " + response.getStatus());
                }
                // The Location header carries the new user id; the local DB tracks users by username,
                // so the reset-password path (below) looks the user up by username when a password
                // was supplied.
            }
            return null;
        });

        // Check if we need to reset the user password
        resetKeycloakPassword(user);
    }

    /**
     * Updates the user in Keycloak and the local Niord DB
     * @param user the template user to update
     */
    public void updateKeycloakUser(UserVo user) throws Exception {

        adminCall(realm -> {
            UserResource userResource = realm.users().get(user.getKeycloakId());

            // Look up the original user
            UserRepresentation origUser = userResource.toRepresentation();

            // Update the user
            origUser.setUsername(user.getUsername());
            origUser.setEmail(user.getEmail());
            origUser.setFirstName(user.getFirstName());
            origUser.setLastName(user.getLastName());
            origUser.setEnabled(true);
            if (user.getKeycloakActions() != null) {
                origUser.setRequiredActions(user.getKeycloakActions());
            }

            userResource.update(origUser);
            return null;
        });

        // Check if we need to reset the user password
        resetKeycloakPassword(user);
    }

    /** Resets the user password **/
    private void resetKeycloakPassword(UserVo user) throws Exception {

        if (StringUtils.isBlank(user.getKeycloakPassword())) {
            return;
        }

        adminCall(realm -> {
            String userId = user.getKeycloakId();
            if (StringUtils.isBlank(userId)) {
                List<UserRepresentation> users = realm.users().search(user.getUsername(), true);
                if (users.isEmpty()) {
                    throw new Exception("No user with username " + user.getUsername());
                }
                userId = users.get(0).getId();
            }

            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(user.getKeycloakPassword());
            credential.setTemporary(false);

            realm.users().get(userId).resetPassword(credential);
            return null;
        });
    }

    /**
     * Returns the user name of the Keycloak user
     * @param userId the Keycloak ID of the user
     * @return the user name
     */
    public String getKeycloakUsername(String userId) throws Exception {
        return adminCall(realm -> realm.users().get(userId).toRepresentation().getUsername());
    }

    /**
     * Deletes the user from Keycloak
     * @param userId the user to delete
     */
    public void deleteKeycloakUser(String userId) throws Exception {
        adminCall(realm -> {
            realm.users().get(userId).remove();
            return null;
        });
    }

    /**
     * Searches the users from Keycloak matching the given search criteria
     * @return the users from Keycloak
     */
    public List<UserVo> searchKeycloakUsers(String search, int first, int max) throws Exception {
        return adminCall(realm -> realm.users().search(search, first, max)
                .stream()
                .map(this::readUser)
                .collect(Collectors.toList()));
    }

    /** Converts a Keycloak GroupRepresentation to a UserVo **/
    private UserVo readUser(UserRepresentation u) {
        UserVo user = new UserVo();
        user.setKeycloakId(u.getId());
        user.setUsername(u.getUsername());
        user.setEmail(u.getEmail());
        user.setFirstName(u.getFirstName());
        user.setLastName(u.getLastName());
        return user;
    }

    /**
     * Loads the group tree from Keycloak
     * @return the group tree from Keycloak
     */
    public List<GroupVo> getKeycloakGroups() throws Exception {
        return adminCall(realm -> realm.groups().groups()
                .stream()
                .map(this::readGroup)
                .collect(Collectors.toList()));
    }

    /**
     * Returns the groups associated with the given user from Keycloak
     * @return the groups associated with the given user from Keycloak
     */
    public List<GroupVo> getKeycloakUserGroups(String userId) throws Exception {
        return adminCall(realm -> realm.users().get(userId).groups()
                .stream()
                .map(this::readGroup)
                .collect(Collectors.toList()));
    }

    /** Converts a Keycloak GroupRepresentation to a GroupVo **/
    private GroupVo readGroup(GroupRepresentation g) {
        GroupVo group = new GroupVo();
        group.setId(g.getId());
        group.checkCreateDesc("en").setName(g.getName());
        group.setPath(g.getPath());
        if (g.getSubGroups() != null && !g.getSubGroups().isEmpty()) {
            group.setChildren(g.getSubGroups().stream()
                .map(this::readGroup)
                .collect(Collectors.toList()));
        }
        return group;
    }

    /**
     * Assign the user to the given group
     * @param userId the Keycloak user ID
     * @param groupId the Keycloak group ID
     */
    public void joinKeycloakGroup(String userId, String groupId) throws Exception {
        adminCall(realm -> {
            realm.users().get(userId).joinGroup(groupId);
            return null;
        });
    }

    /**
     * Remove the user from the given group
     * @param userId the Keycloak user ID
     * @param groupId the Keycloak group ID
     */
    public void leaveKeycloakGroup(String userId, String groupId) throws Exception {
        adminCall(realm -> {
            realm.users().get(userId).leaveGroup(groupId);
            return null;
        });
    }

    /** Returns the Keycloak client (domain) roles for the given group */
    public List<String> getKeycloakRoles(Domain domain, String groupId) throws Exception {
        return adminCall(realm -> {
            ClientRepresentation client = getKeycloakDomainClient(realm, domain.getDomainId());
            return realm.groups().group(groupId).roles().clientLevel(client.getId()).listAll()
                    .stream()
                    .map(RoleRepresentation::getName)
                    .collect(Collectors.toList());
        });
    }
}
