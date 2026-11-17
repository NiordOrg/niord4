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

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryType;
import org.niord.core.category.TemplateParam;
import org.niord.test.Tx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every message template the base product's bootstrap ships, executed the way the editor executes it
 * ({@code PUT /rest/templates/execute}, with the message's template category and the parameter values the
 * template dialog sends), for a matrix of inputs, and its output read as a mariner reads it: the title, the
 * subject and details, the NAVTEX text and the audio text.
 *
 * <p>The matrix varies one thing at a time from an ordinary case: the position (one, on a whole degree, just
 * below one, a negative longitude, three, an area, none), every value of every list parameter, the edges of the
 * other parameters (empty, 0, 1, many, a decimal, a name with an apostrophe), the event dates (within a day,
 * across midnight, a month end, a clock change and New Year, open-ended, all-day, two intervals, none), the work
 * vessel and the markings. Each template's outputs, with what a mechanical reading finds wrong in them
 * ({@link #lint}), are pinned in a golden file per template under {@code src/test/resources/golden/templates/}, so
 * a change to a template or to a formatter shows as a diff ({@code -Dgolden.write=true} rewrites them; then the
 * diff has to be read). The faults the reading found are pinned one by one by the {@code _documentsBug} tests
 * below, which flip when they are fixed.
 *
 * <p>The templates come from the IT database's fixture, which carries the bootstrap's 80 templates as rows
 * ({@code TestFixtureCurrentTest} keeps it equal to {@code niord/bootstrap/templates/}). Nothing is saved: the
 * execute endpoint renders on the message it is sent. The NAVTEX promulgation type's own script
 * ({@code navtex.ftl}), which the base product does not link, is linked for one test and unlinked after it.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class BootstrapTemplateMatrixITTest extends RoundTripSupport {

    private static final String EXECUTE = "/rest/templates/execute";

    /** The sample's "The Baltic Sea", a child of "Denmark": the title line's area */
    private static final int AREA_ID = 1000002;

    // Positions [lon, lat]
    private static final double[] P_ORDINARY = {10.5, 55.5};
    private static final double[] P_WHOLE = {12.0, 56.0};
    private static final double[] P_JUST_BELOW = {11.999999, 55.999999};
    private static final double[] P_WEST = {-45.25, 61.1};
    private static final double[][] P_THREE = {{10.5, 55.5}, {10.75, 55.6}, {11.0, 55.7}};
    private static final double[][] P_AREA = {{10.5, 55.5}, {11.0, 55.5}, {11.0, 56.0}, {10.5, 56.0}, {10.5, 55.5}};

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    /** The observation date of the date parameter: 10:30 local in Copenhagen */
    private static final long DATE_ORDINARY = at("2026-05-12T08:30:00Z");

    private static Map<String, Object> interval(Long from, Long to, Boolean allDay) {
        Map<String, Object> di = new LinkedHashMap<>();
        di.put("allDay", allDay);
        di.put("fromDate", from);
        di.put("toDate", to);
        return di;
    }

    /** The event-date variants, in Copenhagen's local time where it says so */
    private static Map<String, List<Map<String, Object>>> eventDateVariants() {
        Map<String, List<Map<String, Object>>> v = new LinkedHashMap<>();
        v.put("within a day (12 May 10:00-14:00 CEST)",
                List.of(interval(at("2026-05-12T08:00:00Z"), at("2026-05-12T12:00:00Z"), false)));
        v.put("across midnight (12 May 22:00 - 13 May 02:00 CEST)",
                List.of(interval(at("2026-05-12T20:00:00Z"), at("2026-05-13T00:00:00Z"), false)));
        v.put("across a month end (31 May 20:00 - 1 Jun 06:00 CEST)",
                List.of(interval(at("2026-05-31T18:00:00Z"), at("2026-06-01T04:00:00Z"), false)));
        v.put("across the spring clock change (28 Mar 22:00 CET - 29 Mar 04:00 CEST)",
                List.of(interval(at("2026-03-28T21:00:00Z"), at("2026-03-29T02:00:00Z"), false)));
        v.put("across New Year (31 Dec 22:00 - 1 Jan 02:00 CET)",
                List.of(interval(at("2026-12-31T21:00:00Z"), at("2027-01-01T01:00:00Z"), false)));
        v.put("open-ended (from 12 May 10:00 CEST)",
                List.of(interval(at("2026-05-12T08:00:00Z"), null, false)));
        v.put("all-day (12 May - 14 May)",
                List.of(interval(at("2026-05-11T22:00:00Z"), at("2026-05-14T21:59:59Z"), true)));
        v.put("two intervals",
                List.of(interval(at("2026-05-12T08:00:00Z"), at("2026-05-12T12:00:00Z"), false),
                        interval(at("2026-05-14T06:00:00Z"), at("2026-05-14T15:00:00Z"), false)));
        v.put("no event dates", List.of());
        return v;
    }

    private static Map<String, Object> vessel(String name, String callSign, boolean guard, boolean contact,
                                              Integer channel, Integer minDist, String minDistType) {
        Map<String, Object> vessel = new LinkedHashMap<>();
        if (name != null) vessel.put("name", name);
        if (callSign != null) vessel.put("callSign", callSign);
        vessel.put("guardVessels", guard);
        vessel.put("contact", contact);
        if (channel != null) vessel.put("channel", channel);
        if (minDist != null) vessel.put("minDist", minDist);
        if (minDistType != null) vessel.put("minDistType", minDistType);
        return vessel;
    }

    // ------------------------------------------------------------------------------------------- //
    // The templates and the parameter types, read from the fixture
    // ------------------------------------------------------------------------------------------- //

    record ParamDef(String paramId, String type, boolean positionList) {}

    record TemplateDef(int id, String name, List<String> scripts, List<String> stdFields, List<ParamDef> params) {
        String file() {
            return name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + ".txt";
        }
    }

    private List<TemplateDef> templates;
    private Map<String, Map<String, Object>> paramTypes;
    private String messageTemplateJson;

    @BeforeEach
    void readTemplatesAndTypes() {
        templates = Tx.requiringNew().call(() -> {
            List<TemplateDef> list = new ArrayList<>();
            List<Category> categories = em.createQuery(
                            "select c from Category c where c.type = :type order by c.id", Category.class)
                    .setParameter("type", CategoryType.TEMPLATE)
                    .getResultList();
            for (Category c : categories) {
                String name = c.getDescs().stream().filter(d -> "en".equals(d.getLang()))
                        .map(d -> d.getName()).findFirst().orElse("category-" + c.getId());
                List<ParamDef> params = new ArrayList<>();
                for (TemplateParam p : c.getTemplateParams()) {
                    params.add(new ParamDef(p.getParamId(), p.getType(), p.isPositionList()));
                }
                list.add(new TemplateDef(c.getId(), name,
                        c.getScriptResourcePaths() == null ? List.of() : List.copyOf(c.getScriptResourcePaths()),
                        c.getStdTemplateFields() == null ? List.of() : List.copyOf(c.getStdTemplateFields()),
                        params));
            }
            return list;
        });
        paramTypes = new LinkedHashMap<>();
        for (Map<String, Object> type : getList("sysadmin", "/rest/templates/parameter-types")) {
            paramTypes.put((String) type.get("name"), type);
        }
        messageTemplateJson = as("sysadmin").when().get("/rest/messages/new-message-template?mainType=NW")
                .then().statusCode(200).extract().asString();
    }

    /** The base product's templates: those whose scripts all live under templates/tmpl/ and that run any */
    private List<TemplateDef> baseTemplates() {
        return templates.stream()
                .filter(t -> !t.scripts().isEmpty())
                .filter(t -> t.scripts().stream().allMatch(s -> s.startsWith("templates/tmpl/")))
                .toList();
    }

    private TemplateDef template(String name) {
        return templates.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no template category " + name));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValues(String type) {
        Map<String, Object> pt = paramTypes.get(type);
        return pt == null ? List.of() : (List<Map<String, Object>>) pt.get("values");
    }

    private Map<String, Object> listValue(String type, String key) {
        return listValues(type).stream().filter(v -> key.equals(v.get("key"))).findFirst()
                .orElseThrow(() -> new AssertionError("no value " + key + " in " + type));
    }

    /** A template parameter as the fields it puts into the template's params: a composite's sub-parameters */
    @SuppressWarnings("unchecked")
    private List<ParamDef> fields(ParamDef param) {
        Map<String, Object> pt = paramTypes.get(param.type());
        if (pt != null && "COMPOSITE".equals(pt.get("type"))) {
            List<ParamDef> fields = new ArrayList<>();
            for (Map<String, Object> sub : (List<Map<String, Object>>) pt.get("templateParams")) {
                fields.add(new ParamDef((String) sub.get("paramId"), (String) sub.get("type"), param.positionList()));
            }
            return fields;
        }
        return List.of(param);
    }

    private boolean isList(String type) {
        Map<String, Object> pt = paramTypes.get(type);
        return pt != null && "LIST".equals(pt.get("type"));
    }

    // ------------------------------------------------------------------------------------------- //
    // The cases
    // ------------------------------------------------------------------------------------------- //

    /** One execution: the geometry, the event dates and the parameter values */
    static final class Case {
        String label;
        List<Object> features = new ArrayList<>();
        List<Map<String, Object>> eventDates = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> positionFields = new LinkedHashMap<>();
        Map<String, Object> messageFields = new LinkedHashMap<>();

        Case copy(String label) {
            Case c = new Case();
            c.label = label;
            c.messageFields = new LinkedHashMap<>(messageFields);
            c.features = new ArrayList<>(features);
            c.eventDates = new ArrayList<>(eventDates);
            c.params = new LinkedHashMap<>(params);
            c.positionFields = new LinkedHashMap<>(positionFields);
            return c;
        }
    }

    private static Map<String, Object> point(double[] p) {
        return Map.of("type", "Point", "coordinates", List.of(p[0], p[1]));
    }

    private static Map<String, Object> polygon(double[][] ring) {
        List<Object> coords = new ArrayList<>();
        for (double[] p : ring) coords.add(List.of(p[0], p[1]));
        return Map.of("type", "Polygon", "coordinates", List.of(coords));
    }

    private Object ordinaryValue(ParamDef f) {
        if (isList(f.type())) {
            return listValues(f.type()).get(0);
        }
        return switch (f.type()) {
            case "text", "aton_name" -> "Hals Barre";
            case "number" -> 12;
            case "boolean" -> false;
            case "date" -> DATE_ORDINARY;
            case "light_character" -> "Fl.G.3s";
            case "call_sign" -> "OXYZ";
            default -> null;
        };
    }

    /** The edge values of a field, by label; a null value leaves the field out */
    private Map<String, Object> edgeValues(ParamDef f) {
        Map<String, Object> v = new LinkedHashMap<>();
        if (isList(f.type())) {
            for (Map<String, Object> value : listValues(f.type())) {
                v.put((String) value.get("key"), value);
            }
            v.put("empty", null);
            return v;
        }
        switch (f.type()) {
            case "text", "aton_name" -> {
                v.put("empty", null);
                v.put("with an apostrophe", "St. John's Point");
            }
            case "number" -> {
                v.put("0", 0);
                v.put("1", 1);
                v.put("2.5", 2.5);
                v.put("1250", 1250);
                v.put("empty", null);
            }
            case "boolean" -> {
                v.put("true", true);
                v.put("false", false);
                v.put("empty", null);
            }
            case "date" -> {
                v.put("31 Dec 23:30 local", at("2026-12-31T22:30:00Z"));
                v.put("1 Jan 00:30 local", at("2026-12-31T23:30:00Z"));
                v.put("empty", null);
            }
            case "light_character" -> {
                v.put("Fl(2)R.10s 5M", "Fl(2)R.10s 5M");
                v.put("Q(6)+LFl.15s", "Q(6)+LFl.15s");
                v.put("Iso.WRG.4s", "Iso.WRG.4s");
                v.put("Fl.G.1s", "Fl.G.1s");
                v.put("not a light character", "nonsense");
                v.put("empty", null);
            }
            default -> v.put("empty", null);
        }
        return v;
    }

    private static boolean isAtonTemplate(TemplateDef t) {
        return t.params().stream().anyMatch(ParamDef::positionList);
    }

    private List<Case> cases(TemplateDef t) {
        Case base = new Case();
        base.label = "ordinary";
        base.features.add(point(P_ORDINARY));
        base.params.put("posDecimals", "1");
        for (ParamDef param : t.params()) {
            for (ParamDef f : fields(param)) {
                Object value = ordinaryValue(f);
                if (value != null) {
                    (f.positionList() ? base.positionFields : base.params).put(f.paramId(), value);
                }
            }
        }
        if (t.stdFields().contains("event_dates")) {
            base.eventDates.addAll(eventDateVariants().values().iterator().next());
        }
        if (t.stdFields().contains("work_vessel")) {
            base.params.put("vessel", vessel("Poseidon", "OXYZ", false, false, null, null, null));
        }
        if (t.stdFields().contains("markings")) {
            base.params.put("markingType", "unmarked");
            base.params.put("markings", List.of());
        }

        List<Case> cases = new ArrayList<>();
        cases.add(base);

        Map<String, List<Object>> geometries = new LinkedHashMap<>();
        geometries.put("a position on a whole degree (56 00N 012 00E)", List.of(point(P_WHOLE)));
        geometries.put("a position just below a whole degree (55.999999N 11.999999E)", List.of(point(P_JUST_BELOW)));
        geometries.put("a negative longitude (61 06N 045 15W)", List.of(point(P_WEST)));
        geometries.put("three positions", List.of(point(P_THREE[0]), point(P_THREE[1]), point(P_THREE[2])));
        if (!isAtonTemplate(t)) {
            geometries.put("an area (polygon)", List.of(polygon(P_AREA)));
        }
        geometries.put("no position", List.of());
        geometries.forEach((label, features) -> {
            Case c = base.copy("geometry: " + label);
            c.features = new ArrayList<>(features);
            cases.add(c);
        });

        for (ParamDef param : t.params()) {
            for (ParamDef f : fields(param)) {
                edgeValues(f).forEach((label, value) -> {
                    Case c = base.copy("param " + f.paramId() + " = " + label);
                    Map<String, Object> target = f.positionList() ? c.positionFields : c.params;
                    if (value == null) {
                        target.remove(f.paramId());
                    } else {
                        target.put(f.paramId(), value);
                    }
                    cases.add(c);
                });
            }
        }

        if (t.stdFields().contains("event_dates")) {
            eventDateVariants().forEach((label, dates) -> {
                Case c = base.copy("event dates: " + label);
                c.eventDates = new ArrayList<>(dates);
                cases.add(c);
            });
        }
        if (t.stdFields().contains("work_vessel")) {
            Map<String, Object> vessels = new LinkedHashMap<>();
            vessels.put("none", null);
            vessels.put("name only", vessel("Poseidon", null, false, false, null, null, null));
            vessels.put("call sign only", vessel(null, "OXYZ", false, false, null, null, null));
            vessels.put("everything, minimum distance in m", vessel("Poseidon", "OXYZ", true, true, 12, 500, "m"));
            vessels.put("everything, minimum distance in nm", vessel("Poseidon", "OXYZ", true, true, 12, 2, "nm"));
            vessels.put("a name with an apostrophe", vessel("Queen's Pride", null, false, true, null, null, null));
            vessels.forEach((label, vessel) -> {
                Case c = base.copy("vessel: " + label);
                if (vessel == null) {
                    c.params.remove("vessel");
                } else {
                    c.params.put("vessel", vessel);
                }
                cases.add(c);
            });
        }
        if (t.stdFields().contains("markings")) {
            Map<String, Object> buoy = new LinkedHashMap<>();
            buoy.put("type", listValue("marking_type_list", "aton.type.light_buoy.black_yellow"));
            buoy.put("lightCharacter", "Q(3)10s");
            buoy.put("distance", 50);
            buoy.put("bearing", listValue("marking_bearing", "aton.bearing.e"));
            Map<String, Object> plainBuoy = new LinkedHashMap<>();
            plainBuoy.put("type", listValue("marking_type_list", "aton.type.buoy.red"));
            Map<String, Object> zeroDistance = new LinkedHashMap<>();
            zeroDistance.put("type", listValue("marking_type_list", "aton.type.buoy.green"));
            zeroDistance.put("distance", 0);
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("type", listValue("other_marking_type_list", "marking.type.balloon"));
            other.put("color", listValue("other_marking_color_list", "marking.color.red"));
            Map<String, Object> markings = new LinkedHashMap<>();
            markings.put("one buoy with light, distance and bearing", new Object[]{"buoy", List.of(buoy)});
            markings.put("two buoys", new Object[]{"buoy", List.of(buoy, plainBuoy)});
            markings.put("a buoy at distance 0", new Object[]{"buoy", List.of(zeroDistance)});
            markings.put("other marking (red balloon)", new Object[]{"marking", List.of(other)});
            markings.forEach((label, m) -> {
                Object[] pair = (Object[]) m;
                Case c = base.copy("markings: " + label);
                c.params.put("markingType", pair[0]);
                c.params.put("markings", pair[1]);
                cases.add(c);
            });
        }
        return cases;
    }

    // ------------------------------------------------------------------------------------------- //
    // Execution
    // ------------------------------------------------------------------------------------------- //

    /** What the editor gets back, as a mariner reads it */
    record Output(int status, String error, String title, List<String> subjects, List<String> details,
                  String navtex, String audio, String twitter) {}

    private Map<String, Object> messageFor(TemplateDef t, Case c) {
        Map<String, Object> message = new JsonPath(messageTemplateJson).getMap("");
        message.put("type", "COASTAL_WARNING");
        message.put("autoTitle", true);
        message.put("areas", List.of(Map.of("id", AREA_ID)));
        message.put("categories", List.of(Map.of("id", t.id())));
        List<Object> features = new ArrayList<>();
        for (Object geometry : c.features) {
            features.add(Map.of("type", "Feature", "geometry", geometry, "properties", Map.of()));
        }
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "DETAILS");
        part.put("geometry", Map.of("type", "FeatureCollection", "features", features));
        part.put("eventDates", c.eventDates);
        message.put("parts", List.of(part));
        message.putAll(c.messageFields);
        Object promulgations = message.get("promulgations");
        if (promulgations instanceof List<?> list) {
            for (Object p : list) {
                @SuppressWarnings("unchecked") Map<String, Object> promulgation = (Map<String, Object>) p;
                promulgation.put("promulgate", true);
            }
        }
        return message;
    }

    private Map<String, Object> paramsFor(Case c) {
        Map<String, Object> params = new LinkedHashMap<>(c.params);
        if (!c.positionFields.isEmpty() || true) {
            List<Object> positions = new ArrayList<>();
            for (Object geometry : c.features) {
                Map<String, Object> pos = new LinkedHashMap<>(c.positionFields);
                pos.put("geometry", List.of(Map.of("coordinates", readableCoordinates(geometry))));
                positions.add(pos);
            }
            params.put("positions", positions);
        }
        return params;
    }

    /** As the editor's serializeReadableCoordinates: the vertices, without a polygon's closing one */
    @SuppressWarnings("unchecked")
    private static List<Object> readableCoordinates(Object geometry) {
        Map<String, Object> g = (Map<String, Object>) geometry;
        List<Object> result = new ArrayList<>();
        if ("Point".equals(g.get("type"))) {
            result.add(Map.of("coordinates", g.get("coordinates")));
        } else if ("Polygon".equals(g.get("type"))) {
            List<Object> ring = (List<Object>) ((List<Object>) g.get("coordinates")).get(0);
            for (int i = 0; i < ring.size() - 1; i++) {
                result.add(Map.of("coordinates", ring.get(i)));
            }
        }
        return result;
    }

    private Output execute(TemplateDef t, Case c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", messageFor(t, c));
        body.put("templateParams", List.of(paramsFor(c)));
        Response response = as("sysadmin").body(body).when().put(EXECUTE);
        if (response.statusCode() != 200) {
            String text = response.asString();
            return new Output(response.statusCode(), text.length() > 300 ? text.substring(0, 300) : text,
                    null, List.of(), List.of(), null, null, null);
        }
        JsonPath json = response.jsonPath();
        String title = json.getString("descs.find { it.lang == 'en' }.title");
        List<String> subjects = new ArrayList<>();
        List<String> details = new ArrayList<>();
        List<Map<String, Object>> parts = json.getList("parts");
        for (int i = 0; parts != null && i < parts.size(); i++) {
            subjects.add(json.getString("parts[" + i + "].descs.find { it.lang == 'en' }.subject"));
            details.add(json.getString("parts[" + i + "].descs.find { it.lang == 'en' }.details"));
        }
        String navtex = json.getString("promulgations.find { it.type.typeId == 'navtex' }.text");
        String audio = json.getString("promulgations.find { it.type.typeId == 'audio' }.text");
        String twitter = json.getString("promulgations.find { it.type.typeId == 'twitter' }.tweet");
        return new Output(200, null, title, subjects, details, navtex, audio, twitter);
    }

    // ------------------------------------------------------------------------------------------- //
    // The reading: what a mariner would find wrong, mechanically
    // ------------------------------------------------------------------------------------------- //

    /** The NAVTEX alphabet: letters, figures, the ITA2 signs, space and newline */
    private static final Pattern NAVTEX_OUTSIDE = Pattern.compile("[^A-Z0-9?:().,'=/+\\- \\n]");
    private static final Pattern LEFTOVER = Pattern.compile(
            "\\bnull\\b|\\bundefined\\b|\\bNaN\\b|\\$\\{|<#|\\?\\?\\?|FreeMarker|freemarker|Exception", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACE_BEFORE_STOP = Pattern.compile("\\s[.,]");
    private static final Pattern DOUBLE_STOP = Pattern.compile("\\.\\s*\\.");
    private static final Pattern DANGLING_POS = Pattern.compile("\\b(in pos\\.|between pos\\.|in position|between positions|in the area)\\s*([.,]|$|and\\b|is\\b|has\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOWER_START = Pattern.compile("(^|[.!?]\\s+)([a-z])");
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3})[° -](\\d{2})[.,](\\d)'?\\s?([NSEW])");
    private static final Pattern DOUBLE_WORD = Pattern.compile("\\b(\\w+)\\s+\\1\\b", Pattern.CASE_INSENSITIVE);

    /** The text of an HTML field as it reads: tags gone, a break a line, white space collapsed */
    static String readable(String html) {
        if (html == null) {
            return null;
        }
        return html.replaceAll("(?i)<br\\s*/?>", " / ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static List<String> lint(Output o) {
        List<String> findings = new ArrayList<>();
        if (o.status() != 200) {
            findings.add("HTTP " + o.status());
            return findings;
        }
        Consumer<String[]> check = fieldAndText -> {
            String field = fieldAndText[0];
            String text = fieldAndText[1];
            if (text == null || text.isBlank()) {
                findings.add(field + ": empty");
                return;
            }
            Matcher m = LEFTOVER.matcher(text);
            while (m.find()) findings.add(field + ": leftover '" + m.group() + "'");
            m = SPACE_BEFORE_STOP.matcher(text);
            if (m.find()) findings.add(field + ": a space before a full stop or comma");
            m = DOUBLE_STOP.matcher(text);
            if (m.find()) findings.add(field + ": a doubled full stop");
            m = DANGLING_POS.matcher(text);
            if (m.find()) findings.add(field + ": '" + m.group(1) + "' without a position");
            m = DOUBLE_WORD.matcher(text);
            while (m.find()) {
                if (!m.group(1).matches("\\d+")) findings.add(field + ": doubled word '" + m.group() + "'");
            }
            m = MINUTES.matcher(text);
            while (m.find()) {
                if (Integer.parseInt(m.group(2)) >= 60) findings.add(field + ": minutes of 60 or more in " + m.group());
            }
            if (!field.startsWith("navtex")) {
                m = LOWER_START.matcher(text.replaceAll("\\b(pos|approx|incl|no|St)\\.", "$1"));
                if (m.find()) findings.add(field + ": a sentence that starts in lower case");
            }
        };
        check.accept(new String[]{"title", o.title()});
        for (int i = 0; i < o.details().size(); i++) {
            check.accept(new String[]{"subject", o.subjects().get(i)});
            check.accept(new String[]{"details", readable(o.details().get(i))});
        }
        if (o.navtex() != null) {
            check.accept(new String[]{"navtex", o.navtex()});
            String[] lines = o.navtex().split("\n");
            for (String line : lines) {
                if (line.length() > 40) findings.add("navtex: a line of " + line.length() + " characters");
            }
            Matcher m = NAVTEX_OUTSIDE.matcher(o.navtex());
            StringBuilder outside = new StringBuilder();
            while (m.find()) if (outside.indexOf(m.group()) < 0) outside.append(m.group());
            if (outside.length() > 0) findings.add("navtex: outside the NAVTEX alphabet '" + outside + "'");
        } else {
            findings.add("navtex: none");
        }
        if (o.audio() != null) {
            check.accept(new String[]{"audio", readable(o.audio())});
        }
        return findings;
    }

    private static String render(String caseLabel, Output o) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(caseLabel).append('\n');
        if (o.status() != 200) {
            sb.append("HTTP ").append(o.status()).append(": ").append(o.error().replaceAll("\\s+", " ")).append('\n');
        } else {
            sb.append("TITLE:   ").append(o.title()).append('\n');
            for (int i = 0; i < o.details().size(); i++) {
                sb.append("SUBJECT: ").append(o.subjects().get(i)).append('\n');
                sb.append("DETAILS: ").append(readable(o.details().get(i))).append('\n');
            }
            sb.append("NAVTEX:\n");
            if (o.navtex() != null) {
                for (String line : o.navtex().split("\n")) sb.append("  | ").append(line).append('\n');
            }
            sb.append("AUDIO:   ").append(readable(o.audio())).append('\n');
            if (o.twitter() != null) sb.append("TWITTER: ").append(o.twitter()).append('\n');
        }
        for (String finding : lint(o)) {
            sb.append("LINT:    ").append(finding).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderTemplate(TemplateDef t) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(t.name()).append("  ").append(t.scripts()).append('\n');
        sb.append("# std fields ").append(t.stdFields()).append(", params ").append(t.params()).append("\n\n");
        for (Case c : cases(t)) {
            sb.append(render(c.label, execute(t, c)));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------- //
    // The golden files
    // ------------------------------------------------------------------------------------------- //

    private static String readGolden(String name) throws IOException {
        try (InputStream in = BootstrapTemplateMatrixITTest.class.getClassLoader()
                .getResourceAsStream("golden/templates/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    /**
     * Every base product template, executed for its whole matrix, against its golden file. A difference is a
     * change to what a template prints for mariners: read the diff. {@code -Dgolden.write=true} rewrites them.
     */
    @Test
    void everyTemplate_printsWhatItsGoldenFileSays() throws IOException {
        List<TemplateDef> base = baseTemplates();
        assertTrue(base.size() >= 36, "the bootstrap's template categories are in the fixture: " + base.size());
        List<String> differ = new ArrayList<>();
        StringBuilder index = new StringBuilder("# The base product's template categories, executed by "
                + "BootstrapTemplateMatrixITTest\n");
        for (TemplateDef t : base) {
            String actual = renderTemplate(t);
            index.append(t.file()).append("  ").append(t.name()).append("  ").append(t.scripts()).append('\n');
            if (Boolean.getBoolean("golden.write")) {
                Path file = Paths.get("src/test/resources/golden/templates", t.file());
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual, StandardCharsets.UTF_8);
            } else if (!actual.equals(readGolden(t.file()))) {
                differ.add(t.file());
            }
        }
        if (Boolean.getBoolean("golden.write")) {
            Files.writeString(Paths.get("src/test/resources/golden/templates/index.txt"), index.toString(),
                    StandardCharsets.UTF_8);
        }
        assertEquals(List.of(), differ, "a template prints something else than its golden file says; read the diff "
                + "(run with -Dgolden.write=true to see it in git)");
    }

    // ------------------------------------------------------------------------------------------- //
    // Single cases
    // ------------------------------------------------------------------------------------------- //

    private Case ordinary(TemplateDef t) {
        return cases(t).get(0);
    }

    /** Every template executes for the ordinary case and gives a title, details and a NAVTEX text */
    @Test
    void everyTemplate_givesATitleDetailsAndNavtex_forTheOrdinaryCase() {
        List<String> faults = new ArrayList<>();
        for (TemplateDef t : baseTemplates()) {
            Output o = execute(t, ordinary(t));
            if (o.status() != 200) {
                faults.add(t.name() + ": HTTP " + o.status());
                continue;
            }
            if (o.details().isEmpty() || readable(o.details().get(0)).isBlank()) faults.add(t.name() + ": no details");
            if (o.navtex() == null || o.navtex().isBlank()) faults.add(t.name() + ": no NAVTEX");
            if (o.title() == null || o.title().isBlank()) faults.add(t.name() + ": no title");
        }
        assertEquals(List.of(), faults);
    }

    /**
     * Every NAVTEX line any template gives in its whole matrix is at most 40 characters long, and the check sees every
     * template: each one gives a NAVTEX text in every case that prints a message (a case with details). Every base
     * template prints NAVTEX ({@link #everyTemplate_givesATitleDetailsAndNavtex_forTheOrdinaryCase}), and the golden
     * files hold no case with details and no NAVTEX text; the one case without NAVTEX, DGPS with no station, prints no
     * details either.
     */
    @Test
    void everyNavtexLine_isAtMostFortyCharacters() {
        List<String> faults = new ArrayList<>();
        List<String> withoutNavtex = new ArrayList<>();
        List<TemplateDef> base = baseTemplates();
        int texts = 0;
        for (TemplateDef t : base) {
            int textsOfTemplate = 0;
            for (Case c : cases(t)) {
                Output o = execute(t, c);
                if (o.navtex() == null || o.navtex().isBlank()) {
                    if (printsDetails(o)) withoutNavtex.add(t.name() + " / " + c.label);
                    continue;
                }
                texts++;
                textsOfTemplate++;
                for (String line : o.navtex().split("\n")) {
                    if (line.length() > 40) faults.add(t.name() + " / " + c.label + ": " + line);
                }
            }
            if (textsOfTemplate == 0) withoutNavtex.add(t.name() + ": no case gave a NAVTEX text");
        }
        // Without this the loop passes when no case gives a NAVTEX text at all (a NAVTEX type switched off, a
        // promulgation the execute endpoint no longer returns): every template's ordinary case gives one
        assertTrue(base.size() >= 36 && texts >= base.size(), base.size() + " templates gave " + texts + " NAVTEX texts");
        // Per template, not only a total: a template whose cases stopped giving a NAVTEX text would otherwise leave
        // the line check blind to it while the others kept the total up
        assertEquals(List.of(), withoutNavtex, "a case that prints a message and gives no NAVTEX text");
        assertEquals(List.of(), faults);
    }

    /** Whether a case printed a message: it executed and its first details are not empty */
    private static boolean printsDetails(Output o) {
        if (o.status() != 200 || o.details().isEmpty()) return false;
        String details = readable(o.details().get(0));
        return details != null && !details.isBlank();
    }

    private Output run(String template, Consumer<Case> change) {
        TemplateDef t = template(template);
        Case c = ordinary(t).copy("single");
        change.accept(c);
        Output o = execute(t, c);
        assertEquals(200, o.status(), template + ": " + o.error());
        return o;
    }

    private static String details(Output o) {
        return readable(o.details().get(0));
    }

    @Test
    void lightUnlit_printsThePositionInTheTemplatesForm_andTheNameAndType() {
        Output o = run("Light unlit", c -> {});
        assertTrue(details(o).contains("55° 30.0'N - 010° 30.0'E"), details(o));
        assertTrue(details(o).contains("Hals Barre"), details(o));
        assertTrue(details(o).endsWith("is unlit. /"), details(o));
        assertTrue(o.navtex().contains("55-30.0N 010-30.0E"), o.navtex());
        assertTrue(o.navtex().contains("UNLIT"), o.navtex());
    }

    @Test
    void aPositionJustBelowAWholeDegree_isPrintedAsTheWholeDegree() {
        Output o = run("Light unlit", c -> c.features = List.of(point(P_JUST_BELOW)));
        assertTrue(details(o).contains("56° 00.0'N - 012° 00.0'E"), details(o));
        assertTrue(o.navtex().contains("56-00.0N 012-00.0E"), o.navtex());
    }

    @Test
    void aNegativeLongitude_isPrintedWest() {
        Output o = run("Wreck", c -> c.features = List.of(point(P_WEST)));
        assertTrue(details(o).contains("61° 06.0'N - 045° 15.0'W"), details(o));
        assertTrue(o.navtex().contains("61-06.0N 045-15.0W"), o.navtex());
    }

    @Test
    void anAtonTemplate_givesOneLinePerPosition_inItsOrder() {
        Output o = run("Buoy missing", c -> c.features = List.of(point(P_THREE[0]), point(P_THREE[1]), point(P_THREE[2])));
        String d = details(o);
        int first = d.indexOf("55° 30.0'N"), second = d.indexOf("55° 36.0'N"), third = d.indexOf("55° 42.0'N");
        assertTrue(first >= 0 && first < second && second < third, d);
        assertEquals(3, d.split("is missing").length - 1, d);
    }

    @Test
    void anEventDateAcrossTheClockChange_printsBothEndsInTheirOwnLocalTime() {
        Output o = run("Hydrographic survey", c -> c.eventDates = eventDateVariants()
                .get("across the spring clock change (28 Mar 22:00 CET - 29 Mar 04:00 CEST)"));
        assertTrue(details(o).contains("2200"), details(o));
        assertTrue(details(o).contains("0400"), details(o));
    }

    // ------------------------------------------------------------------------------------------- //
    // The faults the reading found
    // ------------------------------------------------------------------------------------------- //

    /**
     * TMPL8 (2026-11-12): a reduced depth whose water depth was left empty (Mandatory is not enforced)
     * printed "Water depths down to 0m" and "WATER DEPTHS DOWN TO 0M", a depth nobody entered, through
     * {@code ${params.water_depth!0?c}}. Without a depth the texts now say only what the editor knows: "Reduced water
     * depths have been observed" and "REDUCED WATER DEPTHS OBSERVED", with no number.
     */
    @Test
    void reducedDepth_withoutAWaterDepth_printsNoDepth() {
        Output o = run("Reduced depth", c -> c.params.remove("water_depth"));
        assertTrue(details(o).startsWith("Reduced water depths have been observed in pos."), details(o));
        assertFalse(details(o).contains("0m"), details(o));
        assertFalse(details(o).contains("down to"), details(o));
        assertTrue(o.navtex().contains("REDUCED WATER DEPTHS OBSERVED"), o.navtex());
        assertFalse(o.navtex().contains("0M"), o.navtex());
        assertFalse(o.navtex().contains("DOWN TO"), o.navtex());
    }

    /** TMPL8's control: a depth that was entered is printed, in the details and in the NAVTEX text */
    @Test
    void reducedDepth_withAWaterDepth_printsIt() {
        Output o = run("Reduced depth", c -> c.params.put("water_depth", 7));
        assertTrue(details(o).startsWith("Water depths down to 7m have been observed in pos."), details(o));
        assertTrue(o.navtex().contains("WATER DEPTHS DOWN TO 7M OBSERVED"), o.navtex());
        assertFalse(o.navtex().contains("REDUCED"), o.navtex());
    }

    /**
     * TMPL8's text cases: a caller of the template execution other than the admin frontend may send the depth as text.
     * "7" prints as 7m / 7M with no quote mark (FreeMarker's ?c would quote a string), and an empty or blank text counts
     * as no depth entered, so no depth is printed.
     */
    @Test
    void reducedDepth_withAWaterDepthAsText_printsItUnquoted_andAnEmptyTextPrintsNoDepth() {
        Output asText = run("Reduced depth", c -> c.params.put("water_depth", "7"));
        assertTrue(details(asText).startsWith("Water depths down to 7m have been observed in pos."), details(asText));
        assertFalse(details(asText).contains("\""), details(asText));
        assertTrue(asText.navtex().contains("WATER DEPTHS DOWN TO 7M OBSERVED"), asText.navtex());
        assertFalse(asText.navtex().contains("\""), asText.navtex());

        for (String empty : new String[] {"", "  "}) {
            Output o = run("Reduced depth", c -> c.params.put("water_depth", empty));
            assertTrue(details(o).startsWith("Reduced water depths have been observed in pos."), details(o));
            assertFalse(details(o).contains("down to"), details(o));
            assertFalse(details(o).contains("\""), details(o));
            assertTrue(o.navtex().contains("REDUCED WATER DEPTHS OBSERVED"), o.navtex());
            assertFalse(o.navtex().contains("DOWN TO"), o.navtex());
            assertFalse(o.navtex().contains("\""), o.navtex());
        }
    }

    /**
     * TMPL9 (2026-11-12): radio-navigation.ftl serves both the RACON and the AIS category, and decided
     * between them by whether the position carried an {@code ais_status}; the status is optional, and an AIS warning
     * without one said "RACON" in its subject, details and NAVTEX text. The category's own {@code ais} parameter now
     * decides: the AIS template prints AIS whatever its status.
     */
    @Test
    void ais_withoutAStatus_isPrintedAsAis() {
        Output o = run("AIS", c -> c.positionFields.remove("ais_status"));
        assertTrue(details(o).startsWith("AIS on "), details(o));
        assertFalse(details(o).contains("RACON"), details(o));
        assertTrue(o.subjects().get(0).startsWith("AIS"), o.subjects().get(0));
        assertFalse(o.subjects().get(0).contains("RACON"), o.subjects().get(0));
        assertTrue(o.navtex().contains("AIS ON"), o.navtex());
        assertFalse(o.navtex().contains("RACON"), o.navtex());
    }

    /**
     * TMPL9's control: the category, not the status, decides; the RACON category without its status still prints
     * RACON, and never AIS
     */
    @Test
    void racon_withoutAStatus_isPrintedAsRacon() {
        Output o = run("RACON", c -> c.positionFields.remove("racon_status"));
        assertTrue(details(o).startsWith("RACON on "), details(o));
        assertFalse(details(o).contains("AIS"), details(o));
        assertTrue(o.navtex().contains("RACON ON"), o.navtex());
        assertFalse(o.navtex().contains("AIS"), o.navtex());
    }

    /**
     * TMPL10 (2026-11-12): a template whose positions come from the part's geometry
     * ({@code renderPositionList geomParam=part}) printed "in pos." with nothing after it when the message had no
     * position: "A vessel has sunk in pos.." (wreck, uncharted obstruction, mine, torpedo, grenade, exercise, the
     * work templates, bridge closed). {@code common.ftl} now prints the position phrase only for one position or
     * several: without a position the sentence goes on without it ("A vessel has sunk.", "The bridge ... is closed").
     * The control: one position and several still print their phrase.
     */
    @Test
    void aPartTemplate_withoutAPosition_printsNoPositionPhrase() {
        Output o = run("Wreck", c -> c.features = List.of());
        assertTrue(details(o).startsWith("A vessel has sunk."), details(o));
        assertFalse(details(o).contains("in pos"), details(o));
        assertFalse(DOUBLE_STOP.matcher(details(o)).find(), details(o));
        Output h = run("Hydrographic survey", c -> c.features = List.of());
        assertTrue(details(h).contains("hydrographic survey is carried out."), details(h));
        assertFalse(details(h).contains("in pos"), details(h));
        Output b = run("Bridge closed for passage", c -> c.features = List.of());
        assertTrue(details(b).contains("Barre&laquo; is closed for passage"), details(b));
        assertFalse(details(b).contains("in pos"), details(b));

        Output one = run("Wreck", c -> {});
        assertTrue(details(one).startsWith("A vessel has sunk in pos. 55° 30.0'N - 010° 30.0'E."), details(one));
        Output three = run("Wreck", c -> c.features = List.of(point(P_THREE[0]), point(P_THREE[1]), point(P_THREE[2])));
        assertTrue(details(three).startsWith("A vessel has sunk between pos. 55° 30.0'N"), details(three));
    }

    /**
     * DICT4 (2026-11-12): a date interval within one day prints no Danish "kl." in the ENGLISH
     * details of a deployment whose first model language is Danish, as DK's and GL's are and as this suite's is
     * ({@code niord.json}: {@code modelLanguages} [da, en]). The English value of
     * {@code navwarn.date.time_interval_prefix} is empty on purpose (bootstrap {@code texts.json},
     * {@code defaults/base/texts/en/template.properties}), and {@code DictionaryVo.localizedValue} keeps an empty text
     * of the requested language instead of falling back to the default language's: "The 12th May 2026 from 1000 to
     * 1400".
     */
    @Test
    void anIntervalWithinADay_printsNoKl_inTheEnglishDetails() {
        Output o = run("Hydrographic survey", c -> {});
        assertTrue(details(o).contains("May 2026 from 1000 to 1400"), details(o));
        assertFalse(details(o).contains("kl."), details(o));
        Output e = run("Exercise", c -> {});
        assertTrue(details(e).contains("May 2026 from 1000 to 1400"), details(e));
        assertFalse(details(e).contains("kl."), details(e));
    }

    /**
     * TMPL15 (2026-11-12): radio-navigation.ftl and object-adrift.ftl wrote their subject over two
     * lines of the field template, and the line break stayed in the subject and in the title made from it: "AIS\n
     * unreliable." and "Object\nadrift"; the title read "Denmark. The Baltic Sea. AIS\nunreliable.". Everywhere the
     * title is plain text (a mail's subject, the NAVTEX and Twitter titles, a PDF's bookmarks, a list's one-line
     * title) it was broken in two. Now each subject is one line, and several positions are joined by a space.
     */
    @Test
    void theAisAndObjectAdriftSubjects_areOneLine() {
        Output ais = run("AIS", c -> {});
        assertEquals("AIS unreliable.", ais.subjects().get(0));
        assertFalse(ais.title().contains("\n"), ais.title());
        assertTrue(ais.title().endsWith(" AIS unreliable."), ais.title());

        Output threeAis = run("AIS", c -> c.features = List.of(point(P_THREE[0]), point(P_THREE[1]), point(P_THREE[2])));
        assertEquals("AIS unreliable. AIS unreliable. AIS unreliable.", threeAis.subjects().get(0));
        assertFalse(threeAis.title().contains("\n"), threeAis.title());

        Output racon = run("RACON", c -> {});
        assertEquals("RACON unreliable.", racon.subjects().get(0));
        assertFalse(racon.title().contains("\n"), racon.title());

        Output adrift = run("Object adrift", c -> {});
        assertEquals("Object adrift", adrift.subjects().get(0));
        assertFalse(adrift.title().contains("\n"), adrift.title());
        assertTrue(adrift.title().endsWith(" Object adrift."), adrift.title());
    }

    /**
     * TMPL15's controls: an AIS warning without a status keeps "AIS." as its subject (no stray space before the
     * full stop), and an object chosen from the list names it on the same line: "Logs adrift"
     */
    @Test
    void theOneLineSubjects_keepTheirWords() {
        Output noStatus = run("AIS", c -> c.positionFields.remove("ais_status"));
        assertEquals("AIS.", noStatus.subjects().get(0));
        Output logs = run("Object adrift", c -> c.params.put("object", listValue("object_adrift_list", "object.adrift.logs")));
        assertEquals("Logs adrift", logs.subjects().get(0));
        assertTrue(logs.title().endsWith(" Logs adrift."), logs.title());
    }

    /**
     * TMPL14 (2026-11-12), cosmetic English and NAVTEX forms the reading found: the AtoN type was
     * capitalised mid-sentence ("AIS on The light Hals Barre"); an uncharted obstruction without a type was "An
     * uncharted Obstruction ... Obstruction is unmarked"; the category's name was lower case, so its title ended
     * "light destroyed."; reduced-depth.ftl's NAVTEX text ended "MARINERS ADVISED TO KEEP CLEAR" without the full stop
     * the other templates give; a marking at distance 0 read "buoyed with a green buoy of the position"; a depth of
     * 1250 m was printed "1,250M" in the NAVTEX text, where a comma reads as a decimal sign, and so were an obstruction
     * depth, a marking's distance and a work vessel's minimum distance. Now: "AIS on the light", "An uncharted
     * obstruction ... The obstruction is unmarked", "Light destroyed.", "KEEP CLEAR.", "of the position" only with a
     * distance over 0 or a bearing, and numbers without a grouping comma.
     */
    @Test
    void englishAndNavtexForms_areRight() {
        Output ais = run("AIS", c -> {});
        assertTrue(details(ais).startsWith("AIS on the light"), details(ais));
        Output obstruction = run("Uncharted obstruction", c -> c.params.remove("obstruction_type"));
        assertTrue(details(obstruction).contains("An uncharted obstruction"), details(obstruction));
        assertTrue(details(obstruction).contains(" The obstruction is unmarked"), details(obstruction));
        assertFalse(details(obstruction).contains("Obstruction"), details(obstruction));
        Output destroyed = run("Light destroyed", c -> {});
        assertTrue(destroyed.title().endsWith(" Light destroyed."), destroyed.title());
        Output reduced = run("Reduced depth", c -> {});
        assertTrue(reduced.navtex().trim().endsWith("MARINERS ADVISED TO KEEP CLEAR."), reduced.navtex());

        Map<String, Object> green = new LinkedHashMap<>();
        green.put("type", listValue("marking_type_list", "aton.type.buoy.green"));
        green.put("distance", 0);
        Output marked = run("Wreck", c -> {
            c.params.put("markingType", "buoy");
            c.params.put("markings", List.of(green));
        });
        assertFalse(details(marked).contains("of the position"), details(marked));
        assertTrue(details(marked).contains("buoyed with a green buoy."), details(marked));

        Output deep = run("Wreck", c -> c.params.put("wreck_depth", 1250));
        assertTrue(deep.navtex().contains("1250M"), deep.navtex());
        assertFalse(deep.navtex().contains("1,250"), deep.navtex());
        assertTrue(details(deep).contains("The depth above the wreck is 1250m."), details(deep));
        Output deepObstruction = run("Uncharted obstruction", c -> {
            c.params.put("obstruction_visible", false);
            c.params.put("obstruction_depth", 1250);
        });
        assertTrue(deepObstruction.navtex().contains("1250M"), deepObstruction.navtex());
        assertTrue(details(deepObstruction).contains("is 1250m."), details(deepObstruction));
        Output keepAway = run("Hydrographic survey", c -> c.params.put("vessel",
                vessel("Hals", "OXYZ", false, false, null, 1000, "m")));
        assertTrue(keepAway.navtex().replace('\n', ' ').contains("KEEPING MINIMUM DISTANCE 1000M"), keepAway.navtex());
        assertTrue(details(keepAway).contains("keep a minimum distance of 1000m"), details(keepAway));
    }

    /**
     * TMPL14's controls: a sentence that starts with the AtoN type still capitalises it; a marking with a distance
     * over 0 or with a bearing still reads "of the position", the distance without a grouping comma; a depth given as
     * text (a caller other than the admin app) prints as it was typed, unquoted.
     */
    @Test
    void englishAndNavtexForms_keepWhatWasRight() {
        Output established = run("Buoy established", c -> {});
        assertTrue(Character.isUpperCase(details(established).charAt(0)), details(established));

        Map<String, Object> far = new LinkedHashMap<>();
        far.put("type", listValue("marking_type_list", "aton.type.buoy.green"));
        far.put("distance", 1250);
        Output distant = run("Wreck", c -> {
            c.params.put("markingType", "buoy");
            c.params.put("markings", List.of(far));
        });
        assertTrue(details(distant).contains("approx. 1250m of the position."), details(distant));
        Map<String, Object> bearing = new LinkedHashMap<>();
        bearing.put("type", listValue("marking_type_list", "aton.type.buoy.green"));
        bearing.put("bearing", listValue("marking_bearing", "aton.bearing.n"));
        Output north = run("Wreck", c -> {
            c.params.put("markingType", "buoy");
            c.params.put("markings", List.of(bearing));
        });
        assertTrue(details(north).contains("of the position."), details(north));

        Output asText = run("Wreck", c -> c.params.put("wreck_depth", "12"));
        assertTrue(details(asText).contains("The depth above the wreck is 12m."), details(asText));
        assertTrue(asText.navtex().replace('\n', ' ').contains("DEPTH ABOVE WRECK 12M."), asText.navtex());
        assertFalse(asText.navtex().contains("\""), asText.navtex());
    }

    /**
     * The NAVTEX promulgation type's own script, {@code navtex.ftl}, which the bootstrap ships but does not link to the
     * NAVTEX type (a deployment links it): linked for this test, it puts the area lineage at the top of the NAVTEX text
     * and the cancellation lines at the bottom. Its output is pinned in {@code golden/templates/promulgation-navtex.txt}.
     */
    @Test
    void theNavtexTypeScript_addsTheHeaderAndTheCancellationLines() throws IOException {
        Integer navtexType = Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT id FROM public.\"PromulgationType\" WHERE \"typeId\" = 'navtex'").getSingleResult()).intValue());
        Tx.requiringNew().run(() -> em.createNativeQuery("INSERT INTO public.\"PromulgationType_scriptResourcePaths\""
                        + " (\"PromulgationType_id\", \"scriptResourcePaths\", \"indexNo\") VALUES (?1, 'templates/tmpl/navtex.ftl', 0)")
                .setParameter(1, navtexType).executeUpdate());
        try {
            StringBuilder sb = new StringBuilder("# navtex.ftl linked to the NAVTEX type, for the Wreck template\n\n");
            TemplateDef wreck = template("Wreck");
            Case plain = ordinary(wreck).copy("ordinary, no publication end, no reference");
            Case full = ordinary(wreck).copy("a publication end (31 Dec 23:30 local) and a cancellation reference");
            full.messageFields.put("publishDateTo", at("2026-12-31T22:30:00Z"));
            full.messageFields.put("references", List.of(Map.of("messageId", "NW-001-26", "type", "CANCELLATION"),
                    Map.of("messageId", "NW-002-26", "type", "REFERENCE")));
            for (Case c : List.of(plain, full)) {
                sb.append(render(c.label, execute(wreck, c)));
            }
            String actual = sb.toString();
            if (Boolean.getBoolean("golden.write")) {
                Files.writeString(Paths.get("src/test/resources/golden/templates/promulgation-navtex.txt"), actual,
                        StandardCharsets.UTF_8);
            } else {
                assertEquals(readGolden("promulgation-navtex.txt"), actual);
            }
        } finally {
            Tx.requiringNew().run(() -> em.createNativeQuery("DELETE FROM public.\"PromulgationType_scriptResourcePaths\""
                    + " WHERE \"PromulgationType_id\" = ?1 AND \"scriptResourcePaths\" = 'templates/tmpl/navtex.ftl'")
                    .setParameter(1, navtexType).executeUpdate());
        }
    }

    /**
     * TMPL11 (2026-11-12): work-vessel.ftl printed the vessel's name as "???" when only a call sign
     * was given: "Work is carried out by «???» call sign OXYZ." Without a name the vessel is now named by its call
     * sign: "Work is carried out by the vessel with call sign OXYZ.", "BY VESSEL WITH CALL SIGN OXYZ.". The control: a
     * vessel with a name and a call sign is still named and quoted.
     */
    @Test
    void aWorkVessel_withACallSignOnly_isNamedByItsCallSign() {
        Output o = run("Hydrographic survey", c -> c.params.put("vessel", vessel(null, "OXYZ", false, false, null, null, null)));
        assertTrue(details(o).contains("Work is carried out by the vessel with call sign OXYZ."), details(o));
        assertFalse(details(o).contains("???"), details(o));
        String navtex = o.navtex().replace('\n', ' ');
        assertTrue(navtex.contains("BY VESSEL WITH CALL SIGN OXYZ."), o.navtex());
        assertFalse(navtex.contains("???"), o.navtex());

        Output named = run("Hydrographic survey", c -> {});
        assertTrue(details(named).contains("Work is carried out by &raquo;Poseidon&laquo; call sign OXYZ."), details(named));
        assertTrue(named.navtex().replace('\n', ' ').contains("BY 'POSEIDON' CALL SIGN OXYZ."), named.navtex());
    }

    /**
     * TMPL12 (2026-11-12): obstruction-found.ftl (mine, torpedo, grenade) put the plural object in
     * front of a singular verb when there were several positions: "Mines has been reported in pos. ...". The torpedo
     * template's plural was "Torpedos" and the mine template's "The Mines" was capitalised mid-text. object-adrift.ftl
     * did the same for its plural objects: "Logs has been observed adrift", "Fishing stakes has been observed adrift".
     * The verb now follows the number; the control: one mine and one adrift object keep "has".
     */
    @Test
    void pluralObjects_takeAPluralVerb() {
        Output o = run("Mine", c -> c.features = List.of(point(P_THREE[0]), point(P_THREE[1]), point(P_THREE[2])));
        assertTrue(details(o).startsWith("Mines have been reported"), details(o));
        assertTrue(details(o).contains("The mines may be dangerous."), details(o));
        Output t = run("Torpedo", c -> c.features = List.of(point(P_THREE[0]), point(P_THREE[1])));
        assertTrue(details(t).startsWith("Torpedoes have been reported"), details(t));
        assertTrue(details(t).contains("The torpedoes may be dangerous."), details(t));
        assertTrue(t.navtex().contains("TORPEDOES REPORTED FOUND"), t.navtex());
        Output g = run("Grenade", c -> c.features = List.of(point(P_THREE[0]), point(P_THREE[1])));
        assertTrue(details(g).startsWith("Grenades have been reported"), details(g));
        Output logs = run("Object adrift", c -> c.params.put("object", listValue("object_adrift_list", "object.adrift.logs")));
        assertTrue(details(logs).startsWith("Logs have been observed adrift"), details(logs));
        Output stakes = run("Object adrift",
                c -> c.params.put("object", listValue("object_adrift_list", "object.adrift.fishing_stakes")));
        assertTrue(details(stakes).startsWith("Fishing stakes have been observed adrift"), details(stakes));

        Output one = run("Mine", c -> {});
        assertTrue(details(one).startsWith("A mine has been reported"), details(one));
        Output boat = run("Object adrift", c -> c.params.put("object", listValue("object_adrift_list", "object.adrift.boat")));
        assertTrue(details(boat).startsWith("A boat has been observed adrift"), details(boat));
        Output nothing = run("Object adrift", c -> c.params.remove("object"));
        assertTrue(details(nothing).startsWith("An object has been observed adrift"), details(nothing));
    }

    /**
     * TMPL13 (2026-11-12): salvage-operations.ftl printed "salvage operations is carried out" (plural
     * subject, singular verb), and the work templates (hydrographic survey, cable, diving, underwater survey,
     * salvage) and the exercise started their sentence in lower case when the message had no event dates:
     * "hydrographic survey is carried out in pos. ...", "a military exercise will take place ..."; an exercise without
     * a type had no subject at all ("will take place ..."). The text now starts with a capital when no date interval
     * precedes it, salvage takes "are", and an exercise without a type is "an exercise".
     */
    @Test
    void workTemplates_withoutEventDates_startWithACapital_andSalvageIsPlural() {
        Output o = run("Hydrographic survey", c -> c.eventDates = List.of());
        assertTrue(details(o).startsWith("Hydrographic survey is carried out in pos."), details(o));
        Output s = run("Salvage operations", c -> {});
        assertTrue(details(s).contains("2026 from 1000 to 1400 salvage operations are carried out"), details(s));
        Output sNoDates = run("Salvage operations", c -> c.eventDates = List.of());
        assertTrue(details(sNoDates).startsWith("Salvage operations are carried out"), details(sNoDates));
        Output e = run("Exercise", c -> c.eventDates = List.of());
        assertTrue(details(e).startsWith("A military exercise will take place"), details(e));
        Output eNoType = run("Exercise", c -> {
            c.eventDates = List.of();
            c.params.remove("exercise_type");
        });
        assertTrue(details(eNoType).startsWith("An exercise will take place in pos."), details(eNoType));
        Output eNoTypeWithDates = run("Exercise", c -> c.params.remove("exercise_type"));
        assertTrue(details(eNoTypeWithDates).contains("2026 from 1000 to 1400 an exercise will take place"),
                details(eNoTypeWithDates));
        for (String work : List.of("Hydrographic survey", "Cable operations", "Diving operations", "Underwater survey",
                "Salvage operations", "Exercise")) {
            Output w = run(work, c -> c.eventDates = List.of());
            assertFalse(lint(w).contains("details: a sentence that starts in lower case"), work + ": " + details(w));
        }
    }
}
