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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.niord.web.sweep.SystemSweepFixture.CONTACT;
import static org.niord.web.sweep.SystemSweepFixture.CONTACT_EMAIL;
import static org.niord.web.sweep.SystemSweepFixture.S124_DRAFT;
import static org.niord.web.sweep.SystemSweepFixture.S124_PUBLISHED;
import static org.niord.web.sweep.SystemSweepFixture.SCRIPT_RESOURCE;
import static org.niord.web.sweep.SystemSweepFixture.SCRIPT_RESOURCE_PATH;
import static org.niord.web.sweep.SystemSweepFixture.UNKNOWN;
import static org.niord.web.sweep.SystemSweepFixture.contactBody;
import static org.niord.web.sweep.SystemSweepFixture.contactId;
import static org.niord.web.sweep.SystemSweepFixture.map;
import static org.niord.web.sweep.SystemSweepFixture.scriptResourceBody;
import static org.niord.web.sweep.SystemSweepFixture.scriptResourceId;

/**
 * The system group of the endpoint sweep ({@code endpoints/system.json}), main context: batch, bootstrap, contacts,
 * the Keycloak configuration, monitor, S-124, script resources, settings, tickets, users and the version. The SECOM
 * administration interface of the same file is {@link SystemSecomSweepGroup}, run by
 * {@code org.niord.secom.SecomEndpointSweepITTest}.
 *
 * <p>The fixture ({@link SystemSweepFixture}): a published S-124 message and a draft, a batch instance (a harmless
 * script), a contact on no mailing list (CONTACT1 is never met), a script resource with two history entries and a
 * local user. The batch cases are {@link SystemSweepBatch}, the user directory and Keycloak cases
 * {@link SystemSweepKeycloak}.
 *
 * <p>The code-checked handlers here check a role in code because a download link cannot carry the token: the
 * contacts export, the script resources list and the batch status (admin), the editable settings and the batch data
 * file (sysadmin), each also with a ticket; and the ticket itself, which grants only roles its caller holds. Their
 * cells are every tier, the identities of another domain, a ticket of the role the admin app asks, one of a lower role
 * and a role-less ticket (TICKET1: grants no role).
 *
 * <p>The setting the update sweep writes is {@code documentationUrl}, as {@code SettingsRestServiceITTest} does, set
 * back in {@code finally}; the settings import is swept on the same setting (the job writes only the keys its file
 * names). The bootstrap command is refused on the seeded database (409 'existing'): refused cells only, with the base
 * product's own bootstrap archive. The batch set and the repository import act on everything of a kind: global,
 * refused cells and a refusal probe.
 */
class SystemSweepGroup implements SweepGroup {

    /** The setting the update sweep writes and sets back */
    static final String SETTING = "documentationUrl";

    private final SystemSweepFixture fixture = new SystemSweepFixture();

    /** The base product's bootstrap archive, zipped once */
    private static byte[] bootstrapArchive;

    @Override
    public String name() {
        return "system";
    }

    @Override
    public Set<String> rows() {
        return SystemSweepFixture.ROWS;
    }

    @Override
    public void setUp(SweepContext ctx) throws Exception {
        fixture.setUp(ctx);
    }

    @Override
    public List<String> tearDown(SweepContext ctx) {
        return fixture.tearDown(ctx);
    }

    @Override
    public Map<String, String> leftByDesign() {
        return Map.of(
                "the job repository's rows of each batch job the group ran (script-executor, script-resource-import,"
                        + " settings-import: job instance, execution, step execution)",
                "no endpoint removes them; the group removes each job's BatchData row and folder",
                "the BATCH_JOB_<job name> Sequence row of a job's first run in the database",
                "the batch service makes it at a job's first run and nothing removes it",
                "the batch-set work folder of each execute-batch-set probe (full run), on disk",
                "the batch expiry removes it after ten days; nothing in the database");
    }

    @Override
    public Set<String> tables() {
        return SystemSweepFixture.TABLES;
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void cases(SweepCases cases) {
        SystemSweepBatch.cases(cases, fixture);
        SystemSweepKeycloak.cases(cases);
        s124(cases);
        contacts(cases);
        scriptResources(cases);
        settings(cases);
        misc(cases);
    }

    private void s124(SweepCases cases) {
        cases.add("s124MessageDetails", SweepCase
                .of((ctx, row) -> s124(ctx.row(row == null ? S124_PUBLISHED : row)))
                .sweep((ctx, as) -> {
                    SweepResponse gml = ctx.ok(as, s124(ctx.row(S124_PUBLISHED)));
                    if (gml.is2xx()) {
                        ctx.check(gml.contentType() != null && gml.contentType().startsWith("application/gml+xml"),
                                "the answer is application/gml+xml", gml);
                    }
                    // S124-5, in every run: the public read serves no draft, and its answer is the unknown id's
                    // (it logs "Message does not exist" at ERROR, as the old system did: the entry's expectedLog)
                    SweepResponse draft = ctx.send(as, s124(ctx.row(S124_DRAFT)).note("a draft's UID"));
                    ctx.check(draft.status() == 404 && draft.text().equals("No message found with ID: "
                                    + ctx.row(S124_DRAFT)),
                            "404 \"No message found with ID\" (plain text) for a draft, as for an unknown id", draft);
                })
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, s124(ctx.row(S124_PUBLISHED)).query("lang", "da")),
                        null, S124_PUBLISHED))
                .probes((ctx, as) -> {
                    // The answer logs an ERROR, as the old system's did (the entry's expectedLog)
                    SweepResponse unknown = ctx.send(as, s124("sweep-system-no-such-message").note("unknown-id probe"));
                    ctx.check(unknown.status() == 404 && unknown.text().startsWith("No message found with ID"),
                            "404 \"No message found with ID\" (plain text) for an unknown id", unknown);
                }));

        cases.add("xsdFile", SweepCase
                .of((ctx, row) -> xsd("124_2.0.0"))
                .sweep((ctx, as) -> {
                    SweepResponse plain = ctx.ok(as, xsd("124_2.0.0"));
                    SweepResponse withExtension = ctx.ok(as, xsd("124_2.0.0.xsd"));
                    ctx.check(plain.text().contains("schema") && plain.text().equals(withExtension.text()),
                            "the schema, with or without its .xsd ending", withExtension);
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.send(as, xsd("SweepSystemNoSuchSchema").note("unknown file"));
                    ctx.check(unknown.status() == 404 && unknown.text().contains("XSD does not exist"),
                            "404 \"XSD does not exist\" for an unknown file", unknown);
                }));
    }

    private void contacts(SweepCases cases) {
        cases.add("searchContacts", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/contacts/search").query("name", CONTACT))
                .variants((ctx, as) -> {
                    // The term matches the address, in any case
                    ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/contacts/search")
                            .query("name", CONTACT.toUpperCase() + "@EXAMPLE")), null, CONTACT);
                    SweepResponse paged = ctx.ok(as, SweepRequest.get("/rest/contacts/search").query("name", CONTACT)
                            .query("maxSize", 1).query("page", 1));
                    ctx.checkEquals(List.of(), paged.json().getList("data"), "the second page of one", paged);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/contacts/search")
                        .query("maxSize", "abc").note("malformed maxSize"),
                        Cell.status(400, "Failed to convert 'maxSize' with value: 'abc'"))));

        cases.add("addContact", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/contacts/contact").json(contactBody(null,
                        ctx.name("refused-" + ctx.unique()) + "@example.org", "refused", "en")))
                .sweep((ctx, as) -> createContactReadBackAndRemove(ctx, as, "/rest/contacts/contact"))
                .variants((ctx, as) -> createContactReadBackAndRemove(ctx, as, "/rest/contacts/contact/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/contacts/contact").json(Map.of()).note("empty body"),
                            Cell.status(400, "A contact needs an e-mail address"));
                    ctx.call(as.name(), SweepRequest.post("/rest/contacts/contact")
                                    .json(contactBody(null, "sweep-system-not-an-address", "x", "en")).note("no address"),
                            Cell.status(400, "is not an e-mail address"));
                    ctx.call(as.name(), SweepRequest.post("/rest/contacts/contact")
                                    .json(contactBody(null, CONTACT_EMAIL, "x", "en")).note("the fixture's address again"),
                            Cell.status(400, "already exists"));
                }));

        cases.add("updateContact", SweepCase
                // A refused cell sends the fixture's contact as it is
                .of((ctx, row) -> SweepRequest.put("/rest/contacts/contact/{id}").var("id", ctx.row(CONTACT))
                        .json(contactBody((Integer) ctx.row(CONTACT), CONTACT_EMAIL, CONTACT, "en")))
                .sweep((ctx, as) -> {
                    String email = ctx.name("contact-" + ctx.unique()) + "@example.org";
                    int id = createContact(ctx, as, email);
                    try {
                        String changed = ctx.name("changed-" + ctx.unique()) + "@example.org";
                        ctx.ok(as, SweepRequest.put("/rest/contacts/contact/{id}").var("id", id)
                                .json(contactBody(id, changed, "sweep-system-changed", "da")));
                        readContactBack(ctx, id, changed, "sweep-system-changed", "da");
                    } finally {
                        removeContactAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put("/rest/contacts/contact/{id}").var("id", ctx.row(CONTACT))
                                    .json(contactBody(UNKNOWN, CONTACT_EMAIL, CONTACT, "en"))
                                    .note("the body's id is not the path's"), Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.put("/rest/contacts/contact/{id}").var("id", UNKNOWN)
                                    .json(contactBody(UNKNOWN, CONTACT_EMAIL, CONTACT, "en")).note("unknown-id probe"),
                            Cell.status(400, "Contact with ID " + UNKNOWN + " does not exists"));
                }));

        cases.add("deleteContact", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/contacts/contact/{id}").var("id", ctx.row(CONTACT)))
                .sweep((ctx, as) -> removeContactAndReadGone(ctx, as,
                        createContact(ctx, as, ctx.name("delete-" + ctx.unique()) + "@example.org")))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/contacts/contact/{id}")
                        .var("id", UNKNOWN).note("unknown-id probe"), Cell.status(204, null))));

        cases.add("exportContacts", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/contacts/export"))
                .as("admin")
                .sweep((ctx, as) -> {
                    SweepResponse csv = ctx.ok(as, SweepRequest.get("/rest/contacts/export"));
                    String line = "\"" + CONTACT_EMAIL + "\";\"" + CONTACT + "\";\"en\"";
                    ctx.check(csv.text().lines().anyMatch(l -> l.equals(line)), "the export holds the line " + line,
                            csv);
                })
                .variants((ctx, as) -> {
                    SweepResponse csv = ctx.ok(as, SweepRequest.get("/rest/contacts/export").query("separator", ","));
                    String line = "\"" + CONTACT_EMAIL + "\",\"" + CONTACT + "\",\"en\"";
                    ctx.check(csv.text().lines().anyMatch(l -> l.equals(line)), "separator=, gives the line " + line,
                            csv);
                }));

        cases.add("importEmails", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/contacts/import-emails").json(map("emails", CONTACT_EMAIL)))
                .sweep((ctx, as) -> {
                    // One new address; one not an address, the new one again in capitals, an existing contact's and
                    // a user's address (the fixture's local user): only the first is imported
                    String email = ctx.name("import-" + ctx.unique()) + "@example.org";
                    SweepResponse answer = ctx.ok(as, SweepRequest.post("/rest/contacts/import-emails")
                            .json(map("emails", email + ";sweep-system-not-an-address;" + email.toUpperCase()
                                    + "," + CONTACT_EMAIL + "\n" + SystemSweepFixture.USER_EMAIL)));
                    Integer id = contactId(ctx, email);
                    Integer userAsContact = contactId(ctx, SystemSweepFixture.USER_EMAIL);
                    try {
                        ctx.check(answer.text().startsWith("Imported 1 new contacts"),
                                "the answer counts the one new contact (\"Imported 1 new contacts\")", answer);
                        ctx.check(id != null, "the imported address " + email + " is a contact", answer);
                        ctx.check(userAsContact == null, "a user's address (" + SystemSweepFixture.USER_EMAIL
                                + ") is not imported as a contact", answer);
                    } finally {
                        if (id != null) {
                            removeContactAndReadGone(ctx, as, id);
                        }
                        if (userAsContact != null) {
                            removeContactAndReadGone(ctx, as, userAsContact);
                        }
                    }
                }));
    }

    private void scriptResources(SweepCases cases) {
        cases.add("getScriptResources", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/script-resources/all"))
                .as("admin")
                .variants((ctx, as) -> {
                    ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/script-resources/all").query("type", "FM")),
                            null, SCRIPT_RESOURCE);
                    SweepResponse js = ctx.ok(as, SweepRequest.get("/rest/script-resources/all").query("type", "JS"));
                    ctx.check(!js.text().contains(SCRIPT_RESOURCE_PATH), "type=JS leaves the template out", js);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/script-resources/all")
                        .query("type", "XX").note("unknown type"), Cell.status(400, "Failed to convert 'type'"))));

        cases.add("createScriptResource", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/script-resources/script-resource").json(scriptResourceBody(
                        null, "templates/" + ctx.name("refused-" + ctx.unique()) + ".ftl", "<#-- refused -->")))
                .sweep((ctx, as) -> createResourceReadBackAndRemove(ctx, as, "/rest/script-resources/script-resource"))
                .variants((ctx, as) -> createResourceReadBackAndRemove(ctx, as,
                        "/rest/script-resources/script-resource/"))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/script-resources/script-resource").json(Map.of())
                            .note("empty body"), Cell.status(400, "A script resource needs a path"));
                    ctx.call(as.name(), SweepRequest.post("/rest/script-resources/script-resource")
                                    .json(scriptResourceBody(null, "templates/sweep-system-x.txt", "x")).note("a .txt path"),
                            Cell.status(400, "must end in .ftl or .js"));
                    ctx.call(as.name(), SweepRequest.post("/rest/script-resources/script-resource")
                                    .json(scriptResourceBody(null, SCRIPT_RESOURCE_PATH, "x")).note("the fixture's path"),
                            Cell.status(400, "Cannot create script resource with duplicate path"));
                }));

        cases.add("updateScriptResource", SweepCase
                // A refused cell sends the fixture's resource as it is
                .of((ctx, row) -> SweepRequest.put("/rest/script-resources/script-resource/{id}")
                        .var("id", ctx.row(SCRIPT_RESOURCE)).json(scriptResourceBody((Integer) ctx.row(SCRIPT_RESOURCE),
                                SCRIPT_RESOURCE_PATH, SystemSweepFixture.SCRIPT_RESOURCE_V2)))
                .sweep((ctx, as) -> {
                    String path = "templates/" + ctx.name("update-" + ctx.unique()) + ".ftl";
                    int id = createResource(ctx, as, path, "<#-- v1 -->");
                    try {
                        String moved = path.replace(".ftl", "-moved.js");
                        ctx.ok(as, SweepRequest.put("/rest/script-resources/script-resource/{id}").var("id", id)
                                .json(scriptResourceBody(id, moved, "var v = 2;")));
                        readResourceBack(ctx, id, "JS", moved, "var v = 2;");
                        SweepResponse history = ctx.ok(as, history(id));
                        ctx.checkEquals(2, history.json().getList("$").size(), "the history entries after the update",
                                history);
                    } finally {
                        removeResourceAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put("/rest/script-resources/script-resource/{id}")
                                    .var("id", ctx.row(SCRIPT_RESOURCE)).json(scriptResourceBody(UNKNOWN,
                                            SCRIPT_RESOURCE_PATH, "x")).note("the body's id is not the path's"),
                            Cell.status(400, null));
                    ctx.call(as.name(), SweepRequest.put("/rest/script-resources/script-resource/{id}")
                                    .var("id", UNKNOWN).json(scriptResourceBody(UNKNOWN, "templates/sweep-system-u.ftl",
                                            "x")).note("unknown-id probe"),
                            Cell.status(400, "Cannot update non-existing script resource"));
                }));

        cases.add("deleteScriptResource", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/script-resources/script-resource/{id}")
                        .var("id", ctx.row(SCRIPT_RESOURCE)))
                .sweep((ctx, as) -> removeResourceAndReadGone(ctx, as, createResource(ctx, as,
                        "templates/" + ctx.name("delete-" + ctx.unique()) + ".ftl", "<#-- delete -->")))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/script-resources/script-resource/{id}")
                        .var("id", UNKNOWN).note("unknown-id probe"), Cell.status(204, null))));

        cases.add("getScriptResourceHistory", SweepCase
                .of((ctx, row) -> history(ctx.row(SCRIPT_RESOURCE)))
                .sweep((ctx, as) -> {
                    SweepResponse history = ctx.ok(as, history(ctx.row(SCRIPT_RESOURCE)));
                    if (history.is2xx()) {
                        ctx.checkEquals(List.of(1, 2), history.json().getList("version").stream().sorted().toList(),
                                "the versions of the fixture's two writes", history);
                        ctx.check(history.json().getList("snapshot", String.class).stream()
                                        .anyMatch(s -> s.contains(SCRIPT_RESOURCE + " v2")),
                                "a snapshot holds the second version's content", history);
                    }
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.ok(as, history(UNKNOWN).note("unknown-id probe"));
                    ctx.checkEquals(List.of(), unknown.json().getList("$"), "the history of an unknown id", unknown);
                }));

        cases.add("previewScriptResourceImport", SweepCase
                .of((ctx, row) -> preview(ctx, "[]"))
                .sweep((ctx, as) -> {
                    String added = "templates/" + ctx.name("preview-" + ctx.unique()) + ".ftl";
                    SweepResponse preview = ctx.ok(as, preview(ctx, "[{\"path\": \"" + SCRIPT_RESOURCE_PATH
                            + "\", \"content\": \"<#-- v3 -->\"}, {\"path\": \"" + added + "\", \"content\": \"x\"}]"));
                    if (preview.is2xx()) {
                        ctx.checkEquals(List.of(added), preview.json().getList("add.path"), "the paths it adds",
                                preview);
                        ctx.checkEquals(List.of(SCRIPT_RESOURCE_PATH), preview.json().getList("overwrite.path"),
                                "the paths it overwrites", preview);
                        // It only reads
                        ctx.check(scriptResourceId(ctx, added) == null, "the preview stored nothing", preview);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), preview(ctx, "{\"path\": 1}").note("not a list"),
                        Cell.status(400, "is not a list of script resources"))));

        cases.add("getMissingPaths", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/script-resources/missing-paths"))
                .sweep((ctx, as) -> {
                    // The fixture's second version includes a template no row has
                    SweepResponse report = ctx.ok(as, SweepRequest.get("/rest/script-resources/missing-paths"));
                    if (report.is2xx()) {
                        String at = "find { it.path == '" + SystemSweepFixture.MISSING_PATH + "' }";
                        ctx.checkEquals(List.of("include in " + SCRIPT_RESOURCE_PATH),
                                report.json().getList(at + ".namedBy"), "what names " + SystemSweepFixture.MISSING_PATH,
                                report);
                    }
                }));

        cases.add("importScriptResources", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/script-resources/upload-script-resources")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String path = "templates/" + ctx.name("uploaded-" + ctx.unique()) + ".ftl";
                    String file = ctx.jobs().upload(ctx, as.name(),
                            SweepRequest.post("/rest/script-resources/upload-script-resources"),
                            "[{\"type\": \"FM\", \"path\": \"" + path + "\", \"content\": \"<#-- uploaded -->\"}]");
                    SweepJobs.JobEnd end = ctx.jobs().awaitClean(ctx, "script-resource-import", file);
                    Integer id = scriptResourceId(ctx, path);
                    try {
                        if (id == null) {
                            ctx.fail("The uploaded script resource " + path + " is not listed after its job");
                            return;
                        }
                        readResourceBack(ctx, id, "FM", path, "<#-- uploaded -->");
                        SweepResponse history = ctx.ok(as, history(id));
                        ctx.checkEquals("import", history.json().getString("[0].source"),
                                "the history's source of an imported resource", history);
                    } finally {
                        if (id != null) {
                            removeResourceAndReadGone(ctx, as, id);
                        }
                        if (end != null) {
                            SystemSweepBatch.removeAndReadGone(ctx, end.instanceId());
                        }
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/script-resources/upload-script-resources")
                                .file("file", ctx.name("empty") + ".json", new byte[0], "application/json")
                                .note("an empty file"), Cell.status(400, "is not valid JSON: the file is empty"))));
    }

    private void settings(SweepCases cases) {
        cases.add("getSettings", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/settings/editable-settings"))
                .as("sysadmin")
                .sweep((ctx, as) -> {
                    SweepResponse settings = ctx.ok(as, SweepRequest.get("/rest/settings/editable-settings"));
                    if (settings.is2xx()) {
                        ctx.check(settings.json().getList("key").contains(SETTING),
                                "the editable settings hold " + SETTING, settings);
                    }
                }));

        cases.add("getWebSettings", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/settings/web"))
                .sweep((ctx, as) -> {
                    SweepResponse web = ctx.ok(as, SweepRequest.get("/rest/settings/web"));
                    if (web.is2xx()) {
                        ctx.checkEquals(true, web.json().get("s124Enabled"), "s124Enabled (S-124 is on here)", web);
                        ctx.checkEquals(false, web.json().get("secomEnabled"), "secomEnabled (SECOM is off here)", web);
                    }
                }));

        cases.add("getSetting", SweepCase
                .of((ctx, row) -> setting(SETTING))
                .sweep((ctx, as) -> {
                    SweepResponse setting = ctx.ok(as, setting(SETTING));
                    if (setting.is2xx()) {
                        ctx.checkEquals(SETTING, setting.json().getString("key"), "the key", setting);
                        ctx.checkEquals("String", setting.json().getString("type"), "the type", setting);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), setting("sweep-system-no-such-setting").note("unknown key"),
                        Cell.status(404, "Unknown setting sweep-system-no-such-setting"))));

        cases.add("updateSetting", SweepCase
                // A refused cell sends the setting's value as it is
                .of((ctx, row) -> putSetting(SETTING, ctx.ok("sysadmin", setting(SETTING)).json().get("value")))
                .sweep((ctx, as) -> {
                    Object original = ctx.ok(as, setting(SETTING)).json().get("value");
                    try {
                        String value = "https://example.org/" + ctx.name("docs-" + ctx.unique());
                        ctx.ok(as, putSetting(SETTING, value));
                        SweepResponse read = ctx.ok(as, setting(SETTING));
                        ctx.checkEquals(value, read.json().get("value"), "the setting's value", read);
                    } finally {
                        ctx.ok(as, putSetting(SETTING, original));
                        SweepResponse read = ctx.ok(as, setting(SETTING).note("read after setting it back"));
                        ctx.checkEquals(original, read.json().get("value"), "the setting's value, set back", read);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.put("/rest/settings/setting/{key}").var("key", SETTING)
                                    .json(map("key", "sweep-system-other", "value", "x")).note("the body's key is not the path's"),
                            Cell.status(400, null));
                    ctx.call(as.name(), putSetting("sweep-system-no-such-setting", "x").note("unknown key"),
                            Cell.status(400, "sweep-system-no-such-setting"));
                }));

        cases.add("importSettings", SweepCase
                // An empty list: had a gate let it through, its job would change nothing
                .of((ctx, row) -> SweepRequest.post("/rest/settings/upload-settings")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    // The job writes only the keys the file names: one, the setting the update sweep writes too
                    Object original = ctx.ok(as, setting(SETTING)).json().get("value");
                    SweepJobs.JobEnd end = null;
                    try {
                        String value = "https://example.org/" + ctx.name("imported-" + ctx.unique());
                        String file = ctx.jobs().upload(ctx, as.name(),
                                SweepRequest.post("/rest/settings/upload-settings"),
                                "[{\"key\": \"" + SETTING + "\", \"value\": \"" + value + "\", \"type\": \"String\"}]");
                        end = ctx.jobs().awaitClean(ctx, "settings-import", file);
                        SweepResponse read = ctx.ok(as, setting(SETTING));
                        ctx.checkEquals(value, read.json().get("value"), "the imported setting's value", read);
                    } finally {
                        ctx.ok(as, putSetting(SETTING, original));
                        SweepResponse read = ctx.ok(as, setting(SETTING).note("read after setting it back"));
                        ctx.checkEquals(original, read.json().get("value"), "the setting's value, set back", read);
                        if (end != null) {
                            SystemSweepBatch.removeAndReadGone(ctx, end.instanceId());
                        }
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/settings/upload-settings")
                                .file("file", ctx.name("empty") + ".json", new byte[0], "application/json")
                                .note("an empty file"), Cell.status(400, "is not valid JSON: the file is empty"))));
    }

    private void misc(SweepCases cases) {
        cases.add("getTicket", SweepCase
                // The row of a cell names the role the ticket is asked for; no row asks none
                .of((ctx, row) -> row == null ? ticket() : ticket().query("role", ctx.row(row)))
                .sweep((ctx, as) -> {
                    SweepResponse ticket = ctx.ok(as, ticket());
                    ctx.check(ticket.text().length() >= 16, "a ticket of at least 16 characters", ticket);
                    // A ticket asked as a user with the role user opens an admin-only download for no one
                    SweepResponse user = ctx.ok("user", ticket().query("role", "user"));
                    SweepResponse export = ctx.send("anonymous", SweepRequest.get("/rest/contacts/export")
                            .query("ticket", user.text()));
                    ctx.checkEquals(403, export.status(), "a user's ticket on the admin-only export", export);
                }));

        cases.add("getBootstrapState", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/bootstrap"))
                .sweep((ctx, as) -> {
                    SweepResponse state = ctx.ok(as, SweepRequest.get("/rest/bootstrap"));
                    if (state.is2xx()) {
                        ctx.checkEquals("existing", state.json().getString("state"),
                                "the seeded database's bootstrap state", state);
                    }
                }));

        cases.add("bootstrap", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/bootstrap")
                        .file("archive", "niord-bootstrap.zip", bootstrapArchive(), "application/zip")));

        cases.add("getKeycloakConfig", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/settings/keycloak"))
                .sweep((ctx, as) -> {
                    SweepResponse config = ctx.ok(as, SweepRequest.get("/rest/settings/keycloak"));
                    if (config.is2xx()) {
                        JsonPath json = config.json();
                        ctx.checkEquals("niord", json.getString("realm"), "the realm", config);
                        ctx.checkEquals("niord-web", json.getString("clientId"), "the client id", config);
                        ctx.check(json.getString("url") != null && json.getString("url").startsWith("http"),
                                "the Keycloak URL the browser reaches", config);
                    }
                }));

        cases.add("ping", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/monitor/ping"))
                .sweep((ctx, as) -> {
                    SweepResponse pong = ctx.ok(as, SweepRequest.get("/rest/monitor/ping"));
                    ctx.checkEquals("pong", pong.text(), "the answer", pong);
                }));

        cases.add("db", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/monitor/db"))
                .sweep((ctx, as) -> {
                    SweepResponse db = ctx.ok(as, SweepRequest.get("/rest/monitor/db"));
                    ctx.checkEquals("success", db.text(), "the answer", db);
                }));

        cases.add("buildVersion", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/version/build-version"))
                .sweep((ctx, as) -> {
                    SweepResponse version = ctx.ok(as, SweepRequest.get("/rest/version/build-version"));
                    ctx.check(!version.text().isBlank(), "a build version", version);
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers of the cases
    // ------------------------------------------------------------------------------------------- //

    private static SweepRequest s124(Object messageId) {
        return SweepRequest.get("/rest/S-124/messages/{messageId}").var("messageId", messageId);
    }

    private static SweepRequest xsd(String file) {
        return SweepRequest.get("/rest/S-124/xsds/{file}").var("file", file);
    }

    private static SweepRequest ticket() {
        return SweepRequest.get("/rest/tickets/ticket");
    }

    private static SweepRequest setting(String key) {
        return SweepRequest.get("/rest/settings/setting/{key}").var("key", key);
    }

    private static SweepRequest putSetting(String key, Object value) {
        return SweepRequest.put("/rest/settings/setting/{key}").var("key", key).json(map("key", key, "value", value));
    }

    private static SweepRequest history(Object id) {
        return SweepRequest.get("/rest/script-resources/script-resource/{resourceId}/history").var("resourceId", id);
    }

    private static SweepRequest preview(SweepContext ctx, String json) {
        return SweepRequest.post("/rest/script-resources/import-preview")
                .file("file", ctx.name("preview-" + ctx.unique()) + ".json", json.getBytes(), "application/json");
    }

    // Contacts

    private static int createContact(SweepContext ctx, SweepIdentity as, String email) {
        return ctx.ok(as, SweepRequest.post("/rest/contacts/contact").json(contactBody(null, email, "sweep-system",
                "en"))).json().getInt("id");
    }

    private static void createContactReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        String email = ctx.name("created-" + ctx.unique()) + "@example.org";
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(contactBody(null, email,
                "sweep-system-created", "da")));
        if (!created.is2xx()) {
            return;
        }
        int id = created.json().getInt("id");
        try {
            readContactBack(ctx, id, email, "sweep-system-created", "da");
        } finally {
            removeContactAndReadGone(ctx, as, id);
        }
    }

    private static void readContactBack(SweepContext ctx, int id, String email, String name, String language) {
        SweepResponse read = ctx.ok("admin", SweepRequest.get("/rest/contacts/search").query("name", email));
        if (!read.is2xx()) {
            return;
        }
        String at = "data.find { it.id == " + id + " }";
        ctx.checkEquals(email, read.json().getString(at + ".email"), "the contact's address", read);
        ctx.checkEquals(name, read.json().getString(at + ".name"), "the contact's name", read);
        ctx.checkEquals(language, read.json().getString(at + ".language"), "the contact's language", read);
    }

    private static void removeContactAndReadGone(SweepContext ctx, SweepIdentity as, int id) {
        ctx.ok(as, SweepRequest.delete("/rest/contacts/contact/{id}").var("id", id));
        SweepResponse read = ctx.ok("admin", SweepRequest.get("/rest/contacts/search").query("name", "sweep-system")
                .query("maxSize", 1000).note("read after the delete"));
        ctx.check(read.json().get("data.find { it.id == " + id + " }") == null, "the contact " + id + " is gone",
                read);
    }

    // Script resources

    private static int createResource(SweepContext ctx, SweepIdentity as, String path, String content) {
        return ctx.ok(as, SweepRequest.post("/rest/script-resources/script-resource")
                .json(scriptResourceBody(null, path, content))).json().getInt("id");
    }

    private static void createResourceReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        String path = "templates/" + ctx.name("created-" + ctx.unique()) + ".ftl";
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(scriptResourceBody(null, path,
                "<#-- created -->")));
        if (!created.is2xx()) {
            return;
        }
        int id = created.json().getInt("id");
        try {
            readResourceBack(ctx, id, "FM", path, "<#-- created -->");
            SweepResponse history = ctx.ok("sysadmin", history(id));
            ctx.checkEquals("editor", history.json().getString("[0].source"), "the history's source", history);
        } finally {
            removeResourceAndReadGone(ctx, as, id);
        }
    }

    private static void readResourceBack(SweepContext ctx, int id, String type, String path, String content) {
        SweepResponse read = ctx.ok("admin", SweepRequest.get("/rest/script-resources/all"));
        if (!read.is2xx()) {
            return;
        }
        String at = "find { it.id == " + id + " }";
        ctx.checkEquals(type, read.json().getString(at + ".type"), "the resource's type", read);
        ctx.checkEquals(path, read.json().getString(at + ".path"), "the resource's path", read);
        ctx.checkEquals(content, read.json().getString(at + ".content"), "the resource's content", read);
    }

    private static void removeResourceAndReadGone(SweepContext ctx, SweepIdentity as, int id) {
        ctx.ok(as, SweepRequest.delete("/rest/script-resources/script-resource/{id}").var("id", id));
        SweepResponse read = ctx.ok("admin", SweepRequest.get("/rest/script-resources/all").note("read after the delete"));
        ctx.check(read.json().get("find { it.id == " + id + " }") == null, "the script resource " + id + " is gone",
                read);
        SweepResponse history = ctx.ok("sysadmin", history(id).note("the history after the delete"));
        ctx.checkEquals(List.of(), history.json().getList("$"), "the history of the deleted resource", history);
    }

    // The bootstrap archive

    /** The base product's bootstrap folder, zipped as the bootstrap command takes it (as BootstrapArchiveTest does) */
    static synchronized byte[] bootstrapArchive() {
        if (bootstrapArchive == null) {
            Path folder = Path.of("bootstrap");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes); Stream<Path> paths = Files.walk(folder)) {
                for (Path p : paths.sorted().toList()) {
                    String name = folder.relativize(p).toString().replace('\\', '/');
                    if (name.isEmpty()) {
                        continue;
                    }
                    if (Files.isDirectory(p)) {
                        zip.putNextEntry(new ZipEntry(name + "/"));
                    } else {
                        zip.putNextEntry(new ZipEntry(name));
                        zip.write(Files.readAllBytes(p));
                    }
                    zip.closeEntry();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            bootstrapArchive = bytes.toByteArray();
        }
        return bootstrapArchive;
    }
}
