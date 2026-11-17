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

import org.niord.test.Tx;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Positive / functional integration tests for {@link TemplateExecutionRestService}, complementing the
 * authorization-only {@link TemplateExecutionRestServiceITTest}. These exercise the actual handler logic
 * of {@code PUT /rest/templates/execute} ({@code @RolesAllowed(USER)}) and of the SYSADMIN-only
 * parameter-type write endpoints ({@code POST}/{@code GET}/{@code PUT}/{@code DELETE
 * /rest/templates/parameter-type/...}).
 *
 * <p><b>Execute oracle.</b> {@code applyTemplate} first rejects a body for which
 * {@code ExecuteTemplateVo.valid()} is false (neither {@code message} nor {@code messageId} present) with a
 * {@code 400}, and a body whose template parameters contain a markup tag with a {@code 400}. For a valid body
 * carrying an inline message and an inline TEMPLATE category sent by a sysadmin,
 * {@code TemplateExecutionService.executeTemplate} auto-creates exactly one {@code DETAILS} message part
 * (see {@code checkCreateDetailParts}) and — because there is a single {@code DETAILS} part — sets
 * {@code hideSubject=true} in {@code postExecuteTemplate}. So the returned {@code SystemMessageVo} has one
 * part of type {@code DETAILS} with {@code hideSubject=true}. An inline category definition names the
 * script resources to run, so for anyone but a sysadmin it counts only for its id (the stored category
 * runs), and without an id it is refused with {@code 403}. An id that names no stored category, inline or
 * as {@code categoryId}, is answered {@code 404}.
 *
 * <p><b>Authorization oracle.</b> {@code /execute} carries {@code @RolesAllowed(USER)}; the {@code user}
 * role is only ever realized through the {@code NiordDomain: niord-nw} augmentor (see
 * {@link KeycloakTestTokens}). An authenticated {@code testuser} <em>with</em> that header reaches the
 * handler; <em>without</em> it the identity is role-less and is rejected with {@code 403}; an anonymous
 * caller is rejected by the container with {@code 401} before the handler runs.
 *
 * <p><b>Parameter-type oracle.</b> A {@code "STANDARD"} parameter type cannot even be deserialized into a
 * VO (the {@code @JsonSubTypes} on {@code ParamTypeVo} maps the name to the entity {@code StandardParamType},
 * not to {@code StandardParamTypeVo} — see BUG TMPL2), so the request entity never binds and is rejected
 * before the handler runs: under the Spring Boot / Jersey + Jackson stack a malformed request entity is a
 * {@code 400} (it was a {@code 500} under RESTEasy — a framework-specific detail that changed in the port).
 * {@code updateParamType} rejects a path-id/body-id mismatch with {@code 400} before persisting anything.
 * A {@code "LIST"} type with empty {@code values} round-trips: create returns the persisted VO with an
 * assigned id, GET returns it by id, and DELETE removes it (the void endpoint answers {@code 204}).
 *
 * <p><b>Shared-DB hygiene.</b> The MySQL container is shared across the whole test run and other tests
 * (e.g. {@code ScriptResourceServiceITTest}) assert there are zero {@code ScriptResource} rows. The
 * execute happy-path therefore deliberately uses a category with an <em>empty</em>
 * {@code scriptResourcePaths} list and references no {@code .ftl}/{@code .js} resource, so no
 * {@code ScriptResource} is ever persisted. Parameter-type rows created here use {@code IT-rest*} names and
 * are removed in {@link #cleanup()} via a committing {@code Tx.requiringNew} delete; only
 * empty-values {@code LIST} types are created, so there are no join-table FK rows to untangle.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class TemplateExecutionRestServicePositiveITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String EXECUTE        = "/rest/templates/execute";
    private static final String PARAM_TYPE     = "/rest/templates/parameter-type/";
    private static final String PARAM_TYPE_ID  = "/rest/templates/parameter-type/{id}";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    @PersistenceContext
    EntityManager em;

    /**
     * Removes any parameter-type rows this suite created. Only empty-values {@code LIST} (and the rejected
     * {@code STANDARD}/{@code COMPOSITE}) types are ever attempted here, so there are no value join-table
     * FK rows; a plain {@code delete from ParamType} by name suffices. Runs in its own committing
     * transaction because the rows were committed by the server's request transaction.
     */
    @AfterEach
    void cleanup() {
        try {
            Tx.requiringNew().run(() ->
                    em.createQuery("delete from ParamType where name like 'IT-rest%'").executeUpdate());
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /execute — @RolesAllowed(USER); an inline category definition only for SYSADMIN
    // ------------------------------------------------------------------------------------------- //

    /** An inline TEMPLATE category with an EMPTY scriptResourcePaths list, with the given id or none */
    private static Map<String, Object> inlineTemplateCategory(Integer id) {
        Map<String, Object> category = new HashMap<>();
        if (id != null) {
            category.put("id", id);
        }
        category.put("type", "TEMPLATE");
        category.put("scriptResourcePaths", List.of());
        category.put("descs", List.of(Map.of("lang", "en", "name", "IT-rest-tmpl")));
        return category;
    }

    /** An inline draft message with no categories of its own */
    private static Map<String, Object> inlineMessage() {
        return Map.of(
                "mainType", "NW",
                "type", "LOCAL_WARNING",
                "status", "DRAFT",
                "autoTitle", false,
                "categories", List.of());
    }

    @Test
    void execute_asSysadminWithInlineEmptyTemplateCategoryCreatesDetailPart() {
        // The category editor's "Test" action sends the unsaved category; only a sysadmin has it run as
        // sent. EMPTY scriptResourcePaths, so no ScriptResource is persisted (keeps the shared seed DB
        // clean). With a single DETAILS part, hideSubject becomes true.
        Map<String, Object> body = Map.of(
                "category", inlineTemplateCategory(null),
                "message", inlineMessage());

        as(accessToken()).contentType(ContentType.JSON).body(body)
                .when().put(EXECUTE)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("parts.size()", is(1))
                .body("parts[0].type", is("DETAILS"))
                .body("parts[0].hideSubject", is(true));
    }

    @Test
    void execute_asUserWithAnUnsavedInlineCategoryIsForbidden() {
        // A category definition without an id names script resources nobody stored: sysadmin only.
        Map<String, Object> body = Map.of(
                "category", inlineTemplateCategory(null),
                "message", inlineMessage());

        as(accessToken("user")).contentType(ContentType.JSON).body(body)
                .when().put(EXECUTE)
                .then().statusCode(403);
    }

    @Test
    void execute_asUserLooksUpTheInlineCategoryByIdInsteadOfRunningIt() {
        // For anyone but a sysadmin the inline category counts only for its id. No category is stored under
        // this id, so the request is answered 404 instead of running the inline definition (which, as in
        // the sysadmin case above, would have answered 200 with a DETAILS part).
        Map<String, Object> body = Map.of(
                "category", inlineTemplateCategory(999_999_999),
                "message", inlineMessage());

        as(accessToken("user")).contentType(ContentType.JSON).body(body)
                .when().put(EXECUTE)
                .then().statusCode(404);
    }

    @Test
    void execute_withACategoryIdThatNamesNoCategoryIsNotFound() {
        // An id that names no stored category is reported, rather than quietly running the message's own
        // template categories instead.
        Map<String, Object> body = Map.of(
                "categoryId", 999_999_999,
                "message", inlineMessage());

        as(accessToken("user")).contentType(ContentType.JSON).body(body)
                .when().put(EXECUTE)
                .then().statusCode(404);
    }

    @Test
    void execute_templateParamsWithMarkupAreRejected() {
        // Parameters are printed unescaped into the templates' field templates, some of them HTML (F-X139)
        Map<String, Object> body = Map.of(
                "message", inlineMessage(),
                "templateParams", List.of(Map.of(
                        "wreck_type", Map.of("key", "x", "descs", List.of(Map.of("lang", "en", "value", "<b>x</b>"))))));

        as(accessToken("user")).contentType(ContentType.JSON).body(body)
                .when().put(EXECUTE)
                .then().statusCode(400);
    }

    @Test
    void execute_templateParamsWithPlainTextAreAccepted() {
        Map<String, Object> body = Map.of(
                "message", inlineMessage(),
                "templateParams", List.of(Map.of("name", "Smith & Co, æøå, depth < 2 m", "wreck_depth", 12)));

        as(accessToken("user")).contentType(ContentType.JSON).body(body)
                .when().put(EXECUTE)
                .then().statusCode(200);
    }

    @Test
    void execute_asUserWithoutNiordDomainHeaderForbidden() {
        // No NiordDomain header => the augmentor never grants the USER role => 403 (authenticated, wrong role).
        Map<String, Object> body = Map.of(
                "message", Map.of("mainType", "NW", "type", "LOCAL_WARNING", "status", "DRAFT", "autoTitle", false));

        given().auth().oauth2(accessToken("user"))
                .contentType(ContentType.JSON).body(body)
                .when().put(EXECUTE)
                .then().statusCode(403);
    }

    @Test
    void execute_anonymousRejectedWith401() {
        // The container rejects an unauthenticated caller before the handler runs.
        given().contentType(ContentType.JSON).body(Map.of())
                .when().put(EXECUTE)
                .then().statusCode(401);
    }

    @Test
    void execute_authenticatedUserWithInvalidBodyReturns400() {
        // Authz passes (USER via NiordDomain), but ExecuteTemplateVo.valid() is false (no message/messageId)
        // => NiordWebException 400 from the handler.
        as(accessToken("user")).contentType(ContentType.JSON).body(Map.of())
                .when().put(EXECUTE)
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // Parameter-type writes — @RolesAllowed(SYSADMIN)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createParamType_asSysadminPersistsListTypeThenReadableThenDeletable() {
        // Create a LIST type with no values — round-trips with no join-table FK rows.
        Integer id = as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("type", "LIST", "name", "IT-rest-list", "values", List.of()))
                .when().post(PARAM_TYPE)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("type", is("LIST"))
                .body("name", is("IT-rest-list"))
                .body("id", notNullValue())
                .extract().path("id");

        // GET it back by id.
        as(accessToken())
                .when().get(PARAM_TYPE_ID, id)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("name", is("IT-rest-list"));

        // DELETE returns void => 204 No Content (accept 200 defensively).
        as(accessToken())
                .when().delete(PARAM_TYPE_ID, id)
                .then().statusCode(anyOf(is(204), is(200)));
    }

    @Test
    void createParamType_standardTypeRejected_documentsTmpl2() {
        // BUG TMPL2 (see docs/potential-errors.md): @JsonSubTypes on ParamTypeVo maps the "STANDARD"
        // name to the ENTITY class StandardParamType (not StandardParamTypeVo), which is not a subtype
        // of ParamTypeVo. So a POST of a STANDARD parameter type cannot be deserialized (Jackson
        // InvalidTypeIdException) — the body never binds to a ParamTypeVo, and the handler's own
        // `instanceof StandardParamTypeVo` 400-guard is dead code, unreachable because deserialization
        // fails first. The standard parameter types thus remain un-creatable via REST (by accident
        // rather than by design); this pins that behavior.
        //
        // The *status* this surfaces as is framework-specific and legitimately changed in the
        // Quarkus->Spring port: under RESTEasy a request-entity deserialization failure became a 500,
        // whereas under the Spring Boot / Jersey + Jackson stack a body that Jackson cannot bind is a
        // malformed request entity and is rejected with 400 (the more correct HTTP semantics; see
        // JsrAuthorizationRequestFilter's javadoc, which notes Jackson rejects a malformed JSON body
        // with 400 before the handler runs). Either way the STANDARD type cannot be created.
        as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("type", "STANDARD", "name", "IT-rest-std"))
                .when().post(PARAM_TYPE)
                .then().statusCode(400);
    }

    @Test
    void updateParamType_idMismatchReturns400() {
        // Path id (123456) != body id (999999) => 400 before any persistence.
        as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("type", "LIST", "id", 999999, "name", "IT-rest-x", "values", List.of()))
                .when().put(PARAM_TYPE_ID, 123456)
                .then().statusCode(400);
    }
}
