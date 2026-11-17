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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The worked example of the endpoint sweep: the eight handlers of {@code SourceRestService}
 * ({@code endpoints/sources.json}), end to end. The other groups copy its shape.
 *
 * <p>The fixture is two sources made through the API as admin, an active one with an English and a Danish
 * description and an inactive one; the reads assert they hold them. The four handlers carry no code check, so their
 * role cells are the annotation's: one anonymous call each for the three {@code @RolesAllowed(ADMIN)} writes and the
 * upload (expected 401), and the sweep as the least privileged identity the annotation admits (anonymous for the
 * reads, admin for the writes). The writes make a row of their own, read it back field by field, remove it and read
 * it gone; the upload's job is awaited to COMPLETED and its log read. The full run adds the probes (unknown and
 * malformed ids, empty and nameless bodies, a mismatched id, empty and broken files) and the variants (the inactive
 * filter, the Danish name, the limit, the alias path of the create).
 *
 * <p>No existing IT counts all sources: the ones that count (SourceServiceITTest, ReferenceDataImportRoundTripITTest)
 * count the rows of their own name prefix. The tear-down removes the two fixture sources and reads each one gone;
 * the source tables are compacted after it ({@link #tables()}), because a source's descriptions have no defined
 * order and the free space of deleted description rows would otherwise reorder those of a later test's source. The
 * batch instance of each upload stays: no endpoint removes one.
 */
class SourcesSweepGroup implements SweepGroup {

    static final String ACTIVE = "sweep-sources-active";
    static final String INACTIVE = "sweep-sources-inactive";

    /** An id no source has */
    static final long UNKNOWN = 987654321L;

    @Override
    public String name() {
        return "sources";
    }

    @Override
    public Set<String> rows() {
        return Set.of(ACTIVE, INACTIVE);
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixture
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void setUp(SweepContext ctx) {
        ctx.putRow(ACTIVE, findOrCreate(ctx, ACTIVE, true));
        ctx.putRow(INACTIVE, findOrCreate(ctx, INACTIVE, false));
    }

    /** The id of the source of the English name, made as admin when there is none */
    private static int findOrCreate(SweepContext ctx, String name, boolean active) {
        Integer id = idOf(ctx, name);
        if (id != null) {
            return id;
        }
        SweepResponse created = ctx.ok("admin", SweepRequest.post("/rest/sources/source").json(body(name, active)));
        return created.json().getInt("id");
    }

    /** The id of the source of the English name (active or not), or null */
    static Integer idOf(SweepContext ctx, String name) {
        SweepResponse found = ctx.ok("anonymous", SweepRequest.get("/rest/sources/search")
                .query("lang", "en").query("name", name).query("inactive", true));
        return found.json().get("find { it.descs.find { d -> d.lang == 'en' }.name == '" + name + "' }.id");
    }

    /** A source with an English and a Danish description */
    static Map<String, Object> body(String name, boolean active) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("active", active);
        source.put("descs", List.of(
                Map.of("lang", "en", "name", name, "abbreviation", "SWP"),
                Map.of("lang", "da", "name", name + "-da", "abbreviation", "SWD")));
        return source;
    }

    @Override
    public List<String> tearDown(SweepContext ctx) {
        List<String> left = new ArrayList<>();
        for (String row : List.of(ACTIVE, INACTIVE)) {
            if (!ctx.hasRow(row)) {
                continue;
            }
            Object id = ctx.row(row);
            ctx.send("admin", SweepRequest.delete("/rest/sources/source/{id}").var("id", id));
            // The delete's status is not enough: read the row gone
            SweepResponse read = ctx.send("anonymous", get(id).note("read after the tear-down's delete"));
            if (!SweepEngine.matches(Cell.status(404, "No such source: " + id), read)) {
                left.add(row + " (id " + id + ", read after the delete: " + read.status() + ")");
            }
        }
        return left;
    }

    @Override
    public Map<String, String> leftByDesign() {
        return Map.of("the batch instance of each upload (source-import, file sweep-sources-<unique>.json)",
                "no endpoint removes a batch instance");
    }

    @Override
    public Set<String> tables() {
        return Set.of("Source", "SourceDesc");
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void cases(SweepCases cases) {
        cases.add("searchSources", SweepCase
                .of((ctx, row) -> search(ACTIVE))
                .variants((ctx, as) -> {
                    // The inactive source shows only with inactive=true
                    SweepResponse active = ctx.ok(as, search(INACTIVE));
                    ctx.check(!active.text().contains(INACTIVE), "the default search leaves the inactive source out",
                            active);
                    ctx.expectRows(ctx.ok(as, search(INACTIVE).query("inactive", true)), null, INACTIVE);
                    // The Danish name, and the limit
                    ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/sources/search").query("lang", "da")
                            .query("name", ACTIVE + "-da")), null, ACTIVE + "-da");
                    SweepResponse limited = ctx.ok(as, SweepRequest.get("/rest/sources/search").query("lang", "en")
                            .query("name", "sweep-sources").query("inactive", true).query("limit", 1));
                    ctx.checkEquals(1, limited.json().getList("$").size(), "the number of sources with limit=1",
                            limited);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), search(ACTIVE).query("limit", "abc").note("malformed limit"),
                            Cell.status(400, "Failed to convert 'limit' with value: 'abc'"));
                    ctx.call(as.name(), search(ACTIVE).query("inactive", "maybe").note("malformed inactive"),
                            Cell.status(400, "Failed to convert 'inactive' with value: 'maybe'"));
                }));

        cases.add("searchSourcesById", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/sources/search/{ids}")
                        .var("ids", ctx.row(ACTIVE) + "," + ctx.row(INACTIVE)))
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, SweepRequest.get("/rest/sources/search/{ids}")
                            .var("ids", UNKNOWN).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the answer for an unknown id", unknown);
                    // The handler parses the ids itself; the parser's own message is the detail
                    ctx.call(as.name(), SweepRequest.get("/rest/sources/search/{ids}").var("ids", "abc")
                            .note("malformed-id probe"), Cell.status(400, "For input string: \"abc\""));
                }));

        cases.add("getAllSources", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/sources/all").query("lang", "en"))
                .variants((ctx, as) -> {
                    // A public read answers the same in another domain, and for a token with no header at all
                    ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/sources/all").domain("niord-nm")), null, ACTIVE);
                    ctx.expectRows(ctx.ok("user", SweepRequest.get("/rest/sources/all").domain(null)), null, ACTIVE);
                    SweepResponse limited = ctx.ok(as, SweepRequest.get("/rest/sources/all").query("limit", 1));
                    ctx.checkEquals(1, limited.json().getList("$").size(), "the number of sources with limit=1",
                            limited);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/sources/all")
                        .query("limit", "abc").note("malformed limit"),
                        Cell.status(400, "Failed to convert 'limit' with value: 'abc'"))));

        cases.add("getSource", SweepCase
                .of((ctx, row) -> get(ctx.row(row == null ? ACTIVE : row)))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), get(UNKNOWN).note("unknown-id probe"),
                            Cell.status(404, "No such source: " + UNKNOWN));
                    ctx.call(as.name(), get("abc").note("malformed-id probe"),
                            Cell.status(400, "Failed to convert 'id' with value: 'abc'"));
                }));

        cases.add("createSource", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/sources/source")
                        .json(body(ctx.name("refused-" + ctx.unique()), true)))
                .sweep((ctx, as) -> createReadBackAndRemove(ctx, as, "/rest/sources/source"))
                .variants((ctx, as) -> createReadBackAndRemove(ctx, as, "/rest/sources/source/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/sources/source").json(Map.of()).note("empty body"),
                            Cell.status(400, "A source needs a name in at least one language"));
                    ctx.call(as.name(), SweepRequest.post("/rest/sources/source")
                                    .json(Map.of("active", true, "descs", List.of(Map.of("lang", "en", "name", " "))))
                                    .note("a blank name"),
                            Cell.status(400, "A source needs a name in at least one language"));
                }));

        cases.add("updateSource", SweepCase
                // A refused cell sends the fixture row as it is, so a gate that let it through would change nothing
                .of((ctx, row) -> put(ctx.row(row == null ? ACTIVE : row), ACTIVE, true))
                .sweep((ctx, as) -> {
                    int id = create(ctx, as, ctx.name("update-" + ctx.unique()));
                    try {
                        String renamed = ctx.name("updated-" + ctx.unique());
                        Map<String, Object> changed = new LinkedHashMap<>();
                        changed.put("id", id);
                        changed.put("active", false);
                        changed.put("descs", List.of(
                                Map.of("lang", "en", "name", renamed, "abbreviation", "UPD"),
                                Map.of("lang", "da", "name", renamed + "-da", "abbreviation", "UPDA")));
                        ctx.ok(as, SweepRequest.put("/rest/sources/source/{id}").var("id", id).json(changed));
                        readBack(ctx, id, false, renamed, "UPD", renamed + "-da", "UPDA");
                    } finally {
                        removeAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> {
                    Object id = ctx.row(ACTIVE);
                    Map<String, Object> mismatched = new LinkedHashMap<>(body(ACTIVE, true));
                    mismatched.put("id", ((Number) id).longValue() + 1);
                    ctx.call(as.name(), SweepRequest.put("/rest/sources/source/{id}").var("id", id).json(mismatched)
                            .note("the body's id is not the path's"), Cell.status(400, null));
                    ctx.call(as.name(), put(UNKNOWN, ctx.name("unknown"), true).note("unknown-id probe"),
                            Cell.status(400, "Cannot update non-existing source " + UNKNOWN));
                }));

        cases.add("deleteSource", SweepCase
                // A refused cell asks to remove the fixture's active source; the reads after it would fail if it went
                .of((ctx, row) -> SweepRequest.delete("/rest/sources/source/{id}")
                        .var("id", ctx.row(row == null ? ACTIVE : row)))
                .sweep((ctx, as) -> removeAndReadGone(ctx, as, create(ctx, as, ctx.name("delete-" + ctx.unique()))))
                .probes((ctx, as) -> {
                    // The handler removes what is there and answers 204 for an id that is not (no 404)
                    ctx.call(as.name(), SweepRequest.delete("/rest/sources/source/{id}").var("id", UNKNOWN)
                            .note("unknown-id probe"), Cell.status(204, null));
                }));

        cases.add("importSources", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/sources/upload-sources")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String name = ctx.name("uploaded-" + ctx.unique());
                    String json = "[{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"" + name
                            + "\",\"abbreviation\":\"UPL\"}]}]";
                    String fileName = ctx.jobs().upload(ctx, as.name(),
                            SweepRequest.post("/rest/sources/upload-sources"), json);
                    ctx.jobs().awaitClean(ctx, "source-import", fileName);
                    Integer id = idOf(ctx, name);
                    if (id == null) {
                        ctx.fail("The uploaded source " + name + " is not found after its job completed");
                        return;
                    }
                    try {
                        SweepResponse read = ctx.ok("anonymous", get(id));
                        ctx.checkEquals("UPL", read.json().getString("descs.find { it.lang == 'en' }.abbreviation"),
                                "the uploaded source's English abbreviation", read);
                    } finally {
                        removeAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/sources/upload-sources")
                                    .file("file", ctx.name("empty") + ".json", new byte[0], "application/json")
                                    .note("an empty file"),
                            Cell.status(400, "is not valid JSON: the file is empty"));
                    ctx.call(as.name(), SweepRequest.post("/rest/sources/upload-sources")
                                    .file("file", ctx.name("broken") + ".json", "[{\"active\":".getBytes(),
                                            "application/json").note("a broken file"),
                            Cell.status(400, "is not valid JSON"));
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers of the cases
    // ------------------------------------------------------------------------------------------- //

    private static SweepRequest search(String name) {
        return SweepRequest.get("/rest/sources/search").query("lang", "en").query("name", name);
    }

    private static SweepRequest get(Object id) {
        return SweepRequest.get("/rest/sources/source/{id}").var("id", id);
    }

    private static SweepRequest put(Object id, String name, boolean active) {
        Map<String, Object> source = new LinkedHashMap<>(body(name, active));
        source.put("id", id);
        return SweepRequest.put("/rest/sources/source/{id}").var("id", id).json(source);
    }

    /** Creates a source of the name as the identity; answers its id */
    private static int create(SweepContext ctx, SweepIdentity as, String name) {
        SweepResponse created = ctx.ok(as, SweepRequest.post("/rest/sources/source").json(body(name, true)));
        return created.json().getInt("id");
    }

    /** The write sweep of the create: create, read back every field sent, remove, read gone */
    private static void createReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        String name = ctx.name("created-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(body(name, true)));
        if (!created.is2xx()) {
            return;
        }
        int id = created.json().getInt("id");
        try {
            readBack(ctx, id, true, name, "SWP", name + "-da", "SWD");
        } finally {
            removeAndReadGone(ctx, as, id);
        }
    }

    /** Reads the source back as anonymous and checks every field the write sent */
    private static void readBack(SweepContext ctx, int id, boolean active, String enName, String enAbbreviation,
                                 String daName, String daAbbreviation) {
        SweepResponse read = ctx.ok("anonymous", get(id));
        if (!read.is2xx()) {
            return;
        }
        JsonPath json = read.json();
        ctx.checkEquals(id, json.getInt("id"), "the id", read);
        ctx.checkEquals(active, json.getBoolean("active"), "active", read);
        ctx.checkEquals(enName, json.getString("descs.find { it.lang == 'en' }.name"), "the English name", read);
        ctx.checkEquals(enAbbreviation, json.getString("descs.find { it.lang == 'en' }.abbreviation"),
                "the English abbreviation", read);
        ctx.checkEquals(daName, json.getString("descs.find { it.lang == 'da' }.name"), "the Danish name", read);
        ctx.checkEquals(daAbbreviation, json.getString("descs.find { it.lang == 'da' }.abbreviation"),
                "the Danish abbreviation", read);
        ctx.checkEquals(2, json.getList("descs").size(), "the number of descriptions", read);
    }

    /** Removes the source as the identity and reads it gone */
    private static void removeAndReadGone(SweepContext ctx, SweepIdentity as, int id) {
        ctx.ok(as, SweepRequest.delete("/rest/sources/source/{id}").var("id", id));
        ctx.call("anonymous", get(id).note("read after the delete"), Cell.status(404, "No such source: " + id));
    }
}
