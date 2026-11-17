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

import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.user.CurrentUserContext;
import org.niord.core.user.vo.GroupVo;
import org.niord.core.user.vo.UserVo;
import org.niord.test.http.KeycloakAdminStub;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-1 unit tests for the {@link KeycloakIntegrationService} admin operations that the existing
 * {@code KeycloakIntegrationServiceHttpTest} does not cover — domain (client) creation with its role
 * set, user-group membership, group role-mappings, join/leave and user update/delete.
 *
 * <p>The Keycloak admin API is replaced by {@link KeycloakAdminStub}, a reusable in-memory fake. No
 * Quarkus, no container: the service is instantiated directly and its package-private collaborators
 * are set by hand; the {@link CurrentUserContext} is a tiny authenticated fake.
 */
public class KeycloakIntegrationServiceAdminStubTest {

    /** Reflection field-setter that walks the class hierarchy. */
    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }

    private static CurrentUserContext authenticatedContext() {
        return new CurrentUserContext() {
            @Override
            // PORT: getCallerPrincipal() now returns a Spring Security Jwt (was a Quarkus
            // OidcJwtCallerPrincipal). A non-null return marks the caller as authenticated.
            public Jwt getCallerPrincipal() {
                return Jwt.withTokenValue("tok")
                        .header("alg", "none")
                        .claim("preferred_username", "tester")
                        .build();
            }

            @Override
            public String getKeycloakAccessToken() {
                return "test-token";
            }
        };
    }

    private static KeycloakIntegrationService service(KeycloakAdminStub stub) throws Exception {
        KeycloakIntegrationService svc = new KeycloakIntegrationService();
        set(svc, "authServerUrl", stub.baseUrl());
        set(svc, "log", LoggerFactory.getLogger("kc-admin"));
        set(svc, "currentUserContext", authenticatedContext());
        return svc;
    }

    private static Domain domain(String domainId) {
        Domain d = new Domain();
        d.setDomainId(domainId);
        d.setName("Domain " + domainId);
        return d;
    }

    @Test
    public void createKeycloakDomainCreatesTheClientAndItsRoleSet() throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start()) {
            KeycloakIntegrationService svc = service(stub);

            boolean created = svc.createKeycloakDomain(domain("niord-client-xyz"));

            assertTrue(created, "a fresh domain must be created");
            // The client now exists in Keycloak (the stub recorded the POST and stored the client).
            assertNotNull(stub.clientUuid("niord-client-xyz"),
                    "the client must have been created in Keycloak");

            // The four-role ladder (user/editor/admin/sysadmin) must have been POSTed for the client.
            String uuid = stub.clientUuid("niord-client-xyz");
            long rolePosts = stub.http().requests().stream()
                    .filter(r -> r.method().equals("POST"))
                    .filter(r -> r.path().endsWith("/clients/" + uuid + "/roles"))
                    .count();
            assertEquals(4, rolePosts, "user, editor, admin and sysadmin roles must be created");

            // Authenticated admin writes carry the bearer token.
            assertEquals("Bearer test-token",
                    stub.http().firstRequestForPath("/admin/realms/niord/clients").header("Authorization"));
        }
    }

    @Test
    public void createKeycloakDomainReturnsFalseWhenTheClientAlreadyExists() throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start().withClient("niord-client-existing")) {
            KeycloakIntegrationService svc = service(stub);

            boolean created = svc.createKeycloakDomain(domain("niord-client-existing"));

            assertFalse(created, "an existing domain must not be re-created");
            // No client POST should have been issued.
            boolean anyClientPost = stub.http().requests().stream()
                    .anyMatch(r -> r.method().equals("POST") && r.path().equals("/admin/realms/niord/clients"));
            assertFalse(anyClientPost, "no client should be POSTed when the domain already exists");
        }
    }

    @Test
    public void getKeycloakUserGroupsMapsTheGroupTree() throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start()) {
            org.keycloak.representations.idm.GroupRepresentation g =
                    new org.keycloak.representations.idm.GroupRepresentation();
            g.setId("g-1");
            g.setName("Editors");
            g.setPath("/Editors");
            stub.withUserGroups("u-1", g);

            List<GroupVo> groups = service(stub).getKeycloakUserGroups("u-1");

            assertEquals(1, groups.size());
            assertEquals("g-1", groups.get(0).getId());
            assertEquals("Editors", groups.get(0).getDesc("en").getName());
            assertEquals("/Editors", groups.get(0).getPath());
            assertEquals("Bearer test-token",
                    stub.http().firstRequestForPath("/admin/realms/niord/users/u-1/groups").header("Authorization"));
        }
    }

    @Test
    public void getKeycloakRolesReturnsTheGroupsClientRoleNames() throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start().withClient("niord-client-nw")) {
            String clientUuid = stub.clientUuid("niord-client-nw");
            stub.withGroupClientRoles("grp-7", clientUuid, "user", "editor");

            List<String> roles = service(stub).getKeycloakRoles(domain("niord-client-nw"), "grp-7");

            assertEquals(List.of("user", "editor"), roles);
        }
    }

    @Test
    public void joinAndLeaveKeycloakGroupIssuePutAndDelete() throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start()) {
            KeycloakIntegrationService svc = service(stub);

            svc.joinKeycloakGroup("u-9", "g-9");
            svc.leaveKeycloakGroup("u-9", "g-9");

            String path = "/admin/realms/niord/users/u-9/groups/g-9";
            boolean put = stub.http().requests().stream()
                    .anyMatch(r -> r.method().equals("PUT") && r.path().equals(path));
            boolean delete = stub.http().requests().stream()
                    .anyMatch(r -> r.method().equals("DELETE") && r.path().equals(path));
            assertTrue(put, "joining a group must PUT the membership");
            assertTrue(delete, "leaving a group must DELETE the membership");
        }
    }

    @Test
    public void deleteKeycloakUserIssuesADelete() throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start()) {
            service(stub).deleteKeycloakUser("u-del");

            boolean deleted = stub.http().requests().stream()
                    .anyMatch(r -> r.method().equals("DELETE") && r.path().equals("/admin/realms/niord/users/u-del"));
            assertTrue(deleted, "deleting a user must issue a DELETE on the user resource");
        }
    }

    @Test
    public void getKeycloakUsernameReadsTheUser() throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start().withUser("u-read", "leaving.user")) {
            assertEquals("leaving.user", service(stub).getKeycloakUsername("u-read"));

            assertEquals("GET", stub.http().firstRequestForPath("/admin/realms/niord/users/u-read").method());
            assertEquals("Bearer test-token",
                    stub.http().firstRequestForPath("/admin/realms/niord/users/u-read").header("Authorization"));
        }
    }

    @Test
    public void updateKeycloakUserFetchesThenPutsTheUser() throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start().withUser("u-up", "alice")) {
            UserVo user = new UserVo();
            user.setKeycloakId("u-up");
            user.setUsername("alice-renamed");
            user.setEmail("alice@example.com");

            service(stub).updateKeycloakUser(user);

            boolean got = stub.http().requests().stream()
                    .anyMatch(r -> r.method().equals("GET") && r.path().equals("/admin/realms/niord/users/u-up"));
            boolean put = stub.http().requests().stream()
                    .anyMatch(r -> r.method().equals("PUT") && r.path().equals("/admin/realms/niord/users/u-up"));
            assertTrue(got, "update must first read the existing user");
            assertTrue(put, "update must PUT the modified user");
        }
    }
}
