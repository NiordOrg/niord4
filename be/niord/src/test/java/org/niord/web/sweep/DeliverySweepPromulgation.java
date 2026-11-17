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
package org.niord.web.sweep;

import io.restassured.path.json.JsonPath;
import jakarta.persistence.EntityManager;
import org.niord.core.promulgation.TwitterPromulgationService;
import org.niord.core.promulgation.TwitterSettings;
import org.niord.test.Tx;

import java.util.List;
import java.util.Map;

import static org.niord.web.sweep.DeliverySweepFixture.*;

/**
 * The cases of the promulgation handlers for {@link DeliverySweepGroup}: the types ({@code PromulgationRestService}),
 * the NAVTEX transmitters, the SafetyNET areas and the Twitter settings. Every write makes a type, a transmitter or an
 * area of its own and removes it; a type is removed only once it has nothing left (PROM8).
 */
final class DeliverySweepPromulgation {

    private DeliverySweepPromulgation() {
    }

    static void cases(SweepCases cases) {
        typeCases(cases);
        navtexCases(cases);
        safetyNetCases(cases);
        twitterCases(cases);
    }

    // ------------------------------------------------------------------------------------------- //
    // Promulgation types
    // ------------------------------------------------------------------------------------------- //

    private static void typeCases(SweepCases cases) {
        cases.add("getPromulgationServices", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/promulgations/promulgation-services/all"))
                .sweep((ctx, as) -> {
                    SweepResponse services = ctx.ok(as, SweepRequest.get("/rest/promulgations/promulgation-services/all"));
                    ctx.check(services.text().contains("\"navtex\"") && services.text().contains("\"safetynet\""),
                            "the services hold navtex and safetynet", services);
                }));

        cases.add("getPromulgationTypes", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/promulgations/promulgation-types/all"))
                .as("sysadmin"));

        cases.add("getPromulgationType", SweepCase
                .of((ctx, row) -> promulgationType(NAVTEX))
                .probes((ctx, as) -> ctx.call(as.name(), promulgationType(ctx.name("no-such-type"))
                        .note("unknown-id probe"), Cell.status(404, "No such promulgation type: "
                        + ctx.name("no-such-type")))));

        cases.add("createPromulgationType", SweepCase
                // A refused cell sends a type without an id: a gate that let it through answers 400
                .of((ctx, row) -> SweepRequest.post("/rest/promulgations/promulgation-type")
                        .json(typeBody(null, "navtex", ctx.name("refused"), 902, List.of(DOMAIN))))
                .sweep((ctx, as) -> createTypeReadBackAndRemove(ctx, as, "/rest/promulgations/promulgation-type"))
                .variants((ctx, as) -> createTypeReadBackAndRemove(ctx, as, "/rest/promulgations/promulgation-type/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/promulgations/promulgation-type").json(Map.of())
                            .note("empty body"), Cell.status(400, "A promulgation type needs a type ID"));
                    ctx.call(as.name(), SweepRequest.post("/rest/promulgations/promulgation-type")
                            .json(typeBody(ctx.name("no-service"), null, "x", 902, List.of(DOMAIN))).note("no service"),
                            Cell.status(400, "needs a service ID"));
                    ctx.call(as.name(), SweepRequest.post("/rest/promulgations/promulgation-type")
                            .json(typeBody(ctx.name("no-name"), "navtex", null, 902, List.of(DOMAIN))).note("no name"),
                            Cell.status(400, "needs a name"));
                }));

        cases.add("updatePromulgationType", SweepCase
                // The body's id is not the path's: a gate that let it through answers 400 and changes nothing
                .of((ctx, row) -> SweepRequest.put("/rest/promulgations/promulgation-type/{typeId}")
                        .var("typeId", NAVTEX).json(typeBody(ctx.name("other"), "navtex", NAVTEX, 901, List.of(DOMAIN))))
                .sweep((ctx, as) -> {
                    String id = createType(ctx, as, "/rest/promulgations/promulgation-type");
                    if (id == null) {
                        return;
                    }
                    try {
                        Map<String, Object> changed = typeBody(id, "navtex", id + "-renamed", 903,
                                List.of(DOMAIN, SweepIdentity.OTHER_DOMAIN));
                        changed.put("requirement", "DEFAULT");
                        ctx.ok(as, SweepRequest.put("/rest/promulgations/promulgation-type/{typeId}").var("typeId", id)
                                .json(changed));
                        readTypeBack(ctx, id, id + "-renamed", 903, "DEFAULT", List.of(DOMAIN, SweepIdentity.OTHER_DOMAIN));
                    } finally {
                        removeTypeAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/promulgations/promulgation-type/{typeId}")
                        .var("typeId", NAVTEX).json(typeBody(ctx.name("other"), "navtex", NAVTEX, 901, List.of(DOMAIN)))
                        .note("the body's id is not the path's"), Cell.status(400, null))));

        cases.add("deletePromulgationType", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/promulgations/promulgation-type/{typeId}")
                        .var("typeId", ctx.name("no-such-type")))
                .sweep((ctx, as) -> {
                    String id = createType(ctx, as, "/rest/promulgations/promulgation-type");
                    if (id != null) {
                        removeTypeAndReadGone(ctx, as, id);
                    }
                })
                .variants((ctx, as) -> {
                    // PROM8: the fixture's Twitter type has settings, which no endpoint removes; the fix the record
                    // proposes deletes them with the type (the tear-down then finds the type gone)
                    ctx.call(as.name(), SweepRequest.delete("/rest/promulgations/promulgation-type/{typeId}")
                            .var("typeId", TWITTER).note("a type with Twitter settings"), Cell.status(204, null));
                }));

        cases.add("importPromulgationTypes", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/promulgations/upload-promulgation-types")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String id = ctx.name("uploaded-" + ctx.unique());
                    String json = "[{\"typeId\":\"" + id + "\",\"serviceId\":\"navtex\",\"name\":\"" + id
                            + "\",\"active\":false,\"requirement\":\"OPTIONAL\",\"priority\":904,\"language\":\"en\","
                            + "\"domains\":[{\"domainId\":\"" + DOMAIN + "\"}]}]";
                    String file = ctx.jobs().upload(ctx, as.name(),
                            SweepRequest.post("/rest/promulgations/upload-promulgation-types"), json);
                    ctx.jobs().awaitClean(ctx, "promulgation-type-import", file);
                    SweepResponse read = ctx.send("sysadmin", promulgationType(id));
                    if (!read.is2xx()) {
                        ctx.fail(read, "the uploaded promulgation type " + id + " is found after its job completed");
                        return;
                    }
                    try {
                        readTypeBack(ctx, id, id, 904, "OPTIONAL", List.of(DOMAIN));
                    } finally {
                        removeTypeAndReadGone(ctx, as, id);
                    }
                }));

        cases.add("getPublicPromulgationTypes", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/promulgations/public-promulgation-type/{typeIds}")
                        .var("typeIds", NAVTEX + "," + SAFETYNET))
                .sweep((ctx, as) -> {
                    SweepResponse types = ctx.ok(as, SweepRequest.get(
                            "/rest/promulgations/public-promulgation-type/{typeIds}").var("typeIds", NAVTEX + "," + SAFETYNET));
                    ctx.check(!types.text().contains("serviceId"), "the public projection holds no service id", types);
                }));

        cases.add("searchPublicPromulgationTypes", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/promulgations/search-public-promulgation-type")
                        .query("type", "sweep-delivery-")));

        cases.add("generateMessagePromulgation", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/promulgations/generate/{typeId}").var("typeId", NAVTEX)
                        .json(messageToGenerateFrom()))
                .sweep((ctx, as) -> {
                    SweepResponse navtex = ctx.ok(as, SweepRequest.post("/rest/promulgations/generate/{typeId}")
                            .var("typeId", NAVTEX).json(messageToGenerateFrom()));
                    if (!navtex.is2xx()) {
                        return;
                    }
                    ctx.checkEquals(NAVTEX, navtex.json().getString("type.typeId"), "the generated promulgation's type",
                            navtex);
                    ctx.checkEquals(false, navtex.json().getBoolean("transmitters.'" + TRANSMITTER + "'"),
                            "the type's active transmitter is offered, not ticked", navtex);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/promulgations/generate/{typeId}")
                            .var("typeId", ctx.name("no-such-type")).json(messageToGenerateFrom())
                            .note("unknown type"), Cell.status(400, "No promulgation type with the ID "
                            + ctx.name("no-such-type")));
                    ctx.call(as.name(), SweepRequest.post("/rest/promulgations/generate/{typeId}")
                            .var("typeId", NAVTEX).json(Map.of("parts", List.of(Map.of("descs",
                                    List.of(Map.of("details", "x")))))).note("a description without a language"),
                            Cell.status(400, "Each description of a message part must name its language"));
                }));
    }

    static Map<String, Object> messageToGenerateFrom() {
        return Map.of("mainType", "NW", "type", "COASTAL_WARNING", "status", "DRAFT",
                "parts", List.of(Map.of("type", "DETAILS", "descs", List.of(Map.of("lang", "en",
                        "details", "<p>Sweep delivery: a buoy is off station.</p>")))));
    }

    private static String createType(SweepContext ctx, SweepIdentity as, String pattern) {
        String id = ctx.name("type-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(typeBody(id, "navtex", id, 902,
                List.of(DOMAIN))));
        return created.is2xx() ? id : null;
    }

    private static void createTypeReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        String id = createType(ctx, as, pattern);
        if (id == null) {
            return;
        }
        try {
            readTypeBack(ctx, id, id, 902, "OPTIONAL", List.of(DOMAIN));
        } finally {
            removeTypeAndReadGone(ctx, as, id);
        }
    }

    private static void readTypeBack(SweepContext ctx, String id, String name, int priority, String requirement,
                                     List<String> domains) {
        SweepResponse read = ctx.ok("editor", promulgationType(id));
        if (!read.is2xx()) {
            return;
        }
        JsonPath json = read.json();
        ctx.checkEquals(id, json.getString("typeId"), "the type id", read);
        ctx.checkEquals("navtex", json.getString("serviceId"), "the service id", read);
        ctx.checkEquals(name, json.getString("name"), "the name", read);
        ctx.checkEquals(false, json.getBoolean("active"), "active", read);
        ctx.checkEquals(priority, json.getInt("priority"), "the priority", read);
        ctx.checkEquals(requirement, json.getString("requirement"), "the requirement", read);
        ctx.checkEquals("en", json.getString("language"), "the language", read);
        ctx.checkEquals(domains, json.getList("domains.domainId"), "the domains", read);
    }

    private static void removeTypeAndReadGone(SweepContext ctx, SweepIdentity as, String id) {
        ctx.ok(as, SweepRequest.delete("/rest/promulgations/promulgation-type/{typeId}").var("typeId", id));
        ctx.call("sysadmin", promulgationType(id).note("read after the delete"),
                Cell.status(404, "No such promulgation type: " + id));
    }

    // ------------------------------------------------------------------------------------------- //
    // NAVTEX transmitters
    // ------------------------------------------------------------------------------------------- //

    private static void navtexCases(SweepCases cases) {
        cases.add("reformatNavtex", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/promulgation/navtex/reformat-navtex")
                        .json(reformatBody(NAVTEX_VO, LONG_TEXT)))
                .sweep((ctx, as) -> reformatted(ctx, as, "/rest/promulgation/navtex/reformat-navtex", NAVTEX_VO))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/promulgation/navtex/reformat-navtex")
                        .json(reformatBody(NAVTEX_VO, "x".repeat(20_001))).note("a text over the limit"),
                        Cell.status(400, "The text is longer than 20000 characters"))));

        cases.add("getTransmitters", SweepCase
                .of((ctx, row) -> transmitters(NAVTEX))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, transmitters(NAVTEX).query("lang", "da")), null,
                        TRANSMITTER)));

        cases.add("createTransmitter", SweepCase
                // A refused cell sends a transmitter without a name: a gate that let it through answers 400
                .of((ctx, row) -> transmitter(NAVTEX).json(transmitterBody(NAVTEX, null, true)))
                .sweep((ctx, as) -> createTransmitterReadBackAndRemove(ctx, as,
                        "/rest/promulgation/navtex/transmitters/{typeId}/transmitter"))
                .variants((ctx, as) -> createTransmitterReadBackAndRemove(ctx, as,
                        "/rest/promulgation/navtex/transmitters/{typeId}/transmitter/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), transmitter(NAVTEX).json(transmitterBody(NAVTEX, null, true)).note("no name"),
                            Cell.status(400, "A transmitter needs a name"));
                    ctx.call(as.name(), transmitter(NAVTEX).json(Map.of("name", ctx.name("x"))).note("no type"),
                            Cell.status(400, "A transmitter must name its promulgation type"));
                }));

        cases.add("updateTransmitter", SweepCase
                // The body names another transmitter: a gate that let it through answers 400
                .of((ctx, row) -> transmitterAt(SweepRequest::put, TRANSMITTER)
                        .json(transmitterBody(NAVTEX, ctx.name("other"), true)))
                .sweep((ctx, as) -> {
                    String name = createTransmitter(ctx, as, "/rest/promulgation/navtex/transmitters/{typeId}/transmitter");
                    if (name == null) {
                        return;
                    }
                    try {
                        ctx.ok(as, transmitterAt(SweepRequest::put, name).json(transmitterBody(NAVTEX, name, false)));
                        readTransmitterBack(ctx, as, name, false);
                    } finally {
                        removeTransmitterAndReadGone(ctx, as, name);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), transmitterAt(SweepRequest::put, TRANSMITTER)
                        .json(transmitterBody(NAVTEX, ctx.name("other"), true)).note("the body names another"),
                        Cell.status(400, null))));

        cases.add("deleteTransmitter", SweepCase
                .of((ctx, row) -> transmitterAt(SweepRequest::delete, ctx.name("no-such-transmitter")))
                .sweep((ctx, as) -> {
                    String name = createTransmitter(ctx, as, "/rest/promulgation/navtex/transmitters/{typeId}/transmitter");
                    if (name != null) {
                        removeTransmitterAndReadGone(ctx, as, name);
                    }
                }));
    }

    static final String LONG_TEXT = "sweep delivery: the light buoy east of the fairway is off station and must be "
            + "given a wide berth\n\n\nuntil further notice";

    /** The promulgation value objects are read by their class name ({@code @class}) */
    static final String NAVTEX_VO = "org.niord.core.promulgation.vo.NavtexMessagePromulgationVo";
    static final String SAFETYNET_VO = "org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo";

    static Map<String, Object> reformatBody(String voClass, String text) {
        return map("@class", voClass, "text", text);
    }

    private static void reformatted(SweepContext ctx, SweepIdentity as, String pattern, String voClass) {
        SweepResponse answer = ctx.ok(as, SweepRequest.put(pattern).json(reformatBody(voClass, LONG_TEXT)));
        if (!answer.is2xx()) {
            return;
        }
        String text = answer.json().getString("text");
        ctx.checkEquals(text.toUpperCase(), text, "the text in upper case", answer);
        ctx.check(text.lines().allMatch(l -> l.length() <= 40) && !text.contains("\n\n"), "lines of at most 40 "
                + "characters and no blank line", answer);
    }

    private interface Verb {
        SweepRequest at(String pattern);
    }

    private static SweepRequest transmitter(String typeId) {
        return SweepRequest.post("/rest/promulgation/navtex/transmitters/{typeId}/transmitter").var("typeId", typeId);
    }

    private static SweepRequest transmitterAt(Verb verb, String name) {
        return verb.at("/rest/promulgation/navtex/transmitters/{typeId}/transmitter/{name}").var("typeId", NAVTEX)
                .var("name", name);
    }

    private static String createTransmitter(SweepContext ctx, SweepIdentity as, String pattern) {
        String name = ctx.name("transmitter-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).var("typeId", NAVTEX)
                .json(transmitterBody(NAVTEX, name, true)));
        return created.is2xx() ? name : null;
    }

    private static void createTransmitterReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        String name = createTransmitter(ctx, as, pattern);
        if (name == null) {
            return;
        }
        try {
            readTransmitterBack(ctx, as, name, true);
        } finally {
            removeTransmitterAndReadGone(ctx, as, name);
        }
    }

    private static void readTransmitterBack(SweepContext ctx, SweepIdentity as, String name, boolean active) {
        SweepResponse all = ctx.ok(as, transmitters(NAVTEX));
        String at = "find { it.name == '" + name + "' }";
        ctx.check(all.json().get(at) != null, "the transmitter " + name + " is listed", all);
        ctx.checkEquals(active, all.json().get(at + ".active"), "active", all);
        ctx.checkEquals(NAVTEX, all.json().getString(at + ".promulgationType.typeId"), "the transmitter's type", all);
        ctx.checkEquals(List.of(), all.json().getList(at + ".areas"), "the transmitter's areas", all);
    }

    private static void removeTransmitterAndReadGone(SweepContext ctx, SweepIdentity as, String name) {
        ctx.ok(as, transmitterAt(SweepRequest::delete, name));
        SweepResponse all = ctx.ok(as, transmitters(NAVTEX).note("read after the delete"));
        ctx.check(!all.json().getList("name").contains(name), "the transmitter " + name + " is gone", all);
    }

    // ------------------------------------------------------------------------------------------- //
    // SafetyNET areas
    // ------------------------------------------------------------------------------------------- //

    private static void safetyNetCases(SweepCases cases) {
        cases.add("reformatSafetyNet", SweepCase
                .of((ctx, row) -> SweepRequest.put("/rest/promulgation/safetynet/reformat-safetynet")
                        .json(reformatBody(SAFETYNET_VO, LONG_TEXT)))
                .sweep((ctx, as) -> reformatted(ctx, as, "/rest/promulgation/safetynet/reformat-safetynet", SAFETYNET_VO))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put(
                        "/rest/promulgation/safetynet/reformat-safetynet").json(reformatBody(SAFETYNET_VO, "x".repeat(20_001)))
                        .note("a text over the limit"), Cell.status(400, "The text is longer than 20000 characters"))));

        cases.add("getSafetyNetAreas", SweepCase
                .of((ctx, row) -> safetyNetAreas(SAFETYNET))
                .sweep((ctx, as) -> {
                    SweepResponse areas = ctx.ok(as, safetyNetAreas(SAFETYNET));
                    ctx.checkEquals(List.of("CIRCULAR", "RECTANGULAR", "COASTAL", "NAVAREA"), areas.json()
                            .getList("findAll { it.name.startsWith('sweep-delivery-area-') }.type"),
                            "the fixture's areas, one of each shape, in their sort order", areas);
                    ctx.checkEquals(List.of(DOMAIN), areas.json().getList("[0].promulgationType.domains.domainId"),
                            "the areas' type with its domain", areas);
                }));

        cases.add("createSafetyNetArea", SweepCase
                // A refused cell sends an area of another type: a gate that let it through answers 400
                .of((ctx, row) -> SweepRequest.post("/rest/promulgation/safetynet/areas/{typeId}/area")
                        .var("typeId", SAFETYNET).json(areaBody(NAVTEX, ctx.name("refused-circular"), 9, "X")))
                .sweep((ctx, as) -> createAreaReadBackAndRemove(ctx, as, "/rest/promulgation/safetynet/areas/{typeId}/area"))
                .variants((ctx, as) -> createAreaReadBackAndRemove(ctx, as,
                        "/rest/promulgation/safetynet/areas/{typeId}/area/")));

        cases.add("updateSafetyNetArea", SweepCase
                // The body names another area: a gate that let it through answers 400
                .of((ctx, row) -> areaAt(SweepRequest::put, AREA_CIRCULAR)
                        .json(areaBody(SAFETYNET, ctx.name("other-circular"), 1, "SWEEP")))
                .sweep((ctx, as) -> {
                    String name = createArea(ctx, as, "/rest/promulgation/safetynet/areas/{typeId}/area");
                    if (name == null) {
                        return;
                    }
                    try {
                        Map<String, Object> changed = areaBody(SAFETYNET, name, 7, "KATTEGAT");
                        changed.put("active", false);
                        changed.put("radius", "40");
                        ctx.ok(as, areaAt(SweepRequest::put, name).json(changed));
                        readAreaBack(ctx, as, name, false, 7, "KATTEGAT", "40");
                    } finally {
                        removeAreaAndReadGone(ctx, as, name);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), areaAt(SweepRequest::put, AREA_CIRCULAR)
                        .json(areaBody(SAFETYNET, ctx.name("other-circular"), 1, "SWEEP")).note("the body names another"),
                        Cell.status(400, null))));

        cases.add("deleteSafetyNetArea", SweepCase
                .of((ctx, row) -> areaAt(SweepRequest::delete, ctx.name("no-such-area")))
                .sweep((ctx, as) -> {
                    String name = createArea(ctx, as, "/rest/promulgation/safetynet/areas/{typeId}/area");
                    if (name != null) {
                        removeAreaAndReadGone(ctx, as, name);
                    }
                }));
    }

    private static SweepRequest areaAt(Verb verb, String name) {
        return verb.at("/rest/promulgation/safetynet/areas/{typeId}/area/{name}").var("typeId", SAFETYNET)
                .var("name", name);
    }

    private static String createArea(SweepContext ctx, SweepIdentity as, String pattern) {
        String name = ctx.name("circular-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).var("typeId", SAFETYNET)
                .json(areaBody(SAFETYNET, name, 5, "SKAGERRAK")));
        return created.is2xx() ? name : null;
    }

    private static void createAreaReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        String name = createArea(ctx, as, pattern);
        if (name == null) {
            return;
        }
        try {
            readAreaBack(ctx, as, name, true, 5, "SKAGERRAK", "25");
        } finally {
            removeAreaAndReadGone(ctx, as, name);
        }
    }

    private static void readAreaBack(SweepContext ctx, SweepIdentity as, String name, boolean active, int sortOrder,
                                     String prefix, String radius) {
        SweepResponse all = ctx.ok(as, safetyNetAreas(SAFETYNET));
        String at = "find { it.name == '" + name + "' }";
        if (all.json().get(at) == null) {
            ctx.fail(all, "the area " + name + " is listed");
            return;
        }
        JsonPath json = all.json();
        ctx.checkEquals("CIRCULAR", json.getString(at + ".type"), "the area's shape", all);
        ctx.checkEquals(active, json.getBoolean(at + ".active"), "active", all);
        ctx.checkEquals(sortOrder, json.getInt(at + ".sortOrder"), "the sort order", all);
        ctx.checkEquals("REPETITION_01", json.getString(at + ".repetition"), "the repetition", all);
        ctx.checkEquals(prefix, json.getString(at + ".messageAreaPrefix"), "the message area prefix", all);
        ctx.checkEquals("57 10", json.getString(at + ".center"), "the centre", all);
        ctx.checkEquals(radius, json.getString(at + ".radius"), "the radius", all);
        ctx.checkEquals(SAFETYNET, json.getString(at + ".promulgationType.typeId"), "the area's type", all);
    }

    private static void removeAreaAndReadGone(SweepContext ctx, SweepIdentity as, String name) {
        ctx.ok(as, areaAt(SweepRequest::delete, name));
        SweepResponse all = ctx.ok(as, safetyNetAreas(SAFETYNET).note("read after the delete"));
        ctx.check(!all.json().getList("name").contains(name), "the area " + name + " is gone", all);
    }

    // ------------------------------------------------------------------------------------------- //
    // Twitter settings (PROM7: every answer that maps a type with its domains answers 500)
    // ------------------------------------------------------------------------------------------- //

    private static void twitterCases(SweepCases cases) {
        cases.add("getTwitterSettings", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/promulgation/twitter-settings/{typeId}").var("typeId", TWITTER)));

        cases.add("createTwitterSettings", SweepCase
                // A refused cell names a type that does not exist
                .of((ctx, row) -> SweepRequest.post("/rest/promulgation/twitter-settings")
                        .json(twitterBody(ctx.name("no-such-type"), "x", "refused")))
                .sweep((ctx, as) -> createTwitterSettings(ctx, as, "/rest/promulgation/twitter-settings"))
                .variants((ctx, as) -> createTwitterSettings(ctx, as, "/rest/promulgation/twitter-settings/")));

        cases.add("updateTwitterSettings", SweepCase
                // The body names another type: a gate that let it through answers 400
                .of((ctx, row) -> SweepRequest.put("/rest/promulgation/twitter-settings/{typeId}").var("typeId", TWITTER)
                        .json(twitterBody(NAVTEX, "x", "refused")))
                .sweep((ctx, as) -> {
                    String format = "sweep-delivery ${title} " + ctx.unique();
                    try {
                        ctx.ok(as, SweepRequest.put("/rest/promulgation/twitter-settings/{typeId}")
                                .var("typeId", TWITTER).json(twitterBody(TWITTER, format, "updated")));
                    } finally {
                        // PROM7 ends the step at the 500 of the stored write; the read-back runs all the same
                        readTwitterSettingsBack(ctx, TWITTER, format, "updated");
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/promulgation/twitter-settings/{typeId}")
                        .var("typeId", TWITTER).json(twitterBody(NAVTEX, "x", "refused")).note("the body's type is "
                                + "not the path's"), Cell.status(400, null))));
    }

    static Map<String, Object> twitterBody(String typeId, String format, String secret) {
        return map("promulgationType", Map.of("typeId", typeId), "format", format, "includeThumbnail", false,
                "apiKey", "sweep-key-" + secret, "apiSecret", "sweep-secret-" + secret,
                "accessToken", "sweep-token-" + secret, "accessTokenSecret", "sweep-token-secret-" + secret);
    }

    private static void createTwitterSettings(SweepContext ctx, SweepIdentity as, String pattern) {
        String type = ctx.name("twitter-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/promulgations/promulgation-type")
                .json(typeBody(type, "twitter", type, 905, List.of(DOMAIN))));
        if (!created.is2xx()) {
            return;
        }
        try {
            String format = "sweep-delivery ${title}";
            try {
                ctx.ok(as, SweepRequest.post(pattern).json(twitterBody(type, format, "created")));
            } finally {
                // PROM7 ends the step at the 500 of the stored write; the read-back runs all the same
                readTwitterSettingsBack(ctx, type, format, "created");
            }
        } finally {
            // No endpoint removes Twitter settings (PROM8): through the entity manager, so the type can go
            EntityManager em = em(ctx);
            Tx.requiringNew().run(() -> em.createQuery("delete from TwitterSettings s where s.promulgationType.id in "
                    + "(select t.id from PromulgationType t where t.typeId = :typeId)")
                    .setParameter("typeId", type).executeUpdate());
            removeTypeAndReadGone(ctx, SweepIdentity.parse("sysadmin"), type);
        }
    }

    /** The stored settings, read through the service: the GET answers 500 (PROM7) */
    private static void readTwitterSettingsBack(SweepContext ctx, String typeId, String format, String secret) {
        TwitterSettings stored = Tx.requiringNew().call(() ->
                ctx.bean(TwitterPromulgationService.class).getSettings(typeId));
        if (stored == null) {
            ctx.fail("The Twitter settings of " + typeId + " are not stored after the write");
            return;
        }
        ctx.checkEquals(format, stored.getFormat(), "the stored format", null);
        ctx.checkEquals(false, stored.getIncludeThumbnail(), "the stored includeThumbnail", null);
        ctx.checkEquals("sweep-key-" + secret, stored.getApiKey(), "the stored API key", null);
        ctx.checkEquals("sweep-secret-" + secret, stored.getApiSecret(), "the stored API secret", null);
        ctx.checkEquals("sweep-token-" + secret, stored.getAccessToken(), "the stored access token", null);
        ctx.checkEquals("sweep-token-secret-" + secret, stored.getAccessTokenSecret(), "the stored access token secret",
                null);
    }
}
