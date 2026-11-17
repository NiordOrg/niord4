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
 * Integration tests for the recipient-contact endpoints of {@link MailingListRestService}: the bulk
 * {@code GET/PUT /mailing-list/{id}/contacts} pair and the per-recipient
 * {@code PUT /mailing-list/{id}/update-status} endpoint, plus their authorization.
 *
 * <p>This class is the contacts-focused sibling of {@code MailingListRestServiceITTest} (which covers
 * search / life-cycle / export / reports). It reuses that class's conventions verbatim — {@code @QuarkusTest}
 * with {@link NiordContainersResource}, {@code extends ITTest}, the four-tier {@link KeycloakTestTokens}
 * identities, the {@code NiordDomain: niord-nw} header that makes the augmentor realize the full role
 * chain, RestAssured {@code given()/when()}, id-tracked best-effort cleanup as sysadmin (HTTP runs in the
 * server's own transaction that {@code @Transactional} cannot roll back), and the seeded
 * {@code audio-broadcast} mailing list.
 *
 * <p><b>Authorization model.</b> The class default is {@code @RolesAllowed(ADMIN)}; the recipient-contact
 * endpoints inherit it. So {@code GET/PUT /contacts} and {@code update-status} require {@code ADMIN} or
 * above; an authenticated {@code user}/{@code editor} is one+ tiers too low and gets {@code 403}, while a
 * missing token gets the container's {@code 401}. (The role hierarchy is {@code sysadmin > admin > editor >
 * user}; see {@link org.niord.core.user.Roles}.)
 *
 * <p><b>Persisted-recipient oracle.</b> {@code updateMailingListRecipients} resolves submitted contacts
 * through {@code BaseService.persistedList(Contact.class, ...)}, which keeps only contacts that already exist
 * <em>by primary-key id</em> ({@code getByPrimaryKey}, null-filtered). The seed leaves the {@code Contact}
 * table empty, so a recipient cannot be added unless a persisted {@code Contact} row exists first. Each test
 * that adds a recipient therefore creates a real contact via the ADMIN {@code POST /rest/contacts/contact/}
 * endpoint (capturing its server-assigned id) and submits a {@code ContactVo} carrying that id; the contact
 * is best-effort deleted in {@link #cleanup()}. Recipient membership is verified by re-reading
 * {@code GET /contacts} and asserting the contact's email is present in {@code selectedRecipients} (and,
 * after removal, absent).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MailingListRestRecipientsITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String MAILING_LIST = "/rest/mailing-lists/mailing-list/";
    private static final String CONTACT       = "/rest/contacts/contact/";

    /** A seeded mailing list (active == false, no seeded contacts). */
    private static final String SEEDED_ID = "audio-broadcast";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    private final List<String>  createdLists    = new ArrayList<>();
    private final List<Integer> createdContacts = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdContacts) {
            try {
                as(accessToken("admin")).when().delete(CONTACT + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdContacts.clear();
        for (String id : createdLists) {
            try {
                as(accessToken()).when().delete(MAILING_LIST + id);
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
                "descs", List.of(Map.of("lang", "en", "name", "IT REST recipients list")));
    }

    /** Creates a mailing list as sysadmin, asserts 200 + echoed id, and tracks it for cleanup. */
    private String createList() {
        String id = "IT-REST-MLR-" + System.nanoTime();
        as(accessToken()).contentType(ContentType.JSON).body(listBody(id))
                .when().post(MAILING_LIST)
                .then().statusCode(200)
                .body("mailingListId", is(id));
        createdLists.add(id);
        return id;
    }

    /**
     * Creates a persisted contact as admin, asserts 200 + echoed email, captures its server-assigned id
     * (so {@code persistedList(Contact.class, ...)} can resolve it), and tracks it for cleanup.
     */
    private ContactRef createContact() {
        String email = "it-rest-mlr-" + System.nanoTime() + "@example.com";
        Integer id = as(accessToken("admin")).contentType(ContentType.JSON)
                .body(Map.of("email", email, "name", "IT REST Contact", "language", "en"))
                .when().post(CONTACT)
                .then().statusCode(200)
                .body("email", is(email))
                .extract().path("id");
        createdContacts.add(id);
        return new ContactRef(id, email);
    }

    /** A {@code ContactVo} body carrying the persisted id + email (so it survives persistedList resolution). */
    private static Map<String, Object> contactVo(ContactRef c) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", c.id);
        vo.put("email", c.email);
        vo.put("name", "IT REST Contact");
        vo.put("language", "en");
        return vo;
    }

    /** An {@code update-status} body for a contact recipient with the given recipient flag. */
    private static Map<String, Object> contactStatus(ContactRef c, boolean recipient) {
        Map<String, Object> body = new HashMap<>();
        body.put("contact", contactVo(c));
        body.put("recipient", recipient);
        return body;
    }

    private record ContactRef(Integer id, String email) {}

    // ------------------------------------------------------------------------------------------- //
    // GET /mailing-list/{id}/contacts — inherits class default @RolesAllowed(ADMIN).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getRecipientContacts_allowsAdminAndReturnsBothArrays() {
        as(accessToken("admin")).when().get(MAILING_LIST + SEEDED_ID + "/contacts")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("selectedRecipients.size()", greaterThanOrEqualTo(0))
                .body("availableRecipients.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void getRecipientContacts_rejectsAnonymousWith401() {
        given().when().get(MAILING_LIST + SEEDED_ID + "/contacts").then().statusCode(401);
    }

    @Test
    void getRecipientContacts_forbidsUserWith403() {
        as(accessToken("user")).when().get(MAILING_LIST + SEEDED_ID + "/contacts").then().statusCode(403);
    }

    @Test
    void getRecipientContacts_unknownListReturns404() {
        as(accessToken("admin")).when().get(MAILING_LIST + "IT-REST-MLR-no-such-list/contacts")
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /mailing-list/{id}/contacts — inherits class default @RolesAllowed(ADMIN).
    // A submitted contact (with a persisted id) ends up in selectedRecipients; a freshly created
    // contact starts in availableRecipients.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void putContacts_addsContactThenGetShowsItSelected() {
        String list = createList();
        ContactRef c = createContact();

        // Before the bulk update the new contact is available, not selected.
        as(accessToken("admin")).when().get(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", not(hasItem(c.email)))
                .body("availableRecipients.email", hasItem(c.email));

        // Bulk-set the recipient contacts to the single new contact.
        as(accessToken("admin")).contentType(ContentType.JSON).body(List.of(contactVo(c)))
                .when().put(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", hasItem(c.email));

        // Re-read: the contact is now a selected recipient and no longer offered as available.
        as(accessToken("admin")).when().get(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", hasItem(c.email))
                .body("availableRecipients.email", not(hasItem(c.email)));
    }

    @Test
    void putContacts_emptyListClearsRecipients() {
        String list = createList();
        ContactRef c = createContact();

        as(accessToken("admin")).contentType(ContentType.JSON).body(List.of(contactVo(c)))
                .when().put(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", hasItem(c.email));

        // Submitting an empty recipient list removes the previously selected contact.
        as(accessToken("admin")).contentType(ContentType.JSON).body(List.of())
                .when().put(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.size()", is(0));

        as(accessToken("admin")).when().get(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", not(hasItem(c.email)))
                .body("availableRecipients.email", hasItem(c.email));
    }

    @Test
    void putContacts_rejectsAnonymousWith401() {
        String list = createList();
        ContactRef c = createContact();
        given().contentType(ContentType.JSON).body(List.of(contactVo(c)))
                .when().put(MAILING_LIST + list + "/contacts")
                .then().statusCode(401);
    }

    @Test
    void putContacts_forbidsUserWith403() {
        String list = createList();
        ContactRef c = createContact();
        as(accessToken("user")).contentType(ContentType.JSON).body(List.of(contactVo(c)))
                .when().put(MAILING_LIST + list + "/contacts")
                .then().statusCode(403);
    }

    @Test
    void putContacts_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        String list = createList();
        ContactRef c = createContact();
        as(accessToken("editor")).contentType(ContentType.JSON).body(List.of(contactVo(c)))
                .when().put(MAILING_LIST + list + "/contacts")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /mailing-list/{id}/update-status — inherits class default @RolesAllowed(ADMIN).
    // Returns the boolean "updated" flag and persists only when a real change occurred.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateStatus_addContactReturnsTrueAndAppearsSelected() {
        String list = createList();
        ContactRef c = createContact();

        as(accessToken("admin")).contentType(ContentType.JSON).body(contactStatus(c, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200)
                .body(is("true"));

        as(accessToken("admin")).when().get(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", hasItem(c.email));
    }

    @Test
    void updateStatus_addAlreadyPresentContactReturnsFalseNoOp() {
        String list = createList();
        ContactRef c = createContact();

        // First add succeeds (updated == true).
        as(accessToken("admin")).contentType(ContentType.JSON).body(contactStatus(c, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("true"));

        // Repeating the add is a no-op: the contact is already a recipient, so updated == false.
        as(accessToken("admin")).contentType(ContentType.JSON).body(contactStatus(c, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("false"));

        // The contact remains a single selected recipient.
        as(accessToken("admin")).when().get(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", hasItem(c.email));
    }

    @Test
    void updateStatus_removeContactReturnsTrueAndDisappears() {
        String list = createList();
        ContactRef c = createContact();

        // Add, then remove.
        as(accessToken("admin")).contentType(ContentType.JSON).body(contactStatus(c, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("true"));

        as(accessToken("admin")).contentType(ContentType.JSON).body(contactStatus(c, false))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("true"));

        // The contact is no longer a recipient and is offered again as available.
        as(accessToken("admin")).when().get(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", not(hasItem(c.email)))
                .body("availableRecipients.email", hasItem(c.email));
    }

    @Test
    void updateStatus_removeAbsentContactReturnsFalseNoOp() {
        String list = createList();
        ContactRef c = createContact();

        // Removing a contact that was never a recipient is a no-op: updated == false.
        as(accessToken("admin")).contentType(ContentType.JSON).body(contactStatus(c, false))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(200).body(is("false"));

        as(accessToken("admin")).when().get(MAILING_LIST + list + "/contacts")
                .then().statusCode(200)
                .body("selectedRecipients.email", not(hasItem(c.email)));
    }

    @Test
    void updateStatus_neitherUserNorContactReturns400() {
        String list = createList();
        Map<String, Object> body = new HashMap<>();
        body.put("recipient", true); // no user, no contact

        as(accessToken("admin")).contentType(ContentType.JSON).body(body)
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(400);
    }

    @Test
    void updateStatus_rejectsAnonymousWith401() {
        String list = createList();
        ContactRef c = createContact();
        given().contentType(ContentType.JSON).body(contactStatus(c, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(401);
    }

    @Test
    void updateStatus_forbidsUserWith403() {
        String list = createList();
        ContactRef c = createContact();
        as(accessToken("user")).contentType(ContentType.JSON).body(contactStatus(c, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(403);
    }

    @Test
    void updateStatus_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        String list = createList();
        ContactRef c = createContact();
        as(accessToken("editor")).contentType(ContentType.JSON).body(contactStatus(c, true))
                .when().put(MAILING_LIST + list + "/update-status")
                .then().statusCode(403);
    }
}
