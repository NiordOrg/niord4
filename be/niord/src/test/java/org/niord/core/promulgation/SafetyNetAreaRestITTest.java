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
package org.niord.core.promulgation;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * The SafetyNET areas of a promulgation type through {@link SafetyNetPromulgationRestService}, with
 * areas in the list: listed, added, updated and deleted as the Areas dialog does it (PROM6).
 *
 * <p>An area's answer carries its promulgation type with the type's domains, a lazy collection; the
 * handlers used to build it outside a transaction and answered 500 with a
 * {@code LazyInitializationException} as soon as the type had an area. Each test therefore makes its
 * own SafetyNET type in a domain, so the domains are read, and its own areas, through the REST
 * interface the application uses; the {@code @AfterEach} deletes them the same way.
 */
class SafetyNetAreaRestITTest extends ITTest {

    private static final String BASE = "/rest/promulgation/safetynet/areas/";
    private static final String DOMAIN = "niord-nw";

    private String typeId;
    private String circleName;
    private String navareaName;

    @BeforeEach
    void createType() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        typeId = "it-safetynet-" + suffix;
        circleName = "IT-SN-circle-" + suffix;
        navareaName = "IT-SN-navarea-" + suffix;

        Map<String, Object> type = new LinkedHashMap<>();
        type.put("typeId", typeId);
        type.put("serviceId", "safetynet");
        type.put("name", "IT SafetyNET " + suffix);
        type.put("active", false);
        type.put("requirement", "OPTIONAL");
        type.put("priority", 900);
        type.put("language", "en");
        type.put("domains", List.of(Map.of("domainId", DOMAIN)));

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(type)
                .when().post("/rest/promulgations/promulgation-type/")
                .then().statusCode(200);
    }

    @AfterEach
    void deleteAreasAndType() {
        for (String name : List.of(circleName, navareaName)) {
            given().auth().oauth2(accessToken())
                    .when().delete(BASE + typeId + "/area/" + name);
        }
        given().auth().oauth2(accessToken())
                .when().delete("/rest/promulgations/promulgation-type/" + typeId)
                .then().statusCode(204);
    }

    private Map<String, Object> circle(int sortOrder, String prefix) {
        Map<String, Object> area = new LinkedHashMap<>();
        area.put("type", "CIRCULAR");
        area.put("promulgationType", Map.of("typeId", typeId));
        area.put("name", circleName);
        area.put("active", true);
        area.put("sortOrder", sortOrder);
        area.put("repetition", "REPETITION_01");
        area.put("messageAreaPrefix", prefix);
        area.put("center", "57 10");
        area.put("radius", "25");
        return area;
    }

    private Map<String, Object> navarea(int sortOrder) {
        Map<String, Object> area = new LinkedHashMap<>();
        area.put("type", "NAVAREA");
        area.put("promulgationType", Map.of("typeId", typeId));
        area.put("name", navareaName);
        area.put("active", true);
        area.put("sortOrder", sortOrder);
        area.put("repetition", "REPETITION_11");
        area.put("navarea", "I");
        return area;
    }

    private Response post(Map<String, Object> area) {
        return given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(area)
                .when().post(BASE + typeId + "/area/");
    }

    private Response list() {
        return given().auth().oauth2(accessToken())
                .when().get(BASE + typeId + "/all");
    }

    @Test
    void listAnswersTheAreasInSortOrderWithTheirTypeAndItsDomains() {
        post(circle(2, "SKAGERRAK")).then().statusCode(200);
        post(navarea(1)).then().statusCode(200);

        list().then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("name", contains(navareaName, circleName))
                .body("type", contains("NAVAREA", "CIRCULAR"))
                .body("[1].center", is("57 10"))
                .body("[1].radius", is("25"))
                .body("[1].messageAreaPrefix", is("SKAGERRAK"))
                .body("[0].promulgationType.typeId", is(typeId))
                .body("[0].promulgationType.domains.domainId", contains(DOMAIN));
    }

    @Test
    void addAnswersTheStoredAreaAndStoresItOnce() {
        post(circle(5, "SKAGERRAK")).then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("name", is(circleName))
                .body("type", is("CIRCULAR"))
                .body("sortOrder", is(5))
                .body("promulgationType.typeId", is(typeId))
                .body("promulgationType.domains.domainId", contains(DOMAIN));

        list().then().statusCode(200).body("name", contains(circleName));
    }

    @Test
    void updateAnswersTheChangedAreaAndStoresTheChange() {
        post(circle(5, "SKAGERRAK")).then().statusCode(200);

        Map<String, Object> changed = circle(7, "KATTEGAT");
        changed.put("active", false);
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(changed)
                .when().put(BASE + typeId + "/area/" + circleName)
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("name", is(circleName))
                .body("active", is(false))
                .body("sortOrder", is(7))
                .body("messageAreaPrefix", is("KATTEGAT"))
                .body("promulgationType.domains.domainId", contains(DOMAIN));

        list().then().statusCode(200)
                .body("name", contains(circleName))
                .body("[0].active", is(false))
                .body("[0].sortOrder", is(7))
                .body("[0].messageAreaPrefix", is("KATTEGAT"));
    }

    @Test
    void deleteRemovesTheAreaFromTheList() {
        post(circle(5, "SKAGERRAK")).then().statusCode(200);
        post(navarea(1)).then().statusCode(200);

        given().auth().oauth2(accessToken())
                .when().delete(BASE + typeId + "/area/" + circleName)
                .then().statusCode(204);

        list().then().statusCode(200).body("name", contains(navareaName));

        given().auth().oauth2(accessToken())
                .when().delete(BASE + typeId + "/area/" + navareaName)
                .then().statusCode(204);

        list().then().statusCode(200).body("$", empty());
    }
}
