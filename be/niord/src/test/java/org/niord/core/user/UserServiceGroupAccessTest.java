/*
 * Copyright 2026 Danish Maritime Authority.
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

import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.core.user.vo.GroupVo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of which Keycloak groups {@link UserService#getKeycloakGroups(Domain)} marks active: the groups an
 * administrator is offered to add a user to, or remove one from, on the Users &amp; Contacts page. A group is active
 * when it maps one of the four domain roles that the caller holds; a client role outside the four gives nothing, even
 * to a caller whose token holds it (a realm set up from an earlier version may keep the role
 * {@code publication-curate} in the admin composite and in a group's mapping).
 */
class UserServiceGroupAccessTest {

    private static final String KEPT_ROLE = "publication-curate";

    private static GroupVo group(String id) {
        GroupVo group = new GroupVo();
        group.setId(id);
        return group;
    }

    /** The service, for a signed-in caller holding the roles, against groups that map the given client roles */
    private static UserService service(Set<String> callerRoles, Map<String, List<String>> groupRoles)
            throws Exception {
        UserService svc = new UserService();
        CurrentUserContext ctx = mock(CurrentUserContext.class);
        when(ctx.isAnonymous()).thenReturn(false);
        when(ctx.currentUserRoles()).thenReturn(callerRoles);
        KeycloakIntegrationService keycloak = mock(KeycloakIntegrationService.class);
        when(keycloak.getKeycloakGroups()).thenReturn(groupRoles.keySet().stream().map(UserServiceGroupAccessTest::group)
                .toList());
        when(keycloak.getKeycloakRoles(any(), anyString()))
                .thenAnswer(call -> groupRoles.get(call.<String>getArgument(1)));
        svc.currentUserContext = ctx;
        svc.keycloakIntegrationService = keycloak;
        return svc;
    }

    private static boolean active(List<GroupVo> groups, String id) {
        return groups.stream().filter(g -> g.getId().equals(id)).findFirst().orElseThrow().isActive();
    }

    @Test
    void aGroupMappingOnlyAKeptNonDomainRoleIsNotActiveForACallerHoldingIt() throws Exception {
        // An administrator whose admin composite still carries the kept role
        UserService svc = service(Set.of("user", "editor", "admin", KEPT_ROLE), Map.of(
                "kept-only", List.of(KEPT_ROLE),
                "sysadmin-and-kept", List.of("sysadmin", KEPT_ROLE),
                "admin", List.of("admin")));

        List<GroupVo> groups = svc.getKeycloakGroups(new Domain());

        assertEquals(false, active(groups, "kept-only"),
                "a group that maps only a role outside the four is not offered to the administrator");
        assertEquals(false, active(groups, "sysadmin-and-kept"),
                "a system administrators' group that also maps the kept role is not offered to an administrator");
        assertEquals(true, active(groups, "admin"), "a group that maps a domain role the caller holds is offered");
    }

    @Test
    void aGroupMappingADomainRoleTheCallerLacksIsNotActive() throws Exception {
        UserService svc = service(Set.of("user", "editor"), Map.of(
                "admins", List.of("admin"),
                "users", List.of("user")));

        List<GroupVo> groups = svc.getKeycloakGroups(new Domain());

        assertEquals(false, active(groups, "admins"), "the administrators' group is not offered to an editor");
        assertEquals(true, active(groups, "users"), "the users' group is offered to an editor");
    }
}
