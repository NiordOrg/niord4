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
package org.niord.core.promulgation;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for the HTTP contract of the three promulgation sub-REST services:
 * {@link NavtexPromulgationRestService}, {@link SafetyNetPromulgationRestService} and
 * {@link TwitterSettingsRestService}.
 *
 * <p>Each of those resources is declared {@code @RolesAllowed(SYSADMIN)} at the class level, but
 * the {@code reformat-*} methods override that with a method-level {@code @RolesAllowed(EDITOR)}: the
 * message editor is their only caller, as it is of generating a promulgation. These tests verify:
 *
 * <ul>
 *   <li>the reformat endpoints take an editor (as the admin application sends it, with its
 *       {@code NiordDomain} header), apply the documented uppercase/trim transformation, reject an
 *       anonymous caller (401) and a caller without the editor role (403), and refuse a text longer
 *       than {@link NavtexPromulgationRestService#MAX_REFORMAT_TEXT_LENGTH} characters (400); and</li>
 *   <li>the remaining endpoints honour the inherited class-level {@code SYSADMIN} requirement —
 *       rejecting requests with no token (401) and accepting a sysadmin token (200).</li>
 * </ul>
 *
 * <p>The reformat request body is a {@code BaseMessagePromulgationVo} subtype, which Jackson
 * serializes/deserializes with a polymorphic {@code @class} discriminator (see
 * {@code BaseMessagePromulgationVo}), so the JSON body is sent as a raw string that includes the
 * {@code @class} property.
 *
 * <p>The GET endpoints resolve against the seeded {@code navtex} promulgation type; the
 * {@code NavtexTransmitter}, {@code SafetyNetArea} and {@code TwitterSettings} tables are seeded
 * empty, so the collection endpoints return empty arrays and the Twitter settings endpoint returns
 * an empty body.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PromulgationFormatRestServiceITTest extends ITTest {

    private static final String NAVTEX_REFORMAT = "/rest/promulgation/navtex/reformat-navtex";
    private static final String SAFETYNET_REFORMAT = "/rest/promulgation/safetynet/reformat-safetynet";

    private static String navtexBody(String text) {
        return "{\"@class\":\"org.niord.core.promulgation.vo.NavtexMessagePromulgationVo\","
                + "\"text\":\"" + text + "\"}";
    }

    private static String safetyNetBody(String text) {
        return "{\"@class\":\"org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo\","
                + "\"text\":\"" + text + "\"}";
    }

    /** A request as the admin application's message editor sends it: an editor's token and its domain */
    private static RequestSpecification asEditor() {
        return given().auth().oauth2(accessToken("editor")).header("NiordDomain", "niord-nw");
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) NAVTEX reformat — @RolesAllowed(EDITOR)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void reformatNavtexAsEditorUppercasesAndTrimsShortText() {
        // "test message" is < 40 chars, so it is uppercased/trimmed only, with no line wrapping.
        asEditor()
                .contentType(ContentType.JSON)
                .body(navtexBody("test message"))
                .when().put(NAVTEX_REFORMAT)
                .then().statusCode(200)
                .body("text", is("TEST MESSAGE"));
    }

    @Test
    void reformatNavtexAsEditorLeavesBlankTextUnchanged() {
        // Blank text short-circuits the transformation, so the text is returned unchanged ("").
        asEditor()
                .contentType(ContentType.JSON)
                .body(navtexBody(""))
                .when().put(NAVTEX_REFORMAT)
                .then().statusCode(200)
                .body("text", is(""));
    }

    @Test
    void reformatNavtexWithoutTokenIsUnauthorized() {
        given()
                .contentType(ContentType.JSON)
                .body(navtexBody("test message"))
                .when().put(NAVTEX_REFORMAT)
                .then().statusCode(401);
    }

    @Test
    void reformatNavtexAsUserIsForbidden() {
        // testuser holds the user role in niord-nw, and no editor role anywhere
        given().auth().oauth2(accessToken("user")).header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(navtexBody("test message"))
                .when().put(NAVTEX_REFORMAT)
                .then().statusCode(403);
    }

    @Test
    void reformatNavtexRefusesATextLongerThanTheCap() {
        asEditor()
                .contentType(ContentType.JSON)
                .body(navtexBody("x".repeat(NavtexPromulgationRestService.MAX_REFORMAT_TEXT_LENGTH + 1)))
                .when().put(NAVTEX_REFORMAT)
                .then().statusCode(400);
    }

    @Test
    void reformatNavtexAcceptsATextAtTheCap() {
        // An unbroken word at the cap is cut into 40-character lines: 20 000 / 40 = 500 of them
        String text = asEditor()
                .contentType(ContentType.JSON)
                .body(navtexBody("x".repeat(NavtexPromulgationRestService.MAX_REFORMAT_TEXT_LENGTH)))
                .when().put(NAVTEX_REFORMAT)
                .then().statusCode(200)
                .extract().path("text");

        assertEquals(("X".repeat(40) + "\n").repeat(500).trim(), text);
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) SafetyNET reformat — @RolesAllowed(EDITOR)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void reformatSafetyNetAsEditorUppercasesAndTrimsShortText() {
        asEditor()
                .contentType(ContentType.JSON)
                .body(safetyNetBody("test message"))
                .when().put(SAFETYNET_REFORMAT)
                .then().statusCode(200)
                .body("text", is("TEST MESSAGE"));
    }

    @Test
    void reformatSafetyNetWithoutTokenIsUnauthorized() {
        given()
                .contentType(ContentType.JSON)
                .body(safetyNetBody("test message"))
                .when().put(SAFETYNET_REFORMAT)
                .then().statusCode(401);
    }

    @Test
    void reformatSafetyNetAsUserIsForbidden() {
        given().auth().oauth2(accessToken("user")).header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(safetyNetBody("test message"))
                .when().put(SAFETYNET_REFORMAT)
                .then().statusCode(403);
    }

    @Test
    void reformatSafetyNetRefusesATextLongerThanTheCap() {
        asEditor()
                .contentType(ContentType.JSON)
                .body(safetyNetBody("x".repeat(NavtexPromulgationRestService.MAX_REFORMAT_TEXT_LENGTH + 1)))
                .when().put(SAFETYNET_REFORMAT)
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) Auth boundaries — endpoints inherit the class-level @RolesAllowed(SYSADMIN)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getNavtexTransmittersWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/promulgation/navtex/transmitters/navtex/all")
                .then().statusCode(401);
    }

    @Test
    void getNavtexTransmittersAsSysadminReturnsEmptyArray() {
        // No NavtexTransmitter rows are seeded, so the SYSADMIN-protected endpoint returns [].
        given().auth().oauth2(accessToken())
                .when().get("/rest/promulgation/navtex/transmitters/navtex/all")
                .then().statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void getSafetyNetAreasWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/promulgation/safetynet/areas/navtex/all")
                .then().statusCode(401);
    }

    @Test
    void getSafetyNetAreasAsSysadminReturnsEmptyArray() {
        // No SafetyNetArea rows are seeded, so the SYSADMIN-protected endpoint returns [].
        given().auth().oauth2(accessToken())
                .when().get("/rest/promulgation/safetynet/areas/navtex/all")
                .then().statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void getTwitterSettingsWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/promulgation/twitter-settings/navtex")
                .then().statusCode(401);
    }

    @Test
    void getTwitterSettingsAsSysadminWithNoSettingsReturnsNoContent() {
        // No TwitterSettings row exists for 'navtex', so getSettings() returns null and the
        // endpoint returns a null entity -> RESTEasy maps that to 204 No Content (not 200).
        // The meaningful assertions: the SYSADMIN endpoint is reachable for a sysadmin (not 401/403)
        // and the "no settings configured" case is signalled as an empty 204 response.
        given().auth().oauth2(accessToken())
                .when().get("/rest/promulgation/twitter-settings/navtex")
                .then().statusCode(204);
    }
}
