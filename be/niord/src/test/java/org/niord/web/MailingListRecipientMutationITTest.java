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
package org.niord.web;

import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for the recipient-<em>mutation</em> handshake of {@link MailingListRestService}: the
 * bulk {@code PUT /mailing-list/{id}/users} endpoint (entirely untested by the siblings) and the
 * <em>user</em> branch of {@code PUT /mailing-list/{id}/update-status} (the siblings exercise only the
 * <em>contact</em> branch). It also pins the 404-on-unknown-list behavior of all three mutation endpoints,
 * which flows from the shared {@code getMailingListDetails(id, true)} lookup.
 *
 * <p>This class is the mutation/user-focused sibling of {@code MailingListRestRecipientsITTest} (contacts)
 * and {@code MailingListRestServiceITTest} (life-cycle / auth). It reuses their conventions verbatim:
 * {@code @QuarkusTest} with {@link NiordContainersResource}, {@code extends ITTest}, the four-tier
 * {@link KeycloakTestTokens} identities, the {@code NiordDomain: niord-nw} header that makes the
 * augmentor realize the full role chain, RestAssured {@code given()/when()}, and id-tracked best-effort
 * cleanup as sysadmin (HTTP runs in the server's own transaction that {@code @Transactional} cannot roll
 * back). Each test creates its own unique {@code IT-REST-MLU-} mailing list so the bidirectional
 * selected/available sync starts from a known-empty recipient set.
 *
 * <p><b>Authorization model.</b> The class default is {@code @RolesAllowed(ADMIN)}; {@code PUT /users} and
 * {@code update-status} inherit it. So they require {@code ADMIN} or above; an authenticated {@code user}
 * or {@code editor} is one+ tiers too low and gets {@code 403}, while a missing token gets the container's
 * {@code 401}. (The role hierarchy is {@code sysadmin > admin > editor > user}; see
 * {@link org.niord.core.user.Roles}.)
 *
 * <p><b>Persisted-user oracle.</b> {@code updateMailingListRecipients} resolves submitted users through
 * {@code UserService.persistedUsers(...)}, which keeps only users that already exist <em>by username</em>
 * ({@code findByUsername}, null-filtered). Unlike the contact side, there is no seed-supplied {@code User}
 * row and no isolated REST way to create one ({@code addKeycloakUser} needs the unreachable Keycloak admin
 * API — see {@code UserRestServiceITTest}). {@code User} rows are instead created lazily the first time a
 * token authenticates against a handler that resolves the caller via {@code UserService.currentUser()},
 * which copies the token's {@code email} claim onto the row. The mailing-list endpoints never call
 * {@code currentUser()}, so {@link #primePersistedUser()} first hits {@code GET /rest/filters/all} (whose
 * {@code checkUserLoggedIn} resolves the caller) as the {@code testadmin} identity, guaranteeing a persisted,
 * email-bearing {@code User}. {@code getRecipientUsers} only offers as <em>available</em> users with a
 * non-blank email, so each test then discovers its target by reading the freshly-created list's
 * {@code availableRecipients} (empty-selected, so the full email-bearing set) and drives the handshake with
 * that exact username/email — keeping the submitted {@code UserVo} resolvable by {@code persistedUsers} and
 * the email matchable in {@code selectedRecipients}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MailingListRecipientMutationITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String MAILING_LIST = "/rest/mailing-lists/mailing-list/";

    /** A seeded mailing list (active == false, no seeded recipients). */
    private static final String SEEDED_ID = "audio-broadcast";

    // Tokens are minted once from the Keycloak container and reused across the suite.
    private static String userToken;
    private static String editorToken;
    private static String adminToken;
    private static String sysadminToken;

    private static String user()     { return userToken     != null ? userToken     : (userToken     = KeycloakTestTokens.userAccessToken()); }
    private static String editor()   { return editorToken   != null ? editorToken   : (editorToken   = KeycloakTestTokens.editorAccessToken()); }
    private static String admin()    { return adminToken    != null ? adminToken    : (adminToken    = KeycloakTestTokens.adminAccessToken()); }
    private static String sysadmin() { return sysadminToken != null ? sysadminToken : (sysadminToken = KeycloakTestTokens.sysadminAccessToken()); }

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    private final List<String> createdLists = new ArrayList<>();

    /**
     * Materializes a persisted, email-bearing {@code User} row by authenticating the {@code testadmin}
     * identity against a handler that resolves the caller via {@code UserService.currentUser()}. The
     * mailing-list recipient endpoints never call {@code currentUser()}, so without this the
     * {@code availableRecipients} list would be empty and no user could be added. {@code GET /rest/filters/all}
     * is {@code @PermitAll} but calls {@code checkUserLoggedIn()} -> {@code currentUser()}, which creates the
     * row (copying the token's {@code email} claim) the first time and is an idempotent no-op afterwards.
     */
    @BeforeEach
    void primePersistedUser() {
        as(admin()).when().get("/rest/filters/all").then().statusCode(200);
    }

    @AfterEach
    void cleanup() {
        for (String id : createdLists) {
            try {
                as(sysadmin()).when().delete(MAILING_LIST + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdLists.clear();
    }

    /** Body for a minimal, valid mailing list with the given id. */
    private static Map<String, Object> listBody(String id) {
        return Map.of(
                "mailingListId", id,
                "active", true,
                "descs", List.of(Map.of("lang", "en", "name", "IT REST recipient-mutation list")));
    }

    /** Creates a mailing list as sysadmin, asserts 200 + echoed id, and tracks it for cleanup. */
    private String createList() {
        String id = "IT-REST-MLU-" + System.nanoTime();
        as(sysadmin()).contentType(ContentType.JSON).body(listBody(id))
                .when().post(MAILING_LIST)
                .then().statusCode(200)
                .body("mailingListId", is(id));
        createdLists.add(id);
        return id;
    }

    /**
     * Discovers a real, persisted, email-bearing user by reading {@code GET /users} of the given list and
     * returning its first {@code availableRecipients} entry. On a freshly created list no user is selected,
     * so this is simply the first user that {@code allUsers()} offers with a non-blank email — guaranteed
     * non-empty because {@link #primePersistedUser()} has persisted the {@code testadmin} {@code User} row.
     */
    private UserRef firstAvailableUser(String list) {
        var resp = as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("availableRecipients.size()", greaterThanOrEqualTo(1))
                .extract();
        return new UserRef(
                resp.path("availableRecipients[0].username"),
                resp.path("availableRecipients[0].email"));
    }

    /** A {@code UserVo} body carrying the persisted username + email (so it survives persistedUsers). */
    private static Map<String, Object> userVo(UserRef u) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("username", u.username);
        vo.put("email", u.email);
        return vo;
    }

    /** An {@code update-status} body for a user recipient with the given recipient flag. */
    private static Map<String, Object> userStatus(UserRef u, boolean recipient) {
        Map<String, Object> body = new HashMap<>();
        body.put("user", userVo(u));
        body.put("recipient", recipient);
        return body;
    }

    private record UserRef(String username, String email) {}

    // ------------------------------------------------------------------------------------------- //
    // PUT /mailing-list/{id}/update-status — user branch. Add a user -> it appears as a selected
    // recipient and is removed from available; remove -> it moves back to available. The boolean
    // "updated" result reflects whether a real change occurred.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateStatus_addUserReturnsTrueAndAppearsSelectedNotAvailable() {
        String list = createList();
        UserRef u = firstAvailableUser(list);

        // Before: the user is available, not selected.
        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.email", not(hasItem(u.email)))
                .body("availableRecipients.email", hasItem(u.email));

        // Add the user: a real change, so updated == true.
        as(admin()).contentType(ContentType.JSON).body(userStatus(u, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("true"));

        // After: the user is a selected recipient and no longer offered as available.
        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.username", hasItem(u.username))
                .body("selectedRecipients.email", hasItem(u.email))
                .body("availableRecipients.email", not(hasItem(u.email)));
    }

    @Test
    void updateStatus_addAlreadyPresentUserReturnsFalseNoOp() {
        String list = createList();
        UserRef u = firstAvailableUser(list);

        // First add succeeds (updated == true).
        as(admin()).contentType(ContentType.JSON).body(userStatus(u, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("true"));

        // Repeating the add is a no-op: the user is already a recipient, so updated == false.
        as(admin()).contentType(ContentType.JSON).body(userStatus(u, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("false"));

        // The user remains a selected recipient (not duplicated, still present).
        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.username", hasItem(u.username));
    }

    @Test
    void updateStatus_removeUserReturnsTrueAndMovesBackToAvailable() {
        String list = createList();
        UserRef u = firstAvailableUser(list);

        // Add, then remove.
        as(admin()).contentType(ContentType.JSON).body(userStatus(u, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("true"));

        as(admin()).contentType(ContentType.JSON).body(userStatus(u, false))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("true"));

        // The user is no longer a recipient and is offered again as available.
        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.email", not(hasItem(u.email)))
                .body("availableRecipients.email", hasItem(u.email));
    }

    @Test
    void updateStatus_removeAbsentUserReturnsFalseNoOp() {
        String list = createList();
        UserRef u = firstAvailableUser(list);

        // Removing a user that was never a recipient is a no-op: updated == false.
        as(admin()).contentType(ContentType.JSON).body(userStatus(u, false))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("false"));

        // It is still merely available, never selected.
        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.email", not(hasItem(u.email)))
                .body("availableRecipients.email", hasItem(u.email));
    }

    @Test
    void updateStatus_unknownListReturns404() {
        // The user branch is reached only after getMailingListDetails(id, true), which 404s first.
        UserRef u = firstAvailableUser(SEEDED_ID);
        as(admin()).contentType(ContentType.JSON).body(userStatus(u, true))
                .when().put(MAILING_LIST + "IT-REST-MLU-no-such-list/update-status")
                .then().statusCode(404);
    }

    @Test
    void updateStatus_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        String list = createList();
        UserRef u = firstAvailableUser(list);
        as(editor()).contentType(ContentType.JSON).body(userStatus(u, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(403);
    }

    @Test
    void updateStatus_rejectsAnonymousWith401() {
        String list = createList();
        UserRef u = firstAvailableUser(list);
        given().contentType(ContentType.JSON).body(userStatus(u, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /mailing-list/{id}/users — bulk set. The response and a re-read reflect exactly the
    // requested (persistable) set; an unknown username is silently dropped by persistedUsers.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void putUsers_setsUserThenGetShowsItSelectedNotAvailable() {
        String list = createList();
        UserRef u = firstAvailableUser(list);

        // Before the bulk update the user is available, not selected.
        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.email", not(hasItem(u.email)))
                .body("availableRecipients.email", hasItem(u.email));

        // Bulk-set the recipient users to the single discovered user; the response already reflects it.
        as(admin()).contentType(ContentType.JSON).body(List.of(userVo(u)))
                .when().put(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.username", hasItem(u.username))
                .body("selectedRecipients.email", hasItem(u.email))
                .body("availableRecipients.email", not(hasItem(u.email)));

        // Re-read confirms persistence: the user is selected and no longer available.
        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.username", hasItem(u.username))
                .body("availableRecipients.email", not(hasItem(u.email)));
    }

    @Test
    void putUsers_emptyListClearsRecipients() {
        String list = createList();
        UserRef u = firstAvailableUser(list);

        // Establish a selected user.
        as(admin()).contentType(ContentType.JSON).body(List.of(userVo(u)))
                .when().put(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.email", hasItem(u.email));

        // Submitting an empty recipient list removes the previously selected user.
        as(admin()).contentType(ContentType.JSON).body(List.of())
                .when().put(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.size()", is(0));

        // Re-read: the user is back among the available recipients, none selected.
        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.size()", is(0))
                .body("availableRecipients.email", hasItem(u.email));
    }

    @Test
    void putUsers_unknownUsernameIsDroppedAndRecipientsRemainEmpty() {
        // persistedUsers resolves by username; an unknown username is null-filtered out, so the
        // recipient set stays empty rather than failing — an observable consequence of that contract.
        String list = createList();
        Map<String, Object> ghost = new HashMap<>();
        ghost.put("username", "IT-REST-MLU-ghost-" + System.nanoTime());
        ghost.put("email", "it-rest-mlu-ghost@example.com");

        as(admin()).contentType(ContentType.JSON).body(List.of(ghost))
                .when().put(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.size()", is(0));

        as(admin()).when().get(MAILING_LIST + list + "/users")
                .then().statusCode(200)
                .body("selectedRecipients.size()", is(0));
    }

    @Test
    void putUsers_unknownListReturns404() {
        UserRef u = firstAvailableUser(SEEDED_ID);
        as(admin()).contentType(ContentType.JSON).body(List.of(userVo(u)))
                .when().put(MAILING_LIST + "IT-REST-MLU-no-such-list/users")
                .then().statusCode(404);
    }

    @Test
    void putUsers_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        String list = createList();
        UserRef u = firstAvailableUser(list);
        as(editor()).contentType(ContentType.JSON).body(List.of(userVo(u)))
                .when().put(MAILING_LIST + list + "/users")
                .then().statusCode(403);
    }

    @Test
    void putUsers_forbidsUserWith403() {
        String list = createList();
        UserRef u = firstAvailableUser(list);
        as(user()).contentType(ContentType.JSON).body(List.of(userVo(u)))
                .when().put(MAILING_LIST + list + "/users")
                .then().statusCode(403);
    }

    @Test
    void putUsers_rejectsAnonymousWith401() {
        String list = createList();
        UserRef u = firstAvailableUser(list);
        given().contentType(ContentType.JSON).body(List.of(userVo(u)))
                .when().put(MAILING_LIST + list + "/users")
                .then().statusCode(401);
    }
}
