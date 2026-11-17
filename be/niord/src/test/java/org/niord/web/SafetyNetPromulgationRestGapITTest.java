/*
 * Copyright 2017 Danish Maritime Authority.
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

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link org.niord.core.promulgation.SafetyNetPromulgationRestService} (and the
 * adjacent {@link org.niord.core.promulgation.NavtexPromulgationRestService} reformat/transmitter
 * endpoints) covering the gaps left by {@code PromulgationRestServiceITTest} and
 * {@code SafetyNetPromulgationServiceITTest}: the HTTP contract of the {@code /rest/promulgation/safetynet}
 * and {@code /rest/promulgation/navtex} endpoints, exercised against a real Keycloak token
 * (see {@link KeycloakTestTokens}) and the seeded {@code navtex} promulgation type.
 *
 * <p>The {@code SafetyNetPromulgationRestService} class carries a class-level
 * {@code @RolesAllowed(SYSADMIN)}, so every endpoint requires a SYSADMIN token (a missing token
 * yields 401) <em>except</em> {@code /reformat-safetynet}, which is annotated
 * {@code @RolesAllowed(EDITOR)}: the message editor is its only caller. The same split holds for
 * {@code NavtexPromulgationRestService}: {@code /reformat-navtex} takes an editor, the transmitter CRUD
 * endpoints require SYSADMIN. The reformat calls below are made as the admin application makes them,
 * with an editor's token and the {@code NiordDomain} header.
 *
 * <p>Key behaviours asserted here:
 * <ul>
 *   <li>The {@code reformat-*} endpoints run the shared NAVTEX 40-character reformat pipeline:
 *       {@code TextUtils.maxLineLength(text,40)} (word-wrap at 40 chars) then collapse of consecutive
 *       newlines, uppercasing and trimming. Blank or absent text short-circuits and is returned
 *       unchanged (the {@code StringUtils.isNotBlank} guard).</li>
 *   <li>The area / transmitter create &amp; update endpoints validate that the path {@code typeId}
 *       (and, for update, the path {@code name}) match the request body, throwing
 *       {@code NiordWebException(400)} otherwise — this validation happens before any
 *       persistence, so it is observable without seeding rows.</li>
 *   <li>The SYSADMIN-protected area / transmitter endpoints reject anonymous callers with 401.</li>
 * </ul>
 *
 * <p>All assertions verify a meaningful outcome (exact HTTP status and, for the reformat endpoints,
 * the exact transformed body). The mismatch (400) and authorization (401) tests do not commit any
 * rows, and the reformat endpoint is a pure transformation, so no cleanup is required and the tests
 * are independent and order-free.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SafetyNetPromulgationRestGapITTest extends ITTest {

    /** The seeded promulgation type used as the {typeId} path segment. */
    private static final String NAVTEX = "navtex";

    /**
     * The reformat endpoints deserialize the request body into the polymorphic
     * {@link org.niord.core.promulgation.vo.BaseMessagePromulgationVo}, which is annotated
     * {@code @JsonTypeInfo(use=Id.CLASS, property="@class")}. Jackson therefore requires the
     * fully-qualified concrete class name to be supplied via the {@code @class} discriminator
     * property; without it deserialization fails and the endpoint returns 500. These constants
     * carry the two concrete VO class names used as discriminators.
     */
    private static final String SAFETYNET_CLASS =
            "org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo";
    private static final String NAVTEX_CLASS =
            "org.niord.core.promulgation.vo.NavtexMessagePromulgationVo";

    /** Builds a SafetyNET circular-area request body bound to the given promulgation typeId. */
    private static Map<String, Object> circularAreaBody(String typeId, String name) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("typeId", typeId);

        Map<String, Object> area = new LinkedHashMap<>();
        // The VO is polymorphic (@JsonTypeInfo property="type"); CIRCULAR selects the concrete subtype.
        area.put("type", "CIRCULAR");
        area.put("promulgationType", type);
        area.put("name", name);
        area.put("active", true);
        area.put("sortOrder", 50);
        area.put("center", "0 0");
        area.put("radius", "100");
        return area;
    }

    /** Builds a NAVTEX transmitter request body bound to the given promulgation typeId. */
    private static Map<String, Object> transmitterBody(String typeId, String name) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("typeId", typeId);

        Map<String, Object> transmitter = new LinkedHashMap<>();
        transmitter.put("promulgationType", type);
        transmitter.put("name", name);
        transmitter.put("active", true);
        return transmitter;
    }

    /**
     * Builds a reformat request body for a polymorphic promulgation VO. The {@code @class}
     * discriminator must come first so Jackson can pick the concrete subtype before binding the
     * remaining properties; a {@link LinkedHashMap} preserves that ordering. A {@code null} text
     * is intentionally <em>not</em> added (the absent-text case relies on the field being missing).
     */
    private static Map<String, Object> reformatBody(String voClass, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@class", voClass);
        if (text != null) {
            body.put("text", text);
        }
        return body;
    }

    /** A reformat request as the admin application's message editor sends it */
    private static RequestSpecification asEditor() {
        return given().auth().oauth2(accessToken("editor")).header("NiordDomain", "niord-nw");
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) PUT /promulgation/safetynet/reformat-safetynet  [@RolesAllowed(EDITOR)] — pure transformation
    // ------------------------------------------------------------------------------------------- //

    @Test
    void reformatSafetyNetWrapsAt40CharsAndUppercases() {
        // A 66-character single line must be word-wrapped to <= 40 chars per line and uppercased.
        // Pipeline: maxLineLength(text,40) -> collapse \n+ -> toUpperCase -> trim.
        String input = "the quick brown fox jumps over the lazy dog and then keeps running";
        String expected = "THE QUICK BROWN FOX JUMPS OVER THE LAZY\nDOG AND THEN KEEPS RUNNING";

        String text = asEditor()
                .contentType(ContentType.JSON)
                .body(reformatBody(SAFETYNET_CLASS, input))
                .when().put("/rest/promulgation/safetynet/reformat-safetynet")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().path("text");

        assertEquals(expected, text, "reformat must word-wrap at 40 chars and uppercase");
        for (String line : text.split("\n")) {
            assertTrue(line.length() <= 40,
                    "every reformatted line must be <= 40 chars, was " + line.length() + ": " + line);
        }
    }

    @Test
    void reformatSafetyNetCollapsesConsecutiveBlankLines() {
        // Consecutive newlines collapse to a single newline, and the text is uppercased.
        String text = asEditor()
                .contentType(ContentType.JSON)
                .body(reformatBody(SAFETYNET_CLASS, "line one\n\n\nline two"))
                .when().put("/rest/promulgation/safetynet/reformat-safetynet")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().path("text");

        assertEquals("LINE ONE\nLINE TWO", text, "consecutive blank lines must collapse to one newline");
    }

    @Test
    void reformatSafetyNetIsNotReachableAnonymously() {
        // @RolesAllowed(EDITOR): an anonymous caller is turned away before the text is reformatted.
        // It used to be @PermitAll and answered 200 with the reformatted text.
        given()
                .contentType(ContentType.JSON)
                .body(reformatBody(SAFETYNET_CLASS, "hello"))
                .when().put("/rest/promulgation/safetynet/reformat-safetynet")
                .then().statusCode(401);
    }

    @Test
    void reformatSafetyNetWithBlankTextReturnsItUnchanged() {
        // StringUtils.isNotBlank(...) is false for a whitespace-only string, so the reformat
        // block is skipped and the text is echoed back verbatim (NOT uppercased / trimmed).
        asEditor()
                .contentType(ContentType.JSON)
                .body(reformatBody(SAFETYNET_CLASS, "   "))
                .when().put("/rest/promulgation/safetynet/reformat-safetynet")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("text", is("   "));
    }

    @Test
    void reformatSafetyNetWithAbsentTextReturnsNullText() {
        // No 'text' field at all: text is null, the isNotBlank guard skips reformatting and the
        // null text serializes back as JSON null. We still supply the @class discriminator (so
        // Jackson can bind the polymorphic VO) plus a 'priority' field to prove a fully-formed
        // VO with no text round-trips unchanged.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@class", SAFETYNET_CLASS);
        body.put("priority", "SAFETY");

        asEditor()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/promulgation/safetynet/reformat-safetynet")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("text", is((Object) null))
                .body("priority", is("SAFETY"));
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) POST /promulgation/safetynet/areas/{typeId}/area  — body/path typeId mismatch -> 400
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createAreaWithMismatchedTypeIdIsBadRequest() {
        // Path typeId = navtex but the body's promulgationType.typeId = other -> NiordWebException(400).
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(circularAreaBody("other-type", "IT-SN-Gap-Mismatch"))
                .when().post("/rest/promulgation/safetynet/areas/" + NAVTEX + "/area/")
                .then().statusCode(400);
    }

    @Test
    void createAreaWithoutTokenIsUnauthorized() {
        // Class-level @RolesAllowed(SYSADMIN): anonymous POST is rejected with 401 (before the
        // 400 body-validation is even reached).
        given()
                .contentType(ContentType.JSON)
                .body(circularAreaBody(NAVTEX, "IT-SN-Gap-NoAuth"))
                .when().post("/rest/promulgation/safetynet/areas/" + NAVTEX + "/area/")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) PUT /promulgation/safetynet/areas/{typeId}/area/{name}  — body/path name mismatch -> 400
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateAreaWithMismatchedNameIsBadRequest() {
        // typeId matches the path, but the path name differs from the body name -> 400.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(circularAreaBody(NAVTEX, "IT-SN-Gap-BodyName"))
                .when().put("/rest/promulgation/safetynet/areas/" + NAVTEX + "/area/IT-SN-Gap-PathName")
                .then().statusCode(400);
    }

    @Test
    void updateAreaWithMismatchedTypeIdIsBadRequest() {
        // The typeId check runs first: a mismatched body typeId yields 400 even though the name matches.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(circularAreaBody("other-type", "IT-SN-Gap-Upd"))
                .when().put("/rest/promulgation/safetynet/areas/" + NAVTEX + "/area/IT-SN-Gap-Upd")
                .then().statusCode(400);
    }

    @Test
    void updateAreaWithoutTokenIsUnauthorized() {
        given()
                .contentType(ContentType.JSON)
                .body(circularAreaBody(NAVTEX, "IT-SN-Gap-Upd"))
                .when().put("/rest/promulgation/safetynet/areas/" + NAVTEX + "/area/IT-SN-Gap-Upd")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // (4) Authorization boundaries — SYSADMIN-only area endpoints reject anonymous callers (401)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAreasWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/promulgation/safetynet/areas/" + NAVTEX + "/all")
                .then().statusCode(401);
    }

    @Test
    void deleteAreaWithoutTokenIsUnauthorized() {
        given()
                .when().delete("/rest/promulgation/safetynet/areas/" + NAVTEX + "/area/IT-SN-Gap-Del")
                .then().statusCode(401);
    }

    @Test
    void getAreasAsSysadminReturnsOkArray() {
        // Positive authorization: a SYSADMIN token is admitted (200). The seed leaves the
        // SafetyNetArea table empty for navtex, so the JSON body is an empty array.
        String body = given().auth().oauth2(accessToken())
                .when().get("/rest/promulgation/safetynet/areas/" + NAVTEX + "/all")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().asString();

        assertEquals("[]", body.trim(), "seed defines no SafetyNET areas for navtex; expected empty array");
    }

    // ------------------------------------------------------------------------------------------- //
    // (5) PUT /promulgation/navtex/reformat-navtex  [@RolesAllowed(EDITOR)] — shared 40-char pipeline
    // ------------------------------------------------------------------------------------------- //

    @Test
    void reformatNavtexWrapsAt40CharsAndUppercases() {
        // Same shared pipeline as reformat-safetynet, for an editor.
        String input = "the quick brown fox jumps over the lazy dog and then keeps running";
        String expected = "THE QUICK BROWN FOX JUMPS OVER THE LAZY\nDOG AND THEN KEEPS RUNNING";

        String text = asEditor()
                .contentType(ContentType.JSON)
                .body(reformatBody(NAVTEX_CLASS, input))
                .when().put("/rest/promulgation/navtex/reformat-navtex")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().path("text");

        assertEquals(expected, text, "reformat-navtex must word-wrap at 40 chars and uppercase");
        for (String line : text.split("\n")) {
            assertTrue(line.length() <= 40,
                    "every reformatted NAVTEX line must be <= 40 chars, was " + line.length() + ": " + line);
        }
        // The original text was a single un-wrapped line; the reformat inserts a wrap newline.
        assertTrue(text.contains("\n"), "a 66-char line must be wrapped onto multiple lines");
        assertFalse(input.contains("\n"), "sanity: the input was a single line");
    }

    @Test
    void reformatNavtexWithBlankTextReturnsItUnchanged() {
        asEditor()
                .contentType(ContentType.JSON)
                .body(reformatBody(NAVTEX_CLASS, "   "))
                .when().put("/rest/promulgation/navtex/reformat-navtex")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("text", is("   "));
    }

    // ------------------------------------------------------------------------------------------- //
    // (6) NAVTEX transmitter create/update — body/path mismatch -> 400; anonymous -> 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createTransmitterWithMismatchedTypeIdIsBadRequest() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(transmitterBody("other-type", "IT-NX-Gap-Mismatch"))
                .when().post("/rest/promulgation/navtex/transmitters/" + NAVTEX + "/transmitter/")
                .then().statusCode(400);
    }

    @Test
    void createTransmitterWithoutTokenIsUnauthorized() {
        given()
                .contentType(ContentType.JSON)
                .body(transmitterBody(NAVTEX, "IT-NX-Gap-NoAuth"))
                .when().post("/rest/promulgation/navtex/transmitters/" + NAVTEX + "/transmitter/")
                .then().statusCode(401);
    }

    @Test
    void updateTransmitterWithMismatchedNameIsBadRequest() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(transmitterBody(NAVTEX, "IT-NX-Gap-BodyName"))
                .when().put("/rest/promulgation/navtex/transmitters/" + NAVTEX + "/transmitter/IT-NX-Gap-PathName")
                .then().statusCode(400);
    }

    @Test
    void updateTransmitterWithMismatchedTypeIdIsBadRequest() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(transmitterBody("other-type", "IT-NX-Gap-Upd"))
                .when().put("/rest/promulgation/navtex/transmitters/" + NAVTEX + "/transmitter/IT-NX-Gap-Upd")
                .then().statusCode(400);
    }

    @Test
    void updateTransmitterWithoutTokenIsUnauthorized() {
        given()
                .contentType(ContentType.JSON)
                .body(transmitterBody(NAVTEX, "IT-NX-Gap-Upd"))
                .when().put("/rest/promulgation/navtex/transmitters/" + NAVTEX + "/transmitter/IT-NX-Gap-Upd")
                .then().statusCode(401);
    }

    @Test
    void getTransmittersWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/promulgation/navtex/transmitters/" + NAVTEX + "/all")
                .then().statusCode(401);
    }

    @Test
    void getTransmittersAsSysadminReturnsOkArray() {
        // Positive authorization for the SYSADMIN-protected transmitter listing (200). The seed
        // defines no NAVTEX transmitters for navtex, so the JSON body is an empty array.
        String body = given().auth().oauth2(accessToken())
                .when().get("/rest/promulgation/navtex/transmitters/" + NAVTEX + "/all")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().asString();

        assertEquals("[]", body.trim(), "seed defines no NAVTEX transmitters for navtex; expected empty array");
        // Belt-and-braces: there is certainly no transmitter named after our IT- prefix.
        assertFalse(body.contains("IT-NX-Gap"), "listing must not contain any leaked IT- test rows");
        assertThatNotContains(body);
    }

    /** Guards against accidental dependence on a previously-created (committed) row. */
    private static void assertThatNotContains(String body) {
        org.hamcrest.MatcherAssert.assertThat(body, not(containsString("IT-SN-Gap")));
    }
}
