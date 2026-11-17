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
package org.niord.web;

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEB15: a message whose short id holds a slash (the almanac's {@code A/1 2026}, the annex's {@code B/1 2026}, the
 * firing areas' {@code FA/14 2026}) can be read by that id. Both frontends write the id into the address as one
 * encoded path segment, so the slash travels as {@code %2F}; Tomcat's connector refused that with 400 before any
 * handler ran, and Spring Security's firewall would have refused it behind Tomcat.
 *
 * <p>{@code EncodedSlashConfig} lets Tomcat pass {@code %2F} through and has the firewall accept it on the three
 * GET paths that take a message id as their last segment, and nowhere else. The Tomcat setting is connector-wide, so
 * this test also shows that the firewall still refuses {@code %2F} on every other surface (the ones
 * {@code NonControllerHttpSurfaceInventoryTest} lists among them, which the firewall runs ahead of), on the three
 * paths with another method or a further segment, and that a traversal spelled with {@code %2F} on an allowed path
 * reaches nothing but the message lookup. {@code %252F} stays refused everywhere.
 *
 * <p>Every request is sent with RestAssured's URL encoding off, so the path goes on the wire exactly as written here.
 * The message is the fixture's published annex notice {@code B/1 2026}, which no other test changes.
 */
class MessageShortIdWithSlashITTest extends ITTest {

    /** The fixture's published annex notice (series dma-nm-annex, short format "B/${number} ${year}") */
    private static final String ANNEX_UID = "5a3f1c70-0000-4000-8000-000000000033";
    private static final String ANNEX_SHORT_ID = "B/1 2026";
    private static final String ANNEX_ENCODED = "B%2F1%202026";

    /** A request whose path goes on the wire as written */
    private static RequestSpecification raw() {
        return given().urlEncodingEnabled(false);
    }

    // ------------------------------------------------------------------------------------------- //
    // The three read paths answer by a short id with a slash
    // ------------------------------------------------------------------------------------------- //

    @Test
    void thePublicApi_returnsAMessageByAShortIdWithAnEncodedSlash() {
        raw().when().get("/rest/public/v1/message/" + ANNEX_ENCODED + "?lang=en")
                .then().statusCode(200)
                .body("id", is(ANNEX_UID))
                .body("shortId", is(ANNEX_SHORT_ID));
    }

    @Test
    void theAdministrationRead_returnsAMessageByAShortIdWithAnEncodedSlash() {
        raw().auth().oauth2(accessToken("editor"))
                .when().get("/rest/messages/message/" + ANNEX_ENCODED + "?lang=en")
                .then().statusCode(200)
                .body("id", is(ANNEX_UID))
                .body("shortId", is(ANNEX_SHORT_ID));
    }

    @Test
    void theMessagePdf_isPrintedByAShortIdWithAnEncodedSlash() {
        byte[] body = raw().when().get("/rest/message-reports/message/" + ANNEX_ENCODED + ".pdf?lang=en")
                .then().statusCode(200)
                .contentType("application/pdf")
                .extract().asByteArray();
        assertTrue(new String(body, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    @Test
    void anEncodedSlashInLowerCase_isReadAsInUpperCase() {
        raw().when().get("/rest/public/v1/message/B%2f1%202026?lang=en")
                .then().statusCode(200)
                .body("id", is(ANNEX_UID));
        raw().auth().oauth2(accessToken("editor"))
                .when().get("/rest/messages/message/B%2f1%202026")
                .then().statusCode(200)
                .body("id", is(ANNEX_UID));
    }

    @Test
    void theSameMessageByItsUid_isStillRead() {
        raw().when().get("/rest/public/v1/message/" + ANNEX_UID + "?lang=en")
                .then().statusCode(200)
                .body("shortId", is(ANNEX_SHORT_ID));
    }

    // ------------------------------------------------------------------------------------------- //
    // A traversal spelled with %2F reaches the message lookup only
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aTraversalWithEncodedSlashesOnThePublicApi_isAMessageNotFound() {
        raw().when().get("/rest/public/v1/message/..%2F..%2Frest%2Fmessages%2Fsearch")
                .then().statusCode(404)
                .body(containsString("No message found with ID: ../../rest/messages/search"));
    }

    @Test
    void aTraversalWithEncodedSlashesOnTheAdministrationRead_findsNoMessage() {
        // The handler answers null for an id that names no message, which is 204 (NullBodyNoContentAdvice)
        raw().auth().oauth2(accessToken("editor"))
                .when().get("/rest/messages/message/..%2F..%2Frepo%2Ffile%2Fx")
                .then().statusCode(204);
    }

    @Test
    void aTraversalWithEncodedSlashesOnTheMessagePdf_isAMessageNotFound() {
        raw().when().get("/rest/message-reports/message/..%2F..%2Fmessages%2Fsearch.pdf?lang=en")
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // Everywhere else %2F is refused, as before
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anEncodedSlash_isRefusedOnEveryOtherSurface() {
        List<String> paths = List.of(
                // controllers
                "/rest/repo/file/a%2Fb.txt",
                "/rest/repo/file/messages%2F5%2Fa3",
                "/rest/areas/area%2F1",
                "/rest/messages/search%2Fx",
                "/rest/public/v1/messages%2Fx",
                "/rest/actuator%2Fhealth",
                // the WMS servlet at /wms/* (NonControllerHttpSurfaceInventoryTest)
                "/wms/a%2Fb",
                // the SECOM servlet at /api/secom/* where it is on, the dispatcher where it is off
                "/api/secom/v2/a%2Fb",
                // static files and the root, which AdminAppRedirectFilter and WebAppFallbackFilter look at
                "/assets/a%2Fb.css",
                "/index.html%2F",
                "/%2F",
                "/%2f");
        for (String path : paths) {
            assertEquals(400, raw().when().get(path).then().extract().statusCode(),
                    "GET " + path + " must be refused with 400");
        }
    }

    @Test
    void theWmsServletAnswersForItselfWithoutAnEncodedSlash() {
        // The control of /wms/a%2Fb above: without %2F the servlet itself answers (403 to an anonymous caller while
        // the WMS is protected), so its 400 comes from the firewall, ahead of the servlet
        raw().when().get("/wms/ab")
                .then().statusCode(not(equalTo(400)));
    }

    @Test
    void anEncodedSlashOnTheThreePaths_isRefusedForAnyOtherMethod() {
        String token = accessToken("editor");
        for (String method : List.of("POST", "PUT", "DELETE", "PATCH")) {
            for (String path : List.of(
                    "/rest/public/v1/message/" + ANNEX_ENCODED,
                    "/rest/messages/message/" + ANNEX_ENCODED,
                    "/rest/message-reports/message/" + ANNEX_ENCODED + ".pdf")) {
                assertEquals(400,
                        raw().auth().oauth2(token).request(method, path).then().extract().statusCode(),
                        method + " " + path + " must be refused with 400");
            }
        }
    }

    @Test
    void anEncodedSlashOnTheThreePaths_isRefusedWithAFurtherSegmentOrADoubleSlash() {
        List<String> paths = List.of(
                "/rest/public/v1/message/" + ANNEX_ENCODED + "/x",
                "/rest/messages/message/" + ANNEX_ENCODED + "/x",
                "/rest/message-reports/message/" + ANNEX_ENCODED + ".pdf/x",
                "/rest/message-reports/message/" + ANNEX_ENCODED,
                "/rest/public/v1/message/B%2F%2F1%202026",
                "/rest/messages/message/B%2f%2F1%202026");
        String token = accessToken("editor");
        for (String path : paths) {
            assertEquals(400, raw().auth().oauth2(token).when().get(path).then().extract().statusCode(),
                    "GET " + path + " must be refused with 400");
        }
    }

    @Test
    void aDoublyEncodedSlash_isRefusedEverywhere() {
        List<String> paths = List.of(
                "/rest/public/v1/message/B%252F1%202026",
                "/rest/messages/message/B%252F1%202026",
                "/rest/message-reports/message/B%252F1%202026.pdf",
                "/rest/repo/file/a%252Fb.txt",
                "/wms/a%252Fb");
        String token = accessToken("editor");
        for (String path : paths) {
            assertEquals(400, raw().auth().oauth2(token).when().get(path).then().extract().statusCode(),
                    "GET " + path + " must be refused with 400");
        }
    }
}
