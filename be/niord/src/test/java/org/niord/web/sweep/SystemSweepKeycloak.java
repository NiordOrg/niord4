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
package org.niord.web.sweep;

import org.keycloak.representations.idm.GroupRepresentation;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.test.NiordTestContainers;
import org.niord.test.http.HttpStub;
import org.niord.test.http.KeycloakAdminStub;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.niord.web.sweep.SystemSweepFixture.USER;
import static org.niord.web.sweep.SystemSweepFixture.USER_EMAIL;
import static org.niord.web.sweep.SystemSweepFixture.map;

/**
 * The cases of {@code UserRestService}. The local user directory reads ({@code all}, {@code search},
 * {@code search-emails}) read the fixture's user. The Keycloak handlers forward the caller's token to Keycloak's
 * admin interface, which decides by the realm role {@code manage-users}: their cells are sent to the test realm's own
 * Keycloak (the container every IT signs in with), where {@code testadmin} lacks the role and {@code sysadmin}
 * holds it, so the cells record what the backend answers when Keycloak refuses; their sweeps (the data) run against a
 * {@link KeycloakAdminStub} loaded with a user and a group. Either way the service is pointed there by its
 * {@code authServerUrl} field, as {@code UserDeleteMailingListsITTest} does, and pointed back: by the sweep's
 * {@code finally} after every Keycloak entry, and by the tear-down. The IT setting itself names no reachable Keycloak.
 * A refused cell of a write is a request that changes nothing even if Keycloak let it through (an unknown user or
 * group, an existing user name).
 */
final class SystemSweepKeycloak {

    /** The stub's user and group (names of the group: no table holds them) */
    static final String KC_USER = "sweep-system-kc-user";
    static final String KC_GROUP = "sweep-system-kc-group";
    static final String KC_USER_ID = KC_USER + "-id";
    static final String KC_GROUP_ID = KC_GROUP + "-id";
    /** A second group of the stub, mapped to a role no caller holds (a name, not a row) */
    static final String KC_UNHELD = "sweep-system-kc-unheld";
    static final String KC_UNHELD_ID = KC_UNHELD + "-id";
    /** The role the second group maps: the test realm grants it to no one */
    static final String UNHELD_ROLE = "sweep-system-unheld-role";
    /** Ids no user or group of the test realm has */
    static final String NO_USER = "sweep-system-no-such-user";
    static final String NO_GROUP = "sweep-system-no-such-group";

    private static final String USERS_PATH = "/admin/realms/" + KeycloakAdminStub.REALM + "/users";

    /** The service's own setting, from before the sweep pointed it anywhere */
    private static Object original;

    @FunctionalInterface
    interface StubStep {
        void run(KeycloakAdminStub stub) throws Exception;
    }

    private SystemSweepKeycloak() {
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    static void cases(SweepCases cases) {
        cases.add("allUsers", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/users/all")));

        cases.add("searchUsers", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/users/search").query("name", USER))
                .sweep((ctx, as) -> {
                    SweepResponse found = ctx.ok(as, SweepRequest.get("/rest/users/search").query("name", USER));
                    if (found.is2xx()) {
                        ctx.checkEquals(USER_EMAIL, found.json().getString("find { it.username == '" + USER
                                + "' }.email"), "the user's e-mail address", found);
                    }
                })
                .variants((ctx, as) -> {
                    SweepResponse one = ctx.ok(as, SweepRequest.get("/rest/users/search").query("name", "s"));
                    ctx.checkEquals(List.of(), one.json().getList("$"), "the users a one-character name finds", one);
                }));

        cases.add("searchEmails", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/users/search-emails").query("name", USER))
                .sweep((ctx, as) -> {
                    SweepResponse found = ctx.ok(as, SweepRequest.get("/rest/users/search-emails").query("name", USER));
                    if (found.is2xx()) {
                        ctx.check(found.json().getList("$").contains(USER + " <" + USER_EMAIL + ">"),
                                "the suggestions hold \"" + USER + " <" + USER_EMAIL + ">\"", found);
                    }
                })
                .variants((ctx, as) -> {
                    String address = "sweep-system-echo@example.org";
                    SweepResponse echo = ctx.ok(as, SweepRequest.get("/rest/users/search-emails").query("name", address));
                    ctx.checkEquals(address, echo.json().getString("[0]"), "an address searched for comes first", echo);
                }));

        cases.add("searchKeycloakUsers", SweepCase
                .of((ctx, row) -> realm(ctx, SweepRequest.get("/rest/users/kc-users")))
                .as("sysadmin")
                .sweep((ctx, as) -> withStub(ctx, stub -> {
                    SweepResponse users = ctx.ok(as, SweepRequest.get("/rest/users/kc-users").query("search", KC_USER));
                    if (users.is2xx()) {
                        ctx.checkEquals(KC_USER_ID, users.json().getString("find { it.username == '" + KC_USER
                                + "' }.keycloakId"), "the Keycloak user's id", users);
                    }
                })));

        cases.add("getKeycloakGroups", SweepCase
                .of((ctx, row) -> realm(ctx, SweepRequest.get("/rest/users/kc-groups")))
                .as("sysadmin")
                .sweep((ctx, as) -> withStub(ctx, stub -> {
                    // The group maps the role user of niord-nw: a caller in niord-nw holds it
                    SweepResponse groups = ctx.ok(as, SweepRequest.get("/rest/users/kc-groups"));
                    if (groups.is2xx()) {
                        ctx.checkEquals(true, groups.json().get("find { it.id == '" + KC_GROUP_ID + "' }.active"),
                                "the group is active for a caller of niord-nw", groups);
                        // Active means the caller holds a role the group maps, not that the group maps any
                        ctx.checkEquals(false, groups.json().get("find { it.id == '" + KC_UNHELD_ID + "' }.active"),
                                "the group of " + UNHELD_ROLE + ", a role the caller lacks, is not active", groups);
                    }
                    // In niord-nm, whose client maps no role to the group, it is not
                    SweepResponse other = ctx.ok(as, SweepRequest.get("/rest/users/kc-groups")
                            .domain(SweepIdentity.OTHER_DOMAIN));
                    if (other.is2xx()) {
                        ctx.checkEquals(false, other.json().get("find { it.id == '" + KC_GROUP_ID + "' }.active"),
                                "the group is not active in niord-nm", other);
                    }
                })));

        cases.add("getKeycloakUserGroups", SweepCase
                .of((ctx, row) -> realm(ctx, userGroups(NO_USER)))
                .as("sysadmin")
                .sweep((ctx, as) -> withStub(ctx, stub -> {
                    SweepResponse groups = ctx.ok(as, userGroups(KC_USER_ID));
                    if (groups.is2xx()) {
                        ctx.checkEquals(List.of(KC_GROUP_ID), groups.json().getList("id"), "the user's groups",
                                groups);
                        ctx.checkEquals(true, groups.json().get("[0].active"), "the group is active in niord-nw",
                                groups);
                    }
                })));

        for (String verb : List.of("PUT", "DELETE")) {
            String name = verb.equals("PUT") ? "joinKeycloakGroup" : "leaveKeycloakGroup";
            cases.add(name, SweepCase
                    .of((ctx, row) -> realm(ctx, membership(verb, NO_USER, NO_GROUP)))
                    .as("sysadmin")
                    .sweep((ctx, as) -> withStub(ctx, stub -> {
                        SweepResponse answer = ctx.ok(as, membership(verb, KC_USER_ID, KC_GROUP_ID));
                        if (answer.is2xx()) {
                            HttpStub.RecordedRequest call = stub.http().requests().stream()
                                    .filter(r -> r.method().equals(verb)
                                            && r.path().equals(USERS_PATH + "/" + KC_USER_ID + "/groups/" + KC_GROUP_ID))
                                    .findFirst().orElse(null);
                            ctx.check(call != null, "Keycloak got " + verb + " " + USERS_PATH + "/" + KC_USER_ID
                                    + "/groups/" + KC_GROUP_ID, answer);
                            ctx.check(call != null && call.header("Authorization") != null
                                    && call.header("Authorization").startsWith("Bearer "),
                                    "the call to Keycloak carries the caller's bearer token", answer);
                        }
                    })));
        }

        cases.add("addKeycloakUser", SweepCase
                // An existing user name: had Keycloak let it through, it would refuse the duplicate
                .of((ctx, row) -> realm(ctx, SweepRequest.post("/rest/users/kc-user").json(userBody(null, "testuser",
                        "sweep-system-refused@example.org", "Sweep", "refused"))))
                .as("sysadmin")
                .sweep((ctx, as) -> withStub(ctx, stub -> addReadBackAndRemove(ctx, as, stub, "/rest/users/kc-user")))
                .variants((ctx, as) -> withStub(ctx, stub ->
                        addReadBackAndRemove(ctx, as, stub, "/rest/users/kc-user/"))));

        cases.add("updateKeycloakUser", SweepCase
                .of((ctx, row) -> realm(ctx, SweepRequest.put("/rest/users/kc-user/{userId}").var("userId", NO_USER)
                        .json(userBody(NO_USER, ctx.name("refused"), "sweep-system-refused@example.org", "Sweep",
                                "refused"))))
                .as("sysadmin")
                .sweep((ctx, as) -> withStub(ctx, stub -> {
                    String username = ctx.name("kc-update-" + ctx.unique());
                    String id = username + "-id";
                    try {
                        ctx.ok(as, SweepRequest.post("/rest/users/kc-user").json(userBody(null, username,
                                username + "@example.org", "Sweep", username)));
                        SweepResponse answer = ctx.ok(as, SweepRequest.put("/rest/users/kc-user/{userId}")
                                .var("userId", id).json(userBody(id, username, username + "-changed@example.org",
                                        "Changed", username + "-changed")));
                        if (answer.is2xx()) {
                            HttpStub.RecordedRequest put = stub.http().requests().stream()
                                    .filter(r -> r.method().equals("PUT") && r.path().equals(USERS_PATH + "/" + id))
                                    .findFirst().orElse(null);
                            ctx.check(put != null && put.bodyString().contains(username + "-changed@example.org"),
                                    "Keycloak got the changed user (PUT " + USERS_PATH + "/" + id + ")", answer);
                            readUserBack(ctx, username, username + "-changed@example.org", "Changed",
                                    username + "-changed");
                        }
                    } finally {
                        removeAndReadGone(ctx, username);
                    }
                }))
                .probes((ctx, as) -> withStub(ctx, stub -> ctx.call(as.name(),
                        SweepRequest.put("/rest/users/kc-user/{userId}").var("userId", "sweep-system-a")
                                .json(userBody("sweep-system-b", ctx.name("mismatch"), null, null, null))
                                .note("the body's id is not the path's"), Cell.status(400, null)))));

        cases.add("deleteKeycloakUser", SweepCase
                .of((ctx, row) -> realm(ctx, SweepRequest.delete("/rest/users/kc-user/{userId}").var("userId", NO_USER)))
                .as("sysadmin")
                .sweep((ctx, as) -> {
                    String username = ctx.name("kc-delete-" + ctx.unique());
                    String id = username + "-id";
                    try {
                        withStub(ctx, stub -> {
                            ctx.ok(as, SweepRequest.post("/rest/users/kc-user").json(userBody(null, username,
                                    username + "@example.org", "Sweep", username)));
                            stub.withUser(id, username);
                            SweepResponse answer = ctx.ok(as, SweepRequest.delete("/rest/users/kc-user/{userId}")
                                    .var("userId", id));
                            if (answer.is2xx()) {
                                ctx.check(stub.http().requests().stream().anyMatch(r -> r.method().equals("DELETE")
                                                && r.path().equals(USERS_PATH + "/" + id)),
                                        "Keycloak got DELETE " + USERS_PATH + "/" + id, answer);
                                // Niord's record stays, without an address to mail
                                SweepResponse read = ctx.ok("admin", SweepRequest.get("/rest/users/all"));
                                ctx.checkEquals(null, read.json().getString("find { it.username == '" + username
                                        + "' }.email"), "the deleted user's e-mail address", read);
                            }
                        });
                    } finally {
                        removeAndReadGone(ctx, username);
                    }
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers of the cases
    // ------------------------------------------------------------------------------------------- //

    static SweepRequest userGroups(String userId) {
        return SweepRequest.get("/rest/users/kc-user/{userId}/kc-groups").var("userId", userId);
    }

    static SweepRequest membership(String verb, String userId, String groupId) {
        String pattern = "/rest/users/kc-user/{userId}/kc-groups/{groupId}";
        SweepRequest request = verb.equals("PUT") ? SweepRequest.put(pattern) : SweepRequest.delete(pattern);
        return request.var("userId", userId).var("groupId", groupId);
    }

    static Map<String, Object> userBody(String keycloakId, String username, String email, String firstName,
                                        String lastName) {
        return map("keycloakId", keycloakId, "username", username, "email", email, "firstName", firstName,
                "lastName", lastName);
    }

    /** The create sweep: create through the stub, read the local record back, remove it, read it gone */
    private static void addReadBackAndRemove(SweepContext ctx, SweepIdentity as, KeycloakAdminStub stub,
                                             String pattern) {
        String username = ctx.name("kc-add-" + ctx.unique());
        try {
            SweepResponse answer = ctx.ok(as, SweepRequest.post(pattern).json(userBody(null, username,
                    username + "@example.org", "Sweep", username)));
            if (!answer.is2xx()) {
                return;
            }
            ctx.check(stub.http().requests().stream().anyMatch(r -> r.method().equals("POST")
                            && r.path().equals(USERS_PATH) && r.bodyString().contains(username)),
                    "Keycloak got the new user (POST " + USERS_PATH + ")", answer);
            readUserBack(ctx, username, username + "@example.org", "Sweep", username);
        } finally {
            removeAndReadGone(ctx, username);
        }
    }

    /** Reads the local record of the user back through the directory and checks every field the write sent */
    private static void readUserBack(SweepContext ctx, String username, String email, String firstName,
                                     String lastName) {
        SweepResponse read = ctx.ok("admin", SweepRequest.get("/rest/users/all"));
        if (!read.is2xx()) {
            return;
        }
        String at = "find { it.username == '" + username + "' }";
        ctx.checkEquals(email, read.json().getString(at + ".email"), "the user's e-mail address", read);
        ctx.checkEquals(firstName, read.json().getString(at + ".firstName"), "the user's first name", read);
        ctx.checkEquals(lastName, read.json().getString(at + ".lastName"), "the user's last name", read);
    }

    /** Removes the local record (no endpoint removes one) and reads it gone through the directory */
    private static void removeAndReadGone(SweepContext ctx, String username) {
        SystemSweepFixture.removeUser(ctx, username);
        SweepResponse read = ctx.ok("admin", SweepRequest.get("/rest/users/all").note("read after the removal"));
        ctx.check(read.json().get("find { it.username == '" + username + "' }") == null,
                "the user " + username + " is gone from the directory", read);
    }

    // ------------------------------------------------------------------------------------------- //
    // Where the service sends its admin calls
    // ------------------------------------------------------------------------------------------- //

    /** The request, after pointing the service at the test realm's Keycloak (a cell of a Keycloak handler) */
    static SweepRequest realm(SweepContext ctx, SweepRequest request) {
        set(ctx, NiordTestContainers.keycloakAuthServerUrl());
        return request;
    }

    /** Runs the step with the service pointed at a stub loaded with the group's user and group, then points it back */
    static void withStub(SweepContext ctx, StubStep step) throws Exception {
        try (KeycloakAdminStub stub = KeycloakAdminStub.start()) {
            stub.withClient(SweepIdentity.DOMAIN)
                    .withUser(KC_USER_ID, KC_USER)
                    .withGroup(KC_GROUP_ID, KC_GROUP, "/" + KC_GROUP);
            stub.withGroupClientRoles(KC_GROUP_ID, stub.clientUuid(SweepIdentity.DOMAIN), "user");
            // A second group maps a role the sweep's sysadmin does not hold (the test realm's sysadmin composite
            // holds every Niord role, publication-curate included, so the role is one no one has): it is listed,
            // but not active for the caller
            stub.withGroup(KC_UNHELD_ID, KC_UNHELD, "/" + KC_UNHELD);
            stub.withGroupClientRoles(KC_UNHELD_ID, stub.clientUuid(SweepIdentity.DOMAIN), UNHELD_ROLE);
            GroupRepresentation group = new GroupRepresentation();
            group.setId(KC_GROUP_ID);
            group.setName(KC_GROUP);
            group.setPath("/" + KC_GROUP);
            stub.withUserGroups(KC_USER_ID, group);
            set(ctx, stub.baseUrl());
            step.run(stub);
        } finally {
            restoreTheSetting(ctx);
        }
    }

    static void rememberTheSetting(SweepContext ctx) {
        if (original == null) {
            original = ReflectionTestUtils.getField(target(ctx), "authServerUrl");
        }
    }

    static void restoreTheSetting(SweepContext ctx) {
        if (original != null) {
            set(ctx, original);
        }
    }

    private static void set(SweepContext ctx, Object url) {
        rememberTheSetting(ctx);
        ReflectionTestUtils.setField(target(ctx), "authServerUrl", url);
    }

    private static KeycloakIntegrationService target(SweepContext ctx) {
        return AopTestUtils.getTargetObject(ctx.bean(KeycloakIntegrationService.class));
    }
}
