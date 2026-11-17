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

import java.util.List;
import java.util.Map;

import static org.niord.web.sweep.DeliverySweepFixture.*;

/**
 * The cases of {@code MailingListRestService} for {@link DeliverySweepGroup}. The recipient writes run on a list the
 * case makes and removes (the fixture's list keeps no recipient: a contact on a list cannot be deleted, CONTACT1), and
 * the list goes before the contact, so the case never meets CONTACT1.
 */
final class DeliverySweepMailingLists {

    private DeliverySweepMailingLists() {
    }

    static void cases(SweepCases cases) {
        cases.add("searchMailingLists", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/mailing-lists/search").query("name", LIST).query("lang", "en"))
                .variants((ctx, as) -> {
                    ctx.expectRows(ctx.ok(as, SweepRequest.get("/rest/mailing-lists/search").query("lang", "da")
                            .query("name", LIST + "-da")), null, LIST + "-da");
                    SweepResponse none = ctx.ok(as, SweepRequest.get("/rest/mailing-lists/search")
                            .query("contactEmail", CONTACT_EMAIL));
                    ctx.checkEquals(List.of(), none.json().getList("$"), "the lists the fixture's contact is on", none);
                }));

        cases.add("getMailingListDetails", SweepCase
                .of((ctx, row) -> mailingList(LIST))
                .variants((ctx, as) -> ctx.expectRows(ctx.ok(as, mailingList(LIST).query("includeRecipients", true)),
                        null, LIST))
                .probes((ctx, as) -> {
                    String unknown = ctx.name("no-such-list");
                    ctx.call(as.name(), mailingList(unknown).note("unknown-id probe"),
                            Cell.status(404, "Mailing list " + unknown + " not found"));
                    ctx.call(as.name(), mailingList(LIST).query("includeRecipients", "maybe")
                            .note("malformed includeRecipients"),
                            Cell.status(400, "Failed to convert 'includeRecipients' with value: 'maybe'"));
                }));

        cases.add("addMailingList", SweepCase
                // A refused cell sends a list without an id: a gate that let it through answers 400
                .of((ctx, row) -> SweepRequest.post("/rest/mailing-lists/mailing-list")
                        .json(listBody(null, List.of())))
                .sweep((ctx, as) -> createListReadBackAndRemove(ctx, as, "/rest/mailing-lists/mailing-list"))
                .variants((ctx, as) -> createListReadBackAndRemove(ctx, as, "/rest/mailing-lists/mailing-list/"))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/mailing-lists/mailing-list")
                        .json(Map.of()).note("empty body"), Cell.status(400, "A mailing list needs a mailing list ID"))));

        cases.add("updateMailingList", SweepCase
                // The body's id is not the path's: a gate that let it through answers 400 and changes nothing
                .of((ctx, row) -> SweepRequest.put("/rest/mailing-lists/mailing-list/{mailingListId}")
                        .var("mailingListId", LIST).json(listBody(ctx.name("other"), List.of())))
                .sweep((ctx, as) -> {
                    String id = createList(ctx, as, "/rest/mailing-lists/mailing-list", List.of());
                    if (id == null) {
                        return;
                    }
                    try {
                        Map<String, Object> trigger = statusTrigger(ctx.name("updated-subject"));
                        Map<String, Object> changed = map("mailingListId", id, "active", false,
                                "descs", List.of(Map.of("lang", "en", "name", id + "-renamed", "description", "changed"),
                                        Map.of("lang", "da", "name", id + "-omdøbt", "description", "ændret")),
                                "triggers", List.of(trigger));
                        ctx.ok(as, SweepRequest.put("/rest/mailing-lists/mailing-list/{mailingListId}")
                                .var("mailingListId", id).json(changed));
                        readListBack(ctx, id, id + "-renamed", "changed", id + "-omdøbt", trigger);
                    } finally {
                        removeListAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put("/rest/mailing-lists/mailing-list/{mailingListId}")
                        .var("mailingListId", LIST).json(listBody(ctx.name("other"), List.of()))
                        .note("the body's id is not the path's"), Cell.status(400, null))));

        cases.add("deleteMailingList", SweepCase
                // A refused cell names no list: a gate that let it through removes nothing
                .of((ctx, row) -> SweepRequest.delete("/rest/mailing-lists/mailing-list/{mailingListId}")
                        .var("mailingListId", ctx.name("no-such-list")))
                .sweep((ctx, as) -> {
                    String id = createList(ctx, as, "/rest/mailing-lists/mailing-list", List.of());
                    if (id != null) {
                        removeListAndReadGone(ctx, as, id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete(
                        "/rest/mailing-lists/mailing-list/{mailingListId}").var("mailingListId", ctx.name("no-such-list"))
                        .note("unknown id: the service removes what is there"), Cell.status(204, null))));

        cases.add("getRecipientUsers", SweepCase
                .of((ctx, row) -> recipients(LIST, "users"))
                .sweep((ctx, as) -> {
                    // The fixture's list has no user; the realm's test users (with addresses) are offered
                    SweepResponse users = ctx.ok(as, recipients(LIST, "users"));
                    if (!users.is2xx()) {
                        return;
                    }
                    ctx.checkEquals(List.of(), users.json().getList("selectedRecipients"), "the fixture list's users",
                            users);
                    List<Object> emails = users.json().getList("availableRecipients.email");
                    ctx.check(emails != null && !emails.isEmpty() && emails.stream().allMatch(e -> e != null
                            && !String.valueOf(e).isBlank()), "the list offers users, each with an e-mail address",
                            users);
                }));

        cases.add("updateRecipientUsers", SweepCase
                // A refused cell sends the fixture list's recipients as they are (none)
                .of((ctx, row) -> putRecipients(LIST, "users", List.of()))
                .sweep((ctx, as) -> {
                    String id = createList(ctx, "sysadmin", List.of());
                    if (id == null) {
                        return;
                    }
                    try {
                        Map<String, Object> user = aUser(ctx, as, id);
                        if (user == null) {
                            return;
                        }
                        ctx.ok(as, putRecipients(id, "users", List.of(user)));
                        SweepResponse read = ctx.ok(as, recipients(id, "users"));
                        ctx.checkEquals(List.of(user.get("username")), read.json().getList("selectedRecipients.username"),
                                "the list's users", read);
                        ctx.ok(as, putRecipients(id, "users", List.of()));
                        SweepResponse gone = ctx.ok(as, recipients(id, "users").note("read after the removal"));
                        ctx.checkEquals(List.of(), gone.json().getList("selectedRecipients"), "the list's users after "
                                + "the removal", gone);
                    } finally {
                        removeListAndReadGone(ctx, SweepIdentity.parse("sysadmin"), id);
                    }
                }));

        cases.add("getRecipientContacts", SweepCase
                .of((ctx, row) -> recipients(LIST, "contacts")));

        cases.add("updateRecipientContacts", SweepCase
                .of((ctx, row) -> putRecipients(LIST, "contacts", List.of()))
                .sweep((ctx, as) -> {
                    String id = createList(ctx, "sysadmin", List.of());
                    if (id == null) {
                        return;
                    }
                    try {
                        Map<String, Object> contact = theContact(ctx, as, id);
                        if (contact == null) {
                            return;
                        }
                        ctx.ok(as, putRecipients(id, "contacts", List.of(contact)));
                        SweepResponse read = ctx.ok(as, recipients(id, "contacts"));
                        ctx.checkEquals(List.of(CONTACT_EMAIL), read.json().getList("selectedRecipients.email"),
                                "the list's contacts", read);
                        ctx.ok(as, putRecipients(id, "contacts", List.of()));
                        SweepResponse gone = ctx.ok(as, recipients(id, "contacts").note("read after the removal"));
                        ctx.checkEquals(List.of(), gone.json().getList("selectedRecipients"), "the list's contacts "
                                + "after the removal", gone);
                    } finally {
                        removeListAndReadGone(ctx, SweepIdentity.parse("sysadmin"), id);
                    }
                }));

        cases.add("updateRecipientStatus", SweepCase
                // A refused cell takes the contact off the fixture list, which it is not on: nothing changes
                .of((ctx, row) -> SweepRequest.put("/rest/mailing-lists/mailing-list/{mailingListId}/update-status")
                        .var("mailingListId", LIST).json(Map.of("contact", Map.of("email", CONTACT_EMAIL),
                                "recipient", false)))
                .sweep((ctx, as) -> {
                    String id = createList(ctx, "sysadmin", List.of());
                    if (id == null) {
                        return;
                    }
                    try {
                        Map<String, Object> contact = theContact(ctx, as, id);
                        if (contact == null) {
                            return;
                        }
                        SweepResponse added = ctx.ok(as, status(id, contact, true));
                        ctx.checkEquals("true", added.text().trim(), "the answer to adding the contact", added);
                        SweepResponse read = ctx.ok(as, recipients(id, "contacts"));
                        ctx.checkEquals(List.of(CONTACT_EMAIL), read.json().getList("selectedRecipients.email"),
                                "the list's contacts", read);
                        SweepResponse removed = ctx.ok(as, status(id, contact, false));
                        ctx.checkEquals("true", removed.text().trim(), "the answer to removing the contact", removed);
                        SweepResponse gone = ctx.ok(as, recipients(id, "contacts").note("read after the removal"));
                        ctx.checkEquals(List.of(), gone.json().getList("selectedRecipients"), "the list's contacts "
                                + "after the removal", gone);
                    } finally {
                        removeListAndReadGone(ctx, SweepIdentity.parse("sysadmin"), id);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.put(
                        "/rest/mailing-lists/mailing-list/{mailingListId}/update-status").var("mailingListId", LIST)
                        .json(Map.of("recipient", true)).note("neither user nor contact"),
                        Cell.status(400, "User or contact must be specified"))));

        cases.add("exportMailingLists", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/mailing-lists/export"))
                .as("sysadmin"));

        cases.add("importMailingLists", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/mailing-lists/upload-mailing-lists")
                        .file("file", ctx.name("refused") + ".json", "[]".getBytes(), "application/json"))
                .sweep((ctx, as) -> {
                    String id = ctx.name("uploaded-" + ctx.unique());
                    String json = "[{\"mailingListId\":\"" + id + "\",\"active\":false,\"descs\":[{\"lang\":\"en\","
                            + "\"name\":\"" + id + "\",\"description\":\"uploaded\"}],\"triggers\":[]}]";
                    String file = ctx.jobs().upload(ctx, as.name(),
                            SweepRequest.post("/rest/mailing-lists/upload-mailing-lists"), json);
                    ctx.jobs().awaitClean(ctx, "mailing-list-import", file);
                    SweepResponse read = ctx.send("admin", mailingList(id));
                    if (!read.is2xx()) {
                        ctx.fail(read, "the uploaded mailing list " + id + " is found after its job completed");
                        return;
                    }
                    try {
                        ctx.checkEquals(false, read.json().getBoolean("active"), "the uploaded list's active", read);
                        ctx.checkEquals(id, read.json().getString("descs.find { it.lang == 'en' }.name"),
                                "the uploaded list's English name", read);
                        ctx.checkEquals("uploaded", read.json().getString("descs.find { it.lang == 'en' }.description"),
                                "the uploaded list's English description", read);
                    } finally {
                        removeListAndReadGone(ctx, as, id);
                    }
                }));

        cases.add("testExecuteTrigger", SweepCase
                // A cell's request is a list nobody saved, without recipients: it renders no mail
                .of((ctx, row) -> SweepRequest.post("/rest/mailing-lists/test-execute-trigger")
                        .query("triggerIndex", 0).query("messageId", "NW-001-26")
                        .json(listBody(ctx.name("unsaved"), List.of(statusTrigger(ctx.name("test-subject"))))))
                .sweep((ctx, as) -> {
                    // A saved list with the fixture's contact: the test adds the saved recipients and sends nothing
                    String id = createList(ctx, "sysadmin", List.of());
                    if (id == null) {
                        return;
                    }
                    try {
                        Map<String, Object> contact = theContact(ctx, SweepIdentity.parse("admin"), id);
                        if (contact == null) {
                            return;
                        }
                        ctx.ok("admin", putRecipients(id, "contacts", List.of(contact)));
                        String subject = ctx.name("test-subject");
                        SweepResponse mails = ctx.ok(as, SweepRequest.post("/rest/mailing-lists/test-execute-trigger")
                                .query("triggerIndex", 0).query("messageId", "NW-001-26")
                                .json(listBody(id, List.of(statusTrigger(subject)))));
                        if (!mails.is2xx()) {
                            return;
                        }
                        ctx.checkEquals(1, mails.json().getList("$").size(), "one mail for the list's one contact",
                                mails);
                        ctx.expectRows(mails, "recipients.address", CONTACT);
                        ctx.check(mails.json().getString("[0].contents").contains("NW-001-26"), "the mail names the "
                                + "message NW-001-26", mails);
                        SweepResponse queued = ctx.ok("sysadmin", SweepRequest.get("/rest/scheduled-mails/search")
                                .query("recipient", CONTACT_EMAIL));
                        ctx.checkEquals(0, queued.json().getInt("total"), "the test queues no mail", queued);
                    } finally {
                        removeListAndReadGone(ctx, SweepIdentity.parse("sysadmin"), id);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/mailing-lists/test-execute-trigger")
                                    .query("triggerIndex", 1).json(listBody(ctx.name("unsaved"),
                                            List.of(statusTrigger("x")))).note("a trigger index past the list"),
                            Cell.status(400, "triggerIndex must be a number from 0 to 0"));
                    ctx.call(as.name(), SweepRequest.post("/rest/mailing-lists/test-execute-trigger")
                                    .query("triggerIndex", 0).json(listBody(ctx.name("unsaved"), List.of()))
                                    .note("a list without triggers"),
                            Cell.status(400, "The mailing list has no trigger to test"));
                }));

        cases.add("getMailingListReports", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/mailing-lists/reports").query("lang", "en")));

        cases.add("executeMailingListReport", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/mailing-lists/execute-report/{triggerId}.html")
                        .var("triggerId", ctx.row(REPORT)).query("lang", "en"))
                .as("user")
                .sweep((ctx, as) -> {
                    SweepResponse report = ctx.ok(as, SweepRequest.get(
                            "/rest/mailing-lists/execute-report/{triggerId}.html").var("triggerId", ctx.row(REPORT))
                            .query("lang", "en"));
                    ctx.check("sandbox".equals(report.header("Content-Security-Policy")), "the report is served "
                            + "sandboxed", report);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.get("/rest/mailing-lists/execute-report/{triggerId}.html")
                            .var("triggerId", UNKNOWN).note("unknown-id probe"),
                            Cell.status(400, "Trigger " + UNKNOWN + " cannot be used as a public report"));
                    ctx.call(as.name(), SweepRequest.get("/rest/mailing-lists/execute-report/{triggerId}.html")
                            .var("triggerId", "abc").note("malformed id"),
                            Cell.status(400, "Failed to convert 'triggerId' with value: 'abc'"));
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    static SweepRequest recipients(Object listId, String kind) {
        return SweepRequest.get("/rest/mailing-lists/mailing-list/{mailingListId}/" + kind)
                .var("mailingListId", listId);
    }

    static SweepRequest putRecipients(Object listId, String kind, Object body) {
        return SweepRequest.put("/rest/mailing-lists/mailing-list/{mailingListId}/" + kind)
                .var("mailingListId", listId).json(body);
    }

    private static SweepRequest status(String listId, Map<String, Object> contact, boolean recipient) {
        return SweepRequest.put("/rest/mailing-lists/mailing-list/{mailingListId}/update-status")
                .var("mailingListId", listId).json(Map.of("contact", contact, "recipient", recipient));
    }

    /** A status-change trigger that renders the NW publication mail, with the subject */
    static Map<String, Object> statusTrigger(String subject) {
        return map("type", "STATUS_CHANGE", "statusChanges", List.of("PUBLISHED"),
                "messageFilter", "msg.messageSeries.seriesId == 'dma-nw'",
                "scriptResourcePaths", List.of("templates/mailinglist/publish-navwarn.ftl"),
                "descs", List.of(Map.of("lang", "en", "subject", subject)));
    }

    /** Creates an inactive list as the identity; answers its id */
    private static String createList(SweepContext ctx, String identity, List<Map<String, Object>> triggers) {
        return createList(ctx, SweepIdentity.parse(identity), "/rest/mailing-lists/mailing-list", triggers);
    }

    private static String createList(SweepContext ctx, SweepIdentity as, String pattern,
                                     List<Map<String, Object>> triggers) {
        String id = ctx.name("list-" + ctx.unique());
        SweepResponse created = ctx.ok(as, SweepRequest.post(pattern).json(listBody(id, triggers)));
        return created.is2xx() ? id : null;
    }

    /** The write sweep of the create: create with a trigger, read back every field sent, remove, read gone */
    private static void createListReadBackAndRemove(SweepContext ctx, SweepIdentity as, String pattern) {
        Map<String, Object> trigger = statusTrigger(ctx.name("subject"));
        String id = createList(ctx, as, pattern, List.of(trigger));
        if (id == null) {
            return;
        }
        try {
            readListBack(ctx, id, id, "The sweep's list " + id, id + "-da", trigger);
        } finally {
            removeListAndReadGone(ctx, as, id);
        }
    }

    @SuppressWarnings("unchecked")
    private static void readListBack(SweepContext ctx, String id, String enName, String enDescription, String daName,
                                     Map<String, Object> trigger) {
        SweepResponse read = ctx.ok("admin", mailingList(id));
        if (!read.is2xx()) {
            return;
        }
        JsonPath json = read.json();
        ctx.checkEquals(id, json.getString("mailingListId"), "the mailing list id", read);
        ctx.checkEquals(false, json.getBoolean("active"), "active", read);
        ctx.checkEquals(enName, json.getString("descs.find { it.lang == 'en' }.name"), "the English name", read);
        ctx.checkEquals(enDescription, json.getString("descs.find { it.lang == 'en' }.description"),
                "the English description", read);
        ctx.checkEquals(daName, json.getString("descs.find { it.lang == 'da' }.name"), "the Danish name", read);
        ctx.checkEquals(1, json.getList("triggers").size(), "the number of triggers", read);
        ctx.checkEquals(trigger.get("type"), json.getString("triggers[0].type"), "the trigger's type", read);
        ctx.checkEquals(trigger.get("statusChanges"), json.getList("triggers[0].statusChanges"),
                "the trigger's status changes", read);
        ctx.checkEquals(trigger.get("messageFilter"), json.getString("triggers[0].messageFilter"),
                "the trigger's message filter", read);
        ctx.checkEquals(trigger.get("scriptResourcePaths"), json.getList("triggers[0].scriptResourcePaths"),
                "the trigger's scripts", read);
        String subject = (String) ((List<Map<String, Object>>) trigger.get("descs")).getFirst().get("subject");
        ctx.checkEquals(subject, json.getString("triggers[0].descs.find { it.lang == 'en' }.subject"),
                "the trigger's English subject", read);
    }

    private static void removeListAndReadGone(SweepContext ctx, SweepIdentity as, String id) {
        SweepIdentity remover = as.roles().contains("sysadmin") ? as : SweepIdentity.parse("sysadmin");
        ctx.ok(remover, SweepRequest.delete("/rest/mailing-lists/mailing-list/{mailingListId}")
                .var("mailingListId", id));
        ctx.call("admin", mailingList(id).note("read after the delete"),
                Cell.status(404, "Mailing list " + id + " not found"));
    }

    /** A user the list offers (with an e-mail address), as the list's users read gives it */
    private static Map<String, Object> aUser(SweepContext ctx, SweepIdentity as, String listId) {
        SweepResponse users = ctx.ok(as, recipients(listId, "users"));
        List<Map<String, Object>> available = users.json().getList("availableRecipients");
        if (available == null || available.isEmpty()) {
            ctx.fail(users, "the list offers a user with an e-mail address");
            return null;
        }
        return available.stream().filter(u -> "testuser".equals(u.get("username"))).findFirst()
                .orElse(available.getFirst());
    }

    /** The fixture's contact, as the list's contacts read gives it */
    private static Map<String, Object> theContact(SweepContext ctx, SweepIdentity as, String listId) {
        SweepResponse contacts = ctx.ok(as, recipients(listId, "contacts"));
        Map<String, Object> contact = contacts.json().get("availableRecipients.find { it.email == '"
                + CONTACT_EMAIL + "' }");
        if (contact == null) {
            ctx.fail(contacts, "the list offers the fixture's contact " + CONTACT_EMAIL);
        }
        return contact;
    }
}
