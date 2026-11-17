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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A create or update without a required field, or with a name another row already has, is refused with a 400 that
 * names the field, and nothing is stored. Each of these used to reach the
 * database and fail there: a NOT NULL column or a unique key, answered as a plain 500 that named nothing. The cases:
 * a chart without a chart number; a message series without a series id or a main type; a dictionary
 * entry without a key; a script resource without a path (or with a path whose type cannot be read), and one renamed
 * to another resource's path; a parameter type under a name another type has, on create and on rename, and a composite
 * type whose template parameter has no id or no type, or whose template parameters hold one id twice; a saved message
 * filter without a name. The right path beside
 * each refusal still answers 200. (The contact case, a blank address, is in {@link ServerSideValidationITTest}.)
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class RequiredFieldRefusalsITTest extends RoundTripSupport {

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        // Last made, first removed
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException | AssertionError ignored) {
                // best effort
            }
        }
        cleanups.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Chart
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aChartWithoutAChartNumberIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"Chart\"");
        postRaw("admin", "/rest/charts/chart/", map()).statusCode(400)
                .body("detail", containsString("chart number"));
        postRaw("admin", "/rest/charts/chart/", map("chartNumber", "  ", "name", "IT-req chart", "active", true))
                .statusCode(400).body("detail", containsString("chart number"));
        assertEquals(before, count("SELECT count(*) FROM public.\"Chart\""), "no chart row");

        // The right path
        String number = "IT-req-" + unique();
        post("admin", "/rest/charts/chart/", map("chartNumber", number, "name", "IT-req chart", "active", true));
        cleanups.add(() -> delete("admin", "/rest/charts/chart/" + number));
        assertEquals(1L, count("SELECT count(*) FROM public.\"Chart\" WHERE \"chartNumber\" = ?1", number));
    }

    // ------------------------------------------------------------------------------------------- //
    // Message series
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageSeriesWithoutASeriesIdOrMainTypeIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"MessageSeries\"");
        String seriesId = "it-req-" + unique();

        postRaw("sysadmin", "/rest/message-series/series/", map()).statusCode(400)
                .body("detail", containsString("series id"));
        postRaw("sysadmin", "/rest/message-series/series/", seriesBody(null, "NW")).statusCode(400)
                .body("detail", containsString("series id"));
        postRaw("sysadmin", "/rest/message-series/series/", seriesBody(seriesId, null)).statusCode(400)
                .body("detail", allOf(containsString(seriesId), containsString("main type")));
        assertEquals(before, count("SELECT count(*) FROM public.\"MessageSeries\""), "no series row");

        // The right path
        post("sysadmin", "/rest/message-series/series/", seriesBody(seriesId, "NW"));
        cleanups.add(() -> delete("sysadmin", "/rest/message-series/series/" + seriesId));
        assertEquals(1L, count("SELECT count(*) FROM public.\"MessageSeries\" WHERE \"seriesId\" = ?1", seriesId));
    }

    private static Map<String, Object> seriesBody(String seriesId, String mainType) {
        Map<String, Object> body = map("types", list("LOCAL_WARNING"), "shortFormat", "IT-${number}",
                "numberSequenceType", "YEARLY");
        if (seriesId != null) {
            body.put("seriesId", seriesId);
        }
        if (mainType != null) {
            body.put("mainType", mainType);
        }
        return body;
    }

    // ------------------------------------------------------------------------------------------- //
    // Dictionary entry
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aDictionaryEntryWithoutAKeyIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"DictionaryEntry\"");
        postRaw("sysadmin", "/rest/dictionaries/dictionary/web",
                map("descs", list(map("lang", "en", "value", "IT-req value")))).statusCode(400)
                .body("detail", containsString("needs a key"));
        postRaw("sysadmin", "/rest/dictionaries/dictionary/web",
                map("key", " ", "descs", list(map("lang", "en", "value", "IT-req value")))).statusCode(400)
                .body("detail", containsString("needs a key"));
        assertEquals(before, count("SELECT count(*) FROM public.\"DictionaryEntry\""), "no entry row");

        // The right path
        String key = "IT-req-" + unique();
        post("sysadmin", "/rest/dictionaries/dictionary/web",
                map("key", key, "descs", list(map("lang", "en", "value", "IT-req value"))));
        cleanups.add(() -> delete("sysadmin", "/rest/dictionaries/dictionary/web/" + key));
        assertEquals(1L, count("SELECT count(*) FROM public.\"DictionaryEntry\" WHERE dict_key = ?1", key));
    }

    // ------------------------------------------------------------------------------------------- //
    // Script resource
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aScriptResourceWithoutAPathOrWithAPathOfNoTypeIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"ScriptResource\"");
        postRaw("sysadmin", "/rest/script-resources/script-resource/", map()).statusCode(400)
                .body("detail", containsString("needs a path"));
        postRaw("sysadmin", "/rest/script-resources/script-resource/", map("type", "FM", "content", "x"))
                .statusCode(400).body("detail", containsString("needs a path"));
        // The save sets the type from the path's extension, and the type is required
        String noType = "templates/it-req/script-" + unique() + ".txt";
        postRaw("sysadmin", "/rest/script-resources/script-resource/", map("type", "FM", "path", noType, "content", "x"))
                .statusCode(400).body("detail", allOf(containsString(noType), containsString(".ftl or .js")));
        assertEquals(before, count("SELECT count(*) FROM public.\"ScriptResource\""), "no resource row");
    }

    @Test
    void aScriptResourceRenamedToAnotherResourcesPathIsRefusedAndKeepsItsPath() {
        String u = unique();
        String taken = "templates/it-req/taken-" + u + ".ftl";
        String mine = "templates/it-req/mine-" + u + ".ftl";
        int takenId = createResource(taken);
        int id = createResource(mine);

        Map<String, Object> edit = map("id", id, "type", "FM", "path", taken, "content", "<#-- changed -->");
        putRaw("sysadmin", "/rest/script-resources/script-resource/" + id, edit).statusCode(400)
                .body("detail", allOf(containsString("Another script resource"), containsString(taken)));
        assertEquals(List.of(mine), strings("SELECT path FROM public.\"ScriptResource\" WHERE id = ?1", id), "unchanged");
        assertEquals(List.of("<#-- " + mine + " -->"),
                strings("SELECT content FROM public.\"ScriptResource\" WHERE id = ?1", id), "unchanged");
        assertEquals(List.of(taken), strings("SELECT path FROM public.\"ScriptResource\" WHERE id = ?1", takenId));

        // A blank path is refused on update too
        edit.put("path", "");
        putRaw("sysadmin", "/rest/script-resources/script-resource/" + id, edit).statusCode(400)
                .body("detail", containsString("needs a path"));

        // The right path: a save that keeps its own path, then a rename to a free one
        edit.put("path", mine);
        put("sysadmin", "/rest/script-resources/script-resource/" + id, edit);
        String free = "templates/it-req/free-" + u + ".ftl";
        edit.put("path", free);
        put("sysadmin", "/rest/script-resources/script-resource/" + id, edit);
        assertEquals(List.of(free), strings("SELECT path FROM public.\"ScriptResource\" WHERE id = ?1", id));
    }

    private int createResource(String path) {
        int id = ((Number) post("sysadmin", "/rest/script-resources/script-resource/",
                map("type", "FM", "path", path, "content", "<#-- " + path + " -->")).get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/script-resources/script-resource/" + id));
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // Parameter type
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aParameterTypeUnderATakenNameIsRefusedOnCreateAndRename() {
        String u = unique();
        String taken = "IT-req taken " + u;
        String mine = "IT-req mine " + u;
        int takenId = createParamType(map("type", "LIST", "name", taken, "values", list()));
        int id = createParamType(map("type", "LIST", "name", mine, "values", list()));
        long before = count("SELECT count(*) FROM public.\"ParamType\"");

        // Create under the taken name, of the same kind and of another kind
        postRaw("sysadmin", "/rest/templates/parameter-type/", map("type", "LIST", "name", taken, "values", list()))
                .statusCode(400).body("detail", allOf(containsString("Another parameter type"), containsString(taken)));
        postRaw("sysadmin", "/rest/templates/parameter-type/", map("type", "COMPOSITE", "name", taken,
                "templateParams", list(map("paramId", "p1", "type", "text")))).statusCode(400)
                .body("detail", containsString(taken));
        assertEquals(before, count("SELECT count(*) FROM public.\"ParamType\""), "no parameter type row");

        // Rename to the taken name
        putRaw("sysadmin", "/rest/templates/parameter-type/" + id,
                map("id", id, "type", "LIST", "name", taken, "values", list())).statusCode(400)
                .body("detail", allOf(containsString("Another parameter type"), containsString(taken)));
        assertEquals(List.of(mine), strings("SELECT name FROM public.\"ParamType\" WHERE id = ?1", id), "unchanged");
        assertEquals(List.of(taken), strings("SELECT name FROM public.\"ParamType\" WHERE id = ?1", takenId));

        // The right path: a save under its own name, and a rename to a free one
        put("sysadmin", "/rest/templates/parameter-type/" + id,
                map("id", id, "type", "LIST", "name", mine, "values", list()));
        String free = "IT-req free " + u;
        put("sysadmin", "/rest/templates/parameter-type/" + id,
                map("id", id, "type", "LIST", "name", free, "values", list()));
        assertEquals(List.of(free), strings("SELECT name FROM public.\"ParamType\" WHERE id = ?1", id));
    }

    @Test
    void aCompositeParameterWithoutAnIdOrTypeIsRefusedAndNothingIsStored() {
        String name = "IT-req composite " + unique();
        long types = count("SELECT count(*) FROM public.\"ParamType\"");
        long params = count("SELECT count(*) FROM public.\"TemplateParam\"");

        postRaw("sysadmin", "/rest/templates/parameter-type/", map("type", "COMPOSITE", "name", name,
                "templateParams", list(map("paramId", "p1", "type", "text"), map("type", "number")))).statusCode(400)
                .body("detail", allOf(containsString("Parameter 2"), containsString(name), containsString("parameter id")));
        postRaw("sysadmin", "/rest/templates/parameter-type/", map("type", "COMPOSITE", "name", name,
                "templateParams", list(map("paramId", "p1")))).statusCode(400)
                .body("detail", allOf(containsString("p1"), containsString("needs a type")));
        assertEquals(types, count("SELECT count(*) FROM public.\"ParamType\""), "no parameter type row");
        assertEquals(params, count("SELECT count(*) FROM public.\"TemplateParam\""), "no template parameter row");

        // The right path, then an update that drops a parameter's type is refused and changes nothing
        int id = createParamType(map("type", "COMPOSITE", "name", name,
                "templateParams", list(map("paramId", "p1", "type", "text"))));
        putRaw("sysadmin", "/rest/templates/parameter-type/" + id, map("id", id, "type", "COMPOSITE", "name", name,
                "templateParams", list(map("paramId", "p1", "type", "")))).statusCode(400)
                .body("detail", containsString("needs a type"));
        assertEquals(List.of("text"),
                strings("SELECT tp.type FROM public.\"TemplateParam\" tp JOIN public.\"ParamType_TemplateParam\" j"
                        + " ON j.\"templateParams_id\" = tp.id WHERE j.\"CompositeParamType_id\" = ?1", id));
    }

    /**
     * A composite type whose parameters hold one id twice is refused on create and on update with a 400 naming the
     * id, and nothing is stored or changed: at execution the parameters are keyed by their id, so the two would share
     * one value. It used to be saved
     */
    @Test
    void aCompositeParameterTypeWithARepeatedParameterIdIsRefusedOnCreateAndUpdate() {
        String name = "IT-req repeated " + unique();
        long types = count("SELECT count(*) FROM public.\"ParamType\"");
        long params = count("SELECT count(*) FROM public.\"TemplateParam\"");

        postRaw("sysadmin", "/rest/templates/parameter-type/", map("type", "COMPOSITE", "name", name,
                "templateParams", list(map("paramId", "depth", "type", "number"), map("paramId", "kind", "type", "text"),
                        map("paramId", "depth", "type", "text")))).statusCode(400)
                .body("detail", equalTo("Parameter type " + name + " has more than one parameter with the id depth"));
        assertEquals(types, count("SELECT count(*) FROM public.\"ParamType\""), "no parameter type row");
        assertEquals(params, count("SELECT count(*) FROM public.\"TemplateParam\""), "no template parameter row");

        // The right path, then an update that repeats an id is refused and changes nothing
        int id = createParamType(map("type", "COMPOSITE", "name", name,
                "templateParams", list(map("paramId", "depth", "type", "number"), map("paramId", "kind", "type", "text"))));
        putRaw("sysadmin", "/rest/templates/parameter-type/" + id, map("id", id, "type", "COMPOSITE", "name", name,
                "templateParams", list(map("paramId", "depth", "type", "number"), map("paramId", "kind", "type", "text"),
                        map("paramId", "kind", "type", "number")))).statusCode(400)
                .body("detail", equalTo("Parameter type " + name + " has more than one parameter with the id kind"));
        String storedIds = "SELECT tp.\"paramId\" FROM public.\"TemplateParam\" tp JOIN public.\"ParamType_TemplateParam\" j"
                + " ON j.\"templateParams_id\" = tp.id WHERE j.\"CompositeParamType_id\" = ?1 ORDER BY j.\"indexNo\"";
        assertEquals(List.of("depth", "kind"), strings(storedIds, id), "unchanged");

        // An update with distinct ids, if only in case, is saved
        put("sysadmin", "/rest/templates/parameter-type/" + id, map("id", id, "type", "COMPOSITE", "name", name,
                "templateParams", list(map("paramId", "kind", "type", "text"), map("paramId", "depth", "type", "number"),
                        map("paramId", "Kind", "type", "number"))));
        assertEquals(List.of("kind", "depth", "Kind"), strings(storedIds, id));
    }

    private int createParamType(Map<String, Object> body) {
        int id = ((Number) post("sysadmin", "/rest/templates/parameter-type/", body).get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/templates/parameter-type/" + id));
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // Message filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aFilterWithoutANameIsRefusedAndNothingIsStored() {
        long before = count("SELECT count(*) FROM public.\"MessageFilter\"");
        postRaw("editor", "/rest/filters/filter", map()).statusCode(400)
                .body("detail", containsString("needs a name"));
        postRaw("editor", "/rest/filters/filter", map("name", "  ", "parameters", "status=PUBLISHED")).statusCode(400)
                .body("detail", containsString("needs a name"));
        assertEquals(before, count("SELECT count(*) FROM public.\"MessageFilter\""), "no filter row");

        // The right path, then an update of it by id without a name is refused and changes nothing
        String name = "IT-req filter " + unique();
        int id = ((Number) post("editor", "/rest/filters/filter", map("name", name, "parameters", "status=PUBLISHED"))
                .get("id")).intValue();
        cleanups.add(() -> delete("editor", "/rest/filters/filter/" + id));
        postRaw("editor", "/rest/filters/filter", map("id", id, "parameters", "status=DRAFT")).statusCode(400);
        assertEquals(List.of(name), strings("SELECT name FROM public.\"MessageFilter\" WHERE id = ?1", id), "unchanged");
    }
}
