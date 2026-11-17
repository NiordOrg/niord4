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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Reads by an id or a name that names nothing are client errors (WEB16, and the first half of PROM12): they answer
 * 404 with a detail that names what was asked for, where they used to dereference the lookup's null and end in a
 * {@code NullPointerException} and a 500. The same reads with an id that exists still answer 200.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class UnknownIdReadsITTest extends RoundTripSupport {

    /** An id no source or parameter type has */
    private static final int UNKNOWN = 987654321;

    // ------------------------------------------------------------------------------------------- //
    // Dictionaries
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theEntriesOfAnUnknownDictionaryAnswer404() {
        String name = "it-no-such-dictionary-" + unique();
        for (String query : List.of("", "?lang=en")) {
            as("sysadmin").when().get("/rest/dictionaries/dictionary/" + name + "/entries" + query)
                    .then().statusCode(404).body("detail", containsString(name));
        }
        // The right path: the seeded web dictionary
        assertFalse(getList("sysadmin", "/rest/dictionaries/dictionary/web/entries").isEmpty());
        assertFalse(getList("sysadmin", "/rest/dictionaries/dictionary/web/entries?lang=en").isEmpty());
    }

    // ------------------------------------------------------------------------------------------- //
    // Publications
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUnknownEditablePublicationAnswers404() {
        String id = "it-no-such-publication-" + unique();
        as("admin").when().get("/rest/publications/editable-publication/" + id)
                .then().statusCode(404).body("detail", containsString(id));
        // The right path: a seeded publication
        String seeded = strings("SELECT \"publicationId\" FROM public.\"Publication\" ORDER BY id LIMIT 1").getFirst();
        as("admin").when().get("/rest/publications/editable-publication/" + seeded)
                .then().statusCode(200).body("publicationId", is(seeded));
    }

    // ------------------------------------------------------------------------------------------- //
    // Sources
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUnknownSourceAnswers404() {
        as("admin").when().get("/rest/sources/source/" + UNKNOWN)
                .then().statusCode(404).body("detail", containsString(String.valueOf(UNKNOWN)));
        // The right path: a seeded source
        int seeded = ints("SELECT id FROM public.\"Source\" ORDER BY id LIMIT 1").getFirst();
        as("admin").when().get("/rest/sources/source/" + seeded)
                .then().statusCode(200).body("id", is(seeded));
    }

    // ------------------------------------------------------------------------------------------- //
    // Parameter types
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUnknownParameterTypeAnswers404WithAndWithoutALanguage() {
        for (String query : List.of("", "?lang=en")) {
            as("sysadmin").when().get("/rest/templates/parameter-type/" + UNKNOWN + query)
                    .then().statusCode(404).body("detail", containsString(String.valueOf(UNKNOWN)));
        }
        // The right path: a seeded list parameter type, whose values the language sorts
        int seeded = ints("SELECT id FROM public.\"ParamType\" WHERE \"DTYPE\" = 'ListParamType' ORDER BY id LIMIT 1")
                .getFirst();
        for (String query : List.of("", "?lang=en")) {
            as("sysadmin").when().get("/rest/templates/parameter-type/" + seeded + query)
                    .then().statusCode(200).body("id", is(seeded));
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Promulgation types (PROM12, its first half)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUnknownPromulgationTypeAnswers404() {
        String typeId = "it-no-such-type-" + unique();
        as("sysadmin").when().get("/rest/promulgations/promulgation-type/" + typeId)
                .then().statusCode(404).body("detail", containsString(typeId));
        // The right path: the seeded NAVTEX type
        as("sysadmin").when().get("/rest/promulgations/promulgation-type/navtex")
                .then().statusCode(200).body("typeId", is("navtex"));
    }
}
