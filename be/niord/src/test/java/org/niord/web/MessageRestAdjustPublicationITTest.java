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

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for the message <em>editing utility</em> endpoints of {@link MessageRestService}:
 * {@code POST /rest/messages/adjust-editable-message}, {@code POST /rest/messages/extract-message-publication}
 * and {@code POST /rest/messages/update-message-publications}. These are pure value-object transformers
 * (no persistence) used by the message editor, all guarded by {@code @RolesAllowed(EDITOR)}.
 *
 * <p>This class is a sibling of {@link MessageRestServiceITTest}, which covers the message CRUD/status
 * matrix; here we pin down the JSON request/response contract of the three publication/editor helpers
 * that the sibling does not touch. It mirrors the sibling's conventions exactly: {@code @QuarkusTest}
 * with {@link NiordContainersResource}, {@code extends ITTest}, bearer tokens minted from the Keycloak
 * container via {@link KeycloakTestTokens}, and the {@code NiordDomain: niord-nw} header that the
 * domain augmentor needs to realize the full role chain. Like {@link PublicationRestServiceITTest}, an
 * authenticated request is built with a small {@link #as(String)} helper.
 *
 * <p><b>Behaviour under test.</b>
 * <ul>
 *   <li>{@code adjust-editable-message} (autoTitle=true): always prunes the round-trip-irrelevant fields
 *       {@code mainType}, {@code autoTitle}, {@code messageSeries}, {@code areas} and {@code categories}
 *       to {@code null} before returning the adjusted {@code SystemMessageVo} (see
 *       {@code MessageRestService.adjustEditableMessage}, the block that nulls these fields). The request
 *       supplies all five fields populated so the pruning is observable rather than vacuous.</li>
 *   <li>{@code extract-message-publication}: looks up the publication by id. An <em>unknown</em> id makes
 *       the handler return {@code null}, which JAX-RS serializes as {@code 204 No Content}. A seeded
 *       <em>ACTIVE</em> {@code EXTERNAL} publication (mainType {@code PUBLICATION}) — resolved at runtime
 *       via {@code /search?messagePublication=EXTERNAL} since the seed regenerates publication UUIDs —
 *       combined with a message whose {@code en} {@code publication} HTML carries an anchor tagged
 *       {@code publication=<that id>} yields a populated {@link
 *       org.niord.core.message.vo.MessagePublicationVo} whose embedded {@code publication.publicationId}
 *       echoes the seeded id.</li>
 *   <li>{@code update-message-publications}: an <em>unknown</em> publication id is a no-op; the handler
 *       returns the request {@code MessageVo} unchanged (same {@code id}).</li>
 *   <li>Authorization: {@code adjust-editable-message} rejects an anonymous caller with {@code 401}.</li>
 * </ul>
 *
 * <p>None of these endpoints write to the database, so no cleanup is required and the tests are fully
 * independent and repeatable; they assert only HTTP status codes and response-body fields and never
 * mutate seeded data.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class MessageRestAdjustPublicationITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String ADJUST  = "/rest/messages/adjust-editable-message";
    private static final String EXTRACT = "/rest/messages/extract-message-publication";
    private static final String UPDATE  = "/rest/messages/update-message-publications";
    private static final String SEARCH  = "/rest/publications/search";

    /**
     * The publicationId of a seeded EXTERNAL publication (status ACTIVE, mainType PUBLICATION). The
     * seed regenerates publication UUIDs on every rebuild, so a real EXTERNAL one is resolved at runtime
     * via the anonymous {@code /search?messagePublication=EXTERNAL} endpoint rather than hard-coded.
     * EXTERNAL is required so {@code PublicationUtils} inspects the message's (non-internal)
     * {@code publication} HTML.
     */
    private String seededPublicationId;

    @BeforeEach
    void resolveSeededExternalPublicationId() {
        seededPublicationId = given()
                .queryParam("messagePublication", "EXTERNAL")
                .when().get(SEARCH)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("data.size()", greaterThanOrEqualTo(1))
                .extract().path("data[0].publicationId");
    }

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /adjust-editable-message — @RolesAllowed(EDITOR). Prunes round-trip-irrelevant fields.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void adjustEditableMessage_autoTitlePrunesIrrelevantFields() {
        // A SystemMessageVo with all five prunable fields populated and the seeded dma-nw series, which
        // the editor-fields computation can resolve. With autoTitle=true the handler computes the title
        // lines and then nulls mainType/autoTitle/messageSeries/areas/categories before returning.
        // A non-null status (DRAFT) is required: adjustEditableMessage builds a core Message and runs
        // updateAutoMessageFields/onLoadSystemMessage, which dereference status.isPublic() and would NPE
        // (HTTP 500) on a null status.
        String body = "{"
                + "\"mainType\":\"NW\","
                + "\"type\":\"COASTAL_WARNING\","
                + "\"status\":\"DRAFT\","
                + "\"autoTitle\":true,"
                + "\"messageSeries\":{\"seriesId\":\"dma-nw\"},"
                + "\"areas\":[{\"id\":999999}],"
                + "\"categories\":[{\"id\":999999}],"
                + "\"parts\":[{\"type\":\"DETAILS\",\"descs\":[{\"lang\":\"en\",\"subject\":\"IT subject\"}]}],"
                + "\"descs\":[{\"lang\":\"en\",\"title\":\"IT title\"}]"
                + "}";

        as(accessToken("editor")).contentType(ContentType.JSON).body(body)
                .when().post(ADJUST)
                .then().statusCode(200).contentType(ContentType.JSON)
                // The five fields are explicitly nulled by adjustEditableMessage regardless of input.
                .body("mainType", nullValue())
                .body("autoTitle", nullValue())
                .body("messageSeries", nullValue())
                .body("areas", nullValue())
                .body("categories", nullValue());
    }

    @Test
    void adjustEditableMessage_withoutTokenIsUnauthorized() {
        // @RolesAllowed(EDITOR): an anonymous caller never reaches the handler -> container 401.
        given().contentType(ContentType.JSON).body("{}")
                .when().post(ADJUST)
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /extract-message-publication — @RolesAllowed(EDITOR). Unknown id -> 204; match -> 200 + VO.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void extractMessagePublication_unknownPublicationReturnsNoContent() {
        // findByPublicationId(unknown) is null, so the handler returns null -> JAX-RS 204 No Content.
        String message = "{\"descs\":[{\"lang\":\"en\",\"title\":\"IT title\",\"publication\":\"some text\"}]}";

        as(accessToken("editor")).contentType(ContentType.JSON).body(message)
                .when().post(EXTRACT + "?publicationId=IT-no-such-publication")
                .then().statusCode(204);
    }

    @Test
    void extractMessagePublication_seededPublicationReturnsPopulatedVo() {
        // The seeded ACTIVE publication is EXTERNAL, so PublicationUtils inspects the message's
        // (non-internal) 'publication' HTML. An anchor tagged publication=<seeded id> is matched and a
        // MessagePublicationVo carrying that publication is returned -> 200 with publication.publicationId.
        String message = "{\"descs\":[{"
                + "\"lang\":\"en\",\"title\":\"IT title\","
                + "\"publication\":\"<a href=\\\"http://example.org/other\\\" publication=\\\""
                + seededPublicationId + "\\\">www.danskehavnelods.dk</a>\""
                + "}]}";

        as(accessToken("editor")).contentType(ContentType.JSON).body(message)
                .when().post(EXTRACT + "?publicationId=" + seededPublicationId + "&lang=en")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("publication", notNullValue())
                .body("publication.publicationId", is(seededPublicationId));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /update-message-publications — @RolesAllowed(EDITOR). Unknown id -> body echoed unchanged.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateMessagePublications_unknownPublicationEchoesBodyUnchanged() {
        // findByPublicationId(unknown) is null, so the handler returns the supplied MessageVo unchanged;
        // the response id must equal the request id.
        String id = "IT-update-msgpub-" + System.nanoTime();
        String message = "{\"id\":\"" + id + "\","
                + "\"descs\":[{\"lang\":\"en\",\"title\":\"IT title\"}]}";

        as(accessToken("editor")).contentType(ContentType.JSON).body(message)
                .when().post(UPDATE + "?publicationId=IT-no-such-publication")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("id", is(id));
    }
}
