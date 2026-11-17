/*
 * Copyright 2024 Danish Maritime Authority.
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

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.niord.core.user.CurrentUserContext;
import org.niord.core.user.vo.GroupVo;
import org.niord.core.user.vo.UserVo;
import org.niord.test.http.HttpStub;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tier-1 unit tests for {@link KeycloakIntegrationService}'s outbound Keycloak admin HTTP calls.
 *
 * <p>These tests exercise the real Apache-HttpClient request building, the real Jackson response
 * mapping into {@link ClientRepresentation}/{@link GroupRepresentation}/{@link UserRepresentation},
 * and the mapping into Niord's {@link GroupVo}/{@link UserVo} value objects. The Keycloak server is
 * replaced by {@link HttpStub} bound to an ephemeral localhost port, so we can both stub the JSON the
 * service receives and assert on the requests it sent (path, query, Authorization header, body).
 *
 * <p>No Quarkus, no container: the service is instantiated directly and its package-private
 * collaborators ({@code authServerUrl}, {@code log}, {@code currentUserContext}) are set by hand. The
 * {@link CurrentUserContext} is replaced by a tiny in-line fake so no real OIDC context is required.
 *
 * <p>The admin client targets {@code authServerUrl + "/admin/realms/niord"}, sends
 * {@code Authorization: Bearer <getKeycloakAccessToken()>} on authenticated reads, and requires a
 * non-null caller principal (otherwise it throws "User not authenticated").
 */
public class KeycloakIntegrationServiceHttpTest {

    private static final String REALM = "/admin/realms/niord";

    /** Reflection field-setter that walks the class hierarchy. */
    private static void set(Object t, String f, Object v) throws Exception {
        Class<?> c = t.getClass();
        while (c != null) {
            try {
                Field fld = c.getDeclaredField(f);
                fld.setAccessible(true);
                fld.set(t, v);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(f);
    }

    /**
     * A real, container-free Spring Security JWT.
     * PORT: the authenticated-caller marker was a Quarkus {@code OidcJwtCallerPrincipal}; the ported
     * {@code UserService.getCallerPrincipal()} returns a Spring Security {@link Jwt} instead.
     */
    private static Jwt jwt() {
        return Jwt.withTokenValue("tok")
                .header("alg", "none")
                .claim("preferred_username", "tester")
                .build();
    }

    /** A caller context whose principal is present and whose token is fixed. */
    private static CurrentUserContext authenticatedUserService() {
        return new CurrentUserContext() {
            @Override
            public Jwt getCallerPrincipal() {
                return jwt();
            }

            @Override
            public String getKeycloakAccessToken() {
                return "test-token";
            }
        };
    }

    /** A caller context that is NOT authenticated (null caller principal). */
    private static CurrentUserContext unauthenticatedUserService() {
        return new CurrentUserContext() {
            @Override
            public Jwt getCallerPrincipal() {
                return null;
            }

            @Override
            public String getKeycloakAccessToken() {
                return "test-token";
            }
        };
    }

    /** Builds a service wired to the stub and the given caller context. */
    private static KeycloakIntegrationService service(HttpStub stub, CurrentUserContext currentUserContext) throws Exception {
        KeycloakIntegrationService svc = new KeycloakIntegrationService();
        set(svc, "authServerUrl", stub.baseUrl());
        set(svc, "log", LoggerFactory.getLogger("kc"));
        set(svc, "currentUserContext", currentUserContext);
        return svc;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ----------------------------------------------------------------- //
    // 1) getKeycloakDomainIds()
    // ----------------------------------------------------------------- //

    @Test
    public void getKeycloakDomainIds_mapsClientIds_andSendsBearerToken() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            ClientRepresentation c1 = new ClientRepresentation();
            c1.setClientId("niord-client-nw");
            ClientRepresentation c2 = new ClientRepresentation();
            c2.setClientId("niord-client-nm");
            String clientsJson = MAPPER.writeValueAsString(List.of(c1, c2));

            stub.respond(req -> {
                if (req.path().equals(REALM + "/clients") && req.method().equals("GET")) {
                    return HttpStub.StubResponse.json(clientsJson);
                }
                return HttpStub.StubResponse.status(404);
            });

            Set<String> ids = service(stub, authenticatedUserService()).getKeycloakDomainIds();

            assertEquals(Set.of("niord-client-nw", "niord-client-nm"), ids,
                    "Both client IDs should be mapped");

            HttpStub.RecordedRequest req = stub.firstRequestForPath(REALM + "/clients");
            assertNotNull(req, "the /clients endpoint should have been hit");
            assertEquals("Bearer test-token", req.header("Authorization"),
                    "authenticated reads must carry the bearer token");
        }
    }

    // ----------------------------------------------------------------- //
    // 2) getKeycloakGroups()
    // ----------------------------------------------------------------- //

    @Test
    public void getKeycloakGroups_mapsNamesAndPaths() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            GroupRepresentation g1 = new GroupRepresentation();
            g1.setId("g-1");
            g1.setName("Editors");
            g1.setPath("/Editors");
            GroupRepresentation g2 = new GroupRepresentation();
            g2.setId("g-2");
            g2.setName("Admins");
            g2.setPath("/Admins");
            String groupsJson = MAPPER.writeValueAsString(List.of(g1, g2));

            stub.respond(req -> {
                if (req.path().equals(REALM + "/groups") && req.method().equals("GET")) {
                    return HttpStub.StubResponse.json(groupsJson);
                }
                return HttpStub.StubResponse.status(404);
            });

            List<GroupVo> groups = service(stub, authenticatedUserService()).getKeycloakGroups();

            assertEquals(2, groups.size(), "two groups should be returned");

            GroupVo first = groups.get(0);
            assertEquals("g-1", first.getId());
            assertEquals("Editors", first.getDesc("en").getName());
            assertEquals("/Editors", first.getPath());

            GroupVo second = groups.get(1);
            assertEquals("g-2", second.getId());
            assertEquals("Admins", second.getDesc("en").getName());
            assertEquals("/Admins", second.getPath());

            assertEquals("Bearer test-token",
                    stub.firstRequestForPath(REALM + "/groups").header("Authorization"));
        }
    }

    // ----------------------------------------------------------------- //
    // 3) searchKeycloakUsers(search, first, max)
    // ----------------------------------------------------------------- //

    @Test
    public void searchKeycloakUsers_mapsUser_andEncodesQuery() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            UserRepresentation u = new UserRepresentation();
            u.setId("u-1");
            u.setUsername("alice");
            u.setEmail("alice@example.com");
            u.setFirstName("Alice");
            u.setLastName("Liddell");
            String usersJson = MAPPER.writeValueAsString(List.of(u));

            stub.respond(req -> {
                if (req.path().equals(REALM + "/users") && req.method().equals("GET")) {
                    return HttpStub.StubResponse.json(usersJson);
                }
                return HttpStub.StubResponse.status(404);
            });

            List<UserVo> users = service(stub, authenticatedUserService())
                    .searchKeycloakUsers("alice", 0, 10);

            assertEquals(1, users.size(), "one user should be returned");
            UserVo user = users.get(0);
            assertEquals("u-1", user.getKeycloakId());
            assertEquals("alice", user.getUsername());
            assertEquals("alice@example.com", user.getEmail());
            assertEquals("Alice", user.getFirstName());
            assertEquals("Liddell", user.getLastName());

            HttpStub.RecordedRequest req = stub.firstRequestForPath(REALM + "/users");
            assertNotNull(req);
            String query = req.query();
            assertNotNull(query, "query string should be present");
            assertTrue(query.contains("search=alice"),
                    "query should carry the search term, was: " + query);
            assertTrue(query.contains("first=0"), "query should carry first=0, was: " + query);
            assertTrue(query.contains("max=10"), "query should carry max=10, was: " + query);
            assertEquals("Bearer test-token", req.header("Authorization"));
        }
    }

    // ----------------------------------------------------------------- //
    // 4) addKeycloakUser(UserVo)
    // ----------------------------------------------------------------- //

    @Test
    public void addKeycloakUser_postsUserRepresentation() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            // Server simply accepts the POST. No password => resetKeycloakPassword is a no-op.
            stub.respond(req -> {
                if (req.path().equals(REALM + "/users") && req.method().equals("POST")) {
                    return HttpStub.StubResponse.status(201);
                }
                return HttpStub.StubResponse.status(404);
            });

            UserVo newUser = new UserVo();
            newUser.setUsername("bob");
            newUser.setEmail("bob@example.com");
            newUser.setFirstName("Bob");
            newUser.setLastName("Builder");
            // keycloakPassword left blank on purpose -> no reset-password call

            service(stub, authenticatedUserService()).addKeycloakUser(newUser);

            HttpStub.RecordedRequest req = stub.firstRequestForPath(REALM + "/users");
            assertNotNull(req, "a request should have hit the /users endpoint");
            assertEquals("POST", req.method(), "user creation must be a POST");

            String body = req.bodyString();
            assertTrue(body.contains("bob"),
                    "posted body should contain the username, was: " + body);
            assertTrue(body.contains("bob@example.com"),
                    "posted body should contain the email, was: " + body);

            assertEquals("Bearer test-token", req.header("Authorization"));

            // Since no password was supplied, only the single POST should have been made
            // (no follow-up reset-password PUT).
            assertEquals(1, stub.requestCount(),
                    "exactly one Keycloak request expected (no password reset)");
        }
    }

    // ----------------------------------------------------------------- //
    // 4b) addKeycloakUser(UserVo) - Keycloak rejects the creation
    // ----------------------------------------------------------------- //

    @Test
    public void addKeycloakUser_rejectedCreation_throwsAndDoesNotSucceedSilently() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            // Keycloak rejects the creation, e.g. a duplicate username/email -> 409 Conflict.
            // The admin client's users().create() returns the response rather than throwing, so the
            // service must inspect the status and fail; otherwise the caller would persist a local
            // user Keycloak never created.
            stub.respond(req -> {
                if (req.path().equals(REALM + "/users") && req.method().equals("POST")) {
                    return HttpStub.StubResponse.status(409);
                }
                return HttpStub.StubResponse.status(404);
            });

            UserVo dup = new UserVo();
            dup.setUsername("bob");
            dup.setEmail("bob@example.com");
            // no password -> the create is the only call; the failure must surface from it

            try {
                service(stub, authenticatedUserService()).addKeycloakUser(dup);
                fail("expected an exception when Keycloak rejects the user creation");
            } catch (Exception e) {
                assertNotNull(e.getMessage());
                assertTrue(e.getMessage().contains("409"),
                        "message should report the failing status, was: " + e.getMessage());
            }

            // The create was actually attempted with the bearer token...
            HttpStub.RecordedRequest req = stub.firstRequestForPath(REALM + "/users");
            assertNotNull(req, "a request should have hit the /users endpoint");
            assertEquals("POST", req.method());
            assertEquals("Bearer test-token", req.header("Authorization"));
        }
    }

    // ----------------------------------------------------------------- //
    // 5) Authentication failure
    // ----------------------------------------------------------------- //

    @Test
    public void getKeycloakDomainIds_unauthenticated_throws() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson("[]");

            KeycloakIntegrationService svc = service(stub, unauthenticatedUserService());

            try {
                svc.getKeycloakDomainIds();
                fail("expected an exception when the caller is not authenticated");
            } catch (Exception e) {
                assertNotNull(e.getMessage());
                assertTrue(e.getMessage().contains("not authenticated"),
                        "message should mention authentication, was: " + e.getMessage());
            }

            // The auth check must short-circuit before any HTTP call is made.
            assertEquals(0, stub.requestCount(),
                    "no HTTP request should be sent when unauthenticated");
        }
    }

    // ----------------------------------------------------------------- //
    // 6) Upstream error status
    // ----------------------------------------------------------------- //

    @Test
    public void getKeycloakDomainIds_upstream500_throwsWithStatus() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respond(req -> {
                if (req.path().equals(REALM + "/clients")) {
                    return HttpStub.StubResponse.status(500);
                }
                return HttpStub.StubResponse.status(404);
            });

            KeycloakIntegrationService svc = service(stub, authenticatedUserService());

            try {
                svc.getKeycloakDomainIds();
                fail("expected an exception on a 500 upstream response");
            } catch (Exception e) {
                assertNotNull(e.getMessage());
                assertTrue(e.getMessage().contains("status = 500"),
                        "message should report the failing status, was: " + e.getMessage());
            }

            // The request was still attempted (and carried the token).
            HttpStub.RecordedRequest req = stub.firstRequestForPath(REALM + "/clients");
            assertNotNull(req);
            assertEquals("Bearer test-token", req.header("Authorization"));
        }
    }
}
