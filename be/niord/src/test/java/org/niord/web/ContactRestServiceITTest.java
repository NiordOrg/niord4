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

import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ContactRestService}: the HTTP contract of the {@code /rest/contacts}
 * endpoints, including authorization. Unlike most other REST services in the app, <em>every</em>
 * endpoint here is protected: the class carries {@code @RolesAllowed(ADMIN)}, so even the read/search
 * endpoints require an {@code admin} token. {@code /export} is annotated {@code @PermitAll} but checks
 * the {@code admin} role programmatically. Positive cases use a real Keycloak {@code sysadmin} token
 * (which carries the {@code admin} role).
 *
 * <p>No {@code Contact} rows are seeded, so every test creates its own data through the API. HTTP
 * requests run in the server's own transaction, which {@code @Transactional} cannot roll back, so
 * created contacts are committed; their ids are tracked and deleted in {@link #cleanup()} using a
 * unique {@code IT-REST-} email/name prefix that never occurs in the seed data, keeping the tests
 * independent and repeatable.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ContactRestServiceITTest extends ITTest {

    private final List<Integer> createdContactIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdContactIds) {
            try {
                given().auth().oauth2(accessToken()).delete("/rest/contacts/contact/" + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdContactIds.clear();
    }

    /** Creates a contact through the REST API as admin, tracking it for cleanup, and returns its id. */
    private int createContactAsAdmin(String email, String name, String language) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        if (name != null) {
            body.put("name", name);
        }
        if (language != null) {
            body.put("language", language);
        }
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/rest/contacts/contact/")
                .then().statusCode(200)
                .extract().path("id");
        createdContactIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — every endpoint is admin-protected, so no token yields 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchWithoutTokenIsUnauthorized() {
        // Reads are admin-protected here (class-level @RolesAllowed(ADMIN)), unlike most services.
        given().queryParam("name", "anything")
                .when().get("/rest/contacts/search")
                .then().statusCode(401);
    }

    @Test
    void addContactWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("email", "IT-REST-noauth@example.com"))
                .when().post("/rest/contacts/contact/")
                .then().statusCode(401);
    }

    @Test
    void updateContactWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("id", 1, "email", "IT-REST-noauth@example.com"))
                .when().put("/rest/contacts/contact/1")
                .then().statusCode(401);
    }

    @Test
    void deleteContactWithoutTokenIsUnauthorized() {
        given().when().delete("/rest/contacts/contact/1").then().statusCode(401);
    }

    @Test
    void importEmailsWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("emails", "IT-REST-noauth@example.com"))
                .when().post("/rest/contacts/import-emails")
                .then().statusCode(401);
    }

    @Test
    void exportWithoutTokenIsForbidden() {
        // /export is @PermitAll, so it is reachable without a token, but checks the admin role
        // programmatically and throws 403 for an anonymous caller (not 401).
        given().when().get("/rest/contacts/export")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // search — admin happy path & response shape
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchAsAdminReturnsPagedResultWrapper() {
        // searchContacts() lower-cases both the term and the name/email columns (restored during the
        // Postgres migration — see potential-errors.md PG3), so the stored casing is immaterial; the
        // contact data uses an all-lower-case it-rest- token simply as a uniform fixture choice
        // (still unique and absent from the seed); ids drive cleanup, not the prefix casing.
        createContactAsAdmin("it-rest-search1@example.com", "it-rest-searchsubject", "en");

        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-searchsubject")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("size", is(1))
                .body("data.size()", is(1))
                .body("data[0].name", is("it-rest-searchsubject"))
                .body("data[0].email", is("it-rest-search1@example.com"))
                .body("data[0].id", notNullValue());
    }

    @Test
    void searchMatchesOnEmailSubstring() {
        createContactAsAdmin("IT-REST-bymail-unique@example.com", "Unrelated Name", "da");

        // The search term matches the email column even though it does not match the name.
        given().auth().oauth2(accessToken())
                .queryParam("name", "bymail-unique")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].email", is("IT-REST-bymail-unique@example.com"));
    }

    @Test
    void searchIsCaseInsensitive() {
        // The /search endpoint delegates to searchContacts(), which lower-cases both the term and the
        // name/email columns, so name search is case-insensitive — preserved across the MySQL -> PostgreSQL
        // migration (the columns are lowered explicitly, since PostgreSQL's LIKE is case-sensitive),
        // asserted here as the observable HTTP contract.
        createContactAsAdmin("it-rest-case@example.com", "it-rest-CaseName", "en");

        // An upper-cased term still matches the mixed-case stored name.
        given().auth().oauth2(accessToken())
                .queryParam("name", "IT-REST-CASENAME")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].name", is("it-rest-CaseName"));

        // A lower-cased substring also matches.
        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-case")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].name", is("it-rest-CaseName"));
    }

    @Test
    void searchWithUnknownTermReturnsEmptyResult() {
        given().auth().oauth2(accessToken())
                .queryParam("name", "IT-REST-NoSuchTermXyz")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(0))
                .body("size", is(0))
                .body("data.size()", is(0));
    }

    @Test
    void searchHonoursMaxSizeButReportsFullTotal() {
        // Lower-case fixture data; the casing is immaterial since the service lower-cases both the
        // term and the columns (see potential-errors.md PG3). Pagination/total semantics are unchanged.
        createContactAsAdmin("it-rest-page-a@example.com", "it-rest-page", "en");
        createContactAsAdmin("it-rest-page-b@example.com", "it-rest-page", "en");
        createContactAsAdmin("it-rest-page-c@example.com", "it-rest-page", "en");

        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-page")
                .queryParam("maxSize", 2)
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(3))
                .body("size", is(2))
                .body("data.size()", is(2))
                // ordered by email ascending: first page holds the two lowest emails
                .body("data[0].email", is("it-rest-page-a@example.com"))
                .body("data[1].email", is("it-rest-page-b@example.com"));

        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-page")
                .queryParam("maxSize", 2)
                .queryParam("page", 1)
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(3))
                .body("data.size()", is(1))
                .body("data[0].email", is("it-rest-page-c@example.com"));
    }

    // ------------------------------------------------------------------------------------------- //
    // addContact — create round-trip & persistence side-effects
    // ------------------------------------------------------------------------------------------- //

    @Test
    void addContactAsAdminReturnsCreatedVoWithGeneratedId() {
        // Lower-case fixture data; the casing is immaterial for the search side-effect check below,
        // since the search lower-cases both the term and the columns (see potential-errors.md PG3).
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "email", "it-rest-create@example.com",
                        "name", "it-rest-create",
                        "language", "en"))
                .when().post("/rest/contacts/contact/")
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("email", is("it-rest-create@example.com"))
                .body("name", is("it-rest-create"))
                .body("language", is("en"))
                .extract().path("id");
        createdContactIds.add(id);

        // The created contact must be findable through the search endpoint (persistence side-effect).
        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-create@example.com")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].id", is(id));
    }

    @Test
    void addContactWithDuplicateEmailIsRejected() {
        // The duplicate check (Contact.findByEmail) compares lower(c.email) = lower(:email) — the
        // bind-value lowering was restored during the Postgres migration (see potential-errors.md
        // PG3) — so the email casing is immaterial; a lower-case fixture value is used for uniformity.
        createContactAsAdmin("it-rest-dup@example.com", "First", "en");

        // The service performs an explicit uniqueness check and throws IllegalArgumentException,
        // which surfaces as a 5xx server error rather than a 2xx success.
        int status = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("email", "it-rest-dup@example.com", "name", "Second", "language", "da"))
                .when().post("/rest/contacts/contact/")
                .then().extract().statusCode();
        assertFalse(status >= 200 && status < 300,
                "creating a duplicate-email contact must not succeed, but returned " + status);

        // The original must remain the only contact for that email.
        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-dup@example.com")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].name", is("First"));
    }

    // ------------------------------------------------------------------------------------------- //
    // updateContact — update round-trip & validation
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateContactAsAdminPersistsChanges() {
        // Lower-case fixture data; the casing is immaterial for the read-back via /search, since the
        // search lower-cases both the term and the columns (see potential-errors.md PG3).
        int id = createContactAsAdmin("it-rest-upd@example.com", "Original", "en");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id,
                        "email", "it-rest-upd-renamed@example.com",
                        "name", "it-rest-renamed",
                        "language", "da"))
                .when().put("/rest/contacts/contact/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("email", is("it-rest-upd-renamed@example.com"))
                .body("name", is("it-rest-renamed"))
                .body("language", is("da"));

        // Verify the change is persisted by reading it back through search.
        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-renamed")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].id", is(id))
                .body("data[0].email", is("it-rest-upd-renamed@example.com"));
    }

    @Test
    void updateContactWithMismatchedIdReturns400() {
        // Lower-case fixture data; the casing is immaterial for the "unchanged after rejected update"
        // read-back (the search lower-cases both sides, see potential-errors.md PG3), as is the 400
        // path-vs-body mismatch check.
        int id = createContactAsAdmin("it-rest-mismatch@example.com", "it-rest-mismatch-name", "en");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("id", id + 1, "email", "it-rest-mismatch@example.com"))
                .when().put("/rest/contacts/contact/" + id)
                .then().statusCode(400);

        // The original must be unchanged after the rejected update.
        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-mismatch-name")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].name", is("it-rest-mismatch-name"));
    }

    @Test
    void updateUnknownContactIsRejected() {
        // Updating a non-existent id (matching path & body) reaches the service, which throws
        // IllegalArgumentException -> server error (non-2xx), not a successful update.
        int status = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("id", -999_999, "email", "IT-REST-ghost@example.com"))
                .when().put("/rest/contacts/contact/-999999")
                .then().extract().statusCode();
        assertFalse(status >= 200 && status < 300,
                "updating an unknown contact must not succeed, but returned " + status);
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteContact — delete round-trip & persistence side-effects
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deleteContactAsAdminRemovesIt() {
        int id = createContactAsAdmin("IT-REST-del@example.com", "IT-REST-Delete", "en");

        // The endpoint returns void; a successful (2xx) status is asserted, and the meaningful
        // outcome — the contact is gone — is verified below via search.
        int status = given().auth().oauth2(accessToken())
                .when().delete("/rest/contacts/contact/" + id)
                .then().extract().statusCode();
        assertTrue(status >= 200 && status < 300, "delete must succeed, but returned " + status);
        // Already removed; drop from cleanup tracking to avoid a redundant delete.
        createdContactIds.remove(Integer.valueOf(id));

        // The deleted contact must no longer be returned by search (persistence side-effect).
        given().auth().oauth2(accessToken())
                .queryParam("name", "IT-REST-Delete")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(0))
                .body("data.size()", is(0));
    }

    @Test
    void deleteUnknownContactIsHandledGracefully() {
        // deleteContact returns false (no exception) for an unknown id; the void endpoint still
        // completes successfully without erroring.
        int status = given().auth().oauth2(accessToken())
                .when().delete("/rest/contacts/contact/-999999")
                .then().extract().statusCode();
        assertTrue(status >= 200 && status < 300,
                "deleting an unknown contact must complete successfully, but returned " + status);
    }

    // ------------------------------------------------------------------------------------------- //
    // export — @PermitAll but admin-checked programmatically
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exportAsAdminReturnsCsvLineForEachContact() {
        createContactAsAdmin("IT-REST-export@example.com", "IT-REST-Export", "da");

        String csv = given().auth().oauth2(accessToken())
                .when().get("/rest/contacts/export")
                .then().statusCode(200)
                .extract().asString();

        // The CSV encodes email;name;language per line (default separator is ';').
        assertTrue(csv.contains("IT-REST-export@example.com"),
                "export must contain the created contact's email");
        assertTrue(csv.contains("IT-REST-Export"),
                "export must contain the created contact's name");
    }

    @Test
    void exportHonoursCustomSeparator() {
        createContactAsAdmin("IT-REST-export-sep@example.com", "IT-REST-Sep", "en");

        String csv = given().auth().oauth2(accessToken())
                .queryParam("separator", ",")
                .when().get("/rest/contacts/export")
                .then().statusCode(200)
                .extract().asString();

        // encodeCVSLine quotes every field ("value") and joins them with the requested separator.
        // Asserting the comma-joined, quoted line proves the "separator" query param is honoured:
        // with the default ';' separator the fields would be joined as "...";"...";"..." instead.
        assertTrue(csv.contains("\"IT-REST-export-sep@example.com\",\"IT-REST-Sep\",\"en\""),
                "export must encode quoted fields with the requested comma separator; got: " + csv);
    }

    // ------------------------------------------------------------------------------------------- //
    // import-emails — bulk import with de-duplication and validation
    // ------------------------------------------------------------------------------------------- //

    @Test
    void importEmailsCreatesContactsForValidNewEmails() {
        // Import stores the address verbatim; the read-back/track via /search is case-insensitive (the
        // search lower-cases both the term and the columns, see potential-errors.md PG3), so the
        // lower-case import addresses are just a uniform fixture choice.
        String response = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("emails", "it-rest-imp-a@example.com, it-rest-imp-b@example.com, not-an-email"))
                .when().post("/rest/contacts/import-emails")
                .then().statusCode(200)
                .body(containsString("Imported 2 new contacts"))
                .extract().asString();
        assertTrue(response.contains("Imported 2"), "expected 2 imports, got: " + response);

        // Track the imported contacts for cleanup by resolving their generated ids via search.
        trackImported("it-rest-imp-a@example.com");
        trackImported("it-rest-imp-b@example.com");

        // Both emails must now be present as contacts.
        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-imp-")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(2))
                .body("data.email", hasItem("it-rest-imp-a@example.com"))
                .body("data.email", hasItem("it-rest-imp-b@example.com"));
    }

    @Test
    void importEmailsSkipsAlreadyExistingContacts() {
        // Pre-create one of the emails; importing it again together with a new one imports only the new.
        // The import's de-duplication uses Contact.findByEmail, which compares
        // lower(c.email) = lower(:email) (the bind-value lowering was restored during the Postgres
        // migration, see potential-errors.md PG3), so an existing contact is recognised regardless of
        // stored/bind casing; lower-case addresses are just a uniform fixture choice.
        createContactAsAdmin("it-rest-impdup@example.com", "Existing", "en");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("emails", "it-rest-impdup@example.com;it-rest-impnew@example.com"))
                .when().post("/rest/contacts/import-emails")
                .then().statusCode(200)
                .body(containsString("Imported 1 new contacts"));
        trackImported("it-rest-impnew@example.com");

        // The pre-existing email must still resolve to exactly one contact (no duplicate inserted).
        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-impdup@example.com")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1));
    }

    @Test
    void importEmailsGuessesNoLanguageFromTheAddress() {
        // The address's domain decides nothing: an imported contact gets no language, as one
        // created without one, whatever country its domain names (niord-base holds no country
        // data). Casing is immaterial for the read-back search (the service lower-cases both
        // sides, see potential-errors.md PG3).
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("emails", "it-rest-danish@example.dk"))
                .when().post("/rest/contacts/import-emails")
                .then().statusCode(200)
                .body(containsString("Imported 1 new contacts"));
        trackImported("it-rest-danish@example.dk");

        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-danish@example.dk")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].language", nullValue());
    }

    @Test
    void importEmailsWithOnlyInvalidAddressesImportsNothing() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("emails", "not-an-email, also-bad, @nope"))
                .when().post("/rest/contacts/import-emails")
                .then().statusCode(200)
                .body(containsString("Imported 0 new contacts"));

        // No matching contacts must have been created.
        given().auth().oauth2(accessToken())
                .queryParam("name", "not-an-email")
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Resolves the generated id of an imported contact via search and tracks it for cleanup. */
    private void trackImported(String email) {
        Integer id = given().auth().oauth2(accessToken())
                .queryParam("name", email)
                .when().get("/rest/contacts/search")
                .then().statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .extract().path("data[0].id");
        if (id != null) {
            createdContactIds.add(id);
        }
    }
}
