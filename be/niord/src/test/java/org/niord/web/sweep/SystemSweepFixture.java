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

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.user.User;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.Tx;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fixture of {@link SystemSweepGroup}: its rows, looked up by name first (a second run in the same context makes
 * nothing twice) and removed by the tear-down, which reads each one gone.
 *
 * <p>Through the API, as the identity whose view the reads show: the batch instance (a harmless script run through
 * {@code execute-javascript}), the contact and the script resource with its history (created, then updated). Through
 * the services and the entity manager, where no endpoint can make or remove the row: the two S-124 messages (a
 * published one and a draft, stamped as {@code S124RestServiceITTest} does: no endpoint makes a published message
 * without the whole publish machinery, and no endpoint removes a message), the local user (users come from
 * Keycloak) and, in the tear-down, the batch instance's row and folder (no endpoint removes a batch instance).
 */
final class SystemSweepFixture {

    /** The rows of the group */
    static final String S124_PUBLISHED = "sweep-system-s124-published";
    static final String S124_DRAFT = "sweep-system-s124-draft";
    static final String BATCH_SCRIPT = "sweep-system-batch-script";
    static final String CONTACT = "sweep-system-contact";
    static final String SCRIPT_RESOURCE = "sweep-system-script-resource";
    static final String USER = "sweep-system-user";
    /** Names, not rows: the role a ticket is asked for (the value of each is the role) */
    static final String ROLE_USER = "sweep-system-role-user";
    static final String ROLE_EDITOR = "sweep-system-role-editor";
    static final String ROLE_ADMIN = "sweep-system-role-admin";
    static final String ROLE_SYSADMIN = "sweep-system-role-sysadmin";
    /**
     * A name, not a row: a cell on it is sent to the test realm's own Keycloak, as every cell of a Keycloak
     * handler is; it exists so that a cell of the sweep's own identity runs there too (the sweep itself runs
     * against {@code KeycloakAdminStub})
     */
    static final String KEYCLOAK_REALM = "sweep-system-keycloak-realm";

    static final Set<String> ROWS = Set.of(S124_PUBLISHED, S124_DRAFT, BATCH_SCRIPT, CONTACT, SCRIPT_RESOURCE, USER,
            ROLE_USER, ROLE_EDITOR, ROLE_ADMIN, ROLE_SYSADMIN, KEYCLOAK_REALM, SystemSweepKeycloak.KC_USER,
            SystemSweepKeycloak.KC_GROUP);

    /** The tables the group's rows (and the rows its write cases make and remove) live in */
    static final Set<String> TABLES = Set.of("BatchData", "Contact", "Message", "MessageDesc", "MessagePart",
            "MessagePartDesc", "MessageHistory", "ScriptResource", "ScriptResourceHistory", "Setting", "User");

    /** An id no row of the group's kinds has */
    static final int UNKNOWN = 987654321;

    static final String BATCH_SCRIPT_FILE = BATCH_SCRIPT + ".js";
    /** The fixture's script: harmless, and its text holds the row's name, so the download shows it */
    static final String BATCH_SCRIPT_TEXT = "var sweep = '" + BATCH_SCRIPT + "';\n1 + 1;\n";
    static final String CONTACT_EMAIL = CONTACT + "@example.org";
    static final String SCRIPT_RESOURCE_PATH = "templates/" + SCRIPT_RESOURCE + ".ftl";
    static final String SCRIPT_RESOURCE_V1 = "<#-- " + SCRIPT_RESOURCE + " v1 -->";
    /** The second version includes a template no row has, so the missing-path report names it */
    static final String SCRIPT_RESOURCE_V2 = "<#-- " + SCRIPT_RESOURCE + " v2 --><#include \"sweep-system-missing.ftl\">";
    static final String MISSING_PATH = "templates/sweep-system-missing.ftl";
    static final String USER_EMAIL = USER + "@example.org";

    /** The execution of the fixture's batch instance */
    long batchExecution;

    /** The first log file of the fixture's batch instance */
    private String firstLogName;

    // ------------------------------------------------------------------------------------------- //
    // Set-up
    // ------------------------------------------------------------------------------------------- //

    void setUp(SweepContext ctx) throws Exception {
        s124Messages(ctx);
        batchInstance(ctx);
        contact(ctx);
        scriptResource(ctx);
        user(ctx);
        ctx.putRow(ROLE_USER, "user");
        ctx.putRow(ROLE_EDITOR, "editor");
        ctx.putRow(ROLE_ADMIN, "admin");
        ctx.putRow(ROLE_SYSADMIN, "sysadmin");
        ctx.putRow(KEYCLOAK_REALM, "realm");
        // The Keycloak stub's user and group: names the stub is loaded with in each case, no table holds them
        ctx.putRow(SystemSweepKeycloak.KC_USER, SystemSweepKeycloak.KC_USER_ID);
        ctx.putRow(SystemSweepKeycloak.KC_GROUP, SystemSweepKeycloak.KC_GROUP_ID);
        SystemSweepKeycloak.rememberTheSetting(ctx);
    }

    private void s124Messages(SweepContext ctx) {
        EntityManager em = em(ctx);
        MessageService messages = ctx.bean(MessageService.class);
        for (String[] row : List.of(new String[]{S124_PUBLISHED, "PUBLISHED", "931"},
                new String[]{S124_DRAFT, "DRAFT", "932"})) {
            String uid = Tx.requiringNew().call(() -> {
                List<Message> found = em.createQuery("select m from Message m where m.shortId = :s", Message.class)
                        .setParameter("s", row[0]).getResultList();
                if (!found.isEmpty()) {
                    return found.getFirst().getUid();
                }
                try {
                    Message created = messages.createMessage(s124Draft(row[0]));
                    created.setNumber(Integer.parseInt(row[2]));
                    created.setShortId(row[0]);
                    created.checkUpdateYear();
                    created.setStatus(Status.valueOf(row[1]));
                    em.flush();
                    return created.getUid();
                } catch (Exception e) {
                    throw new IllegalStateException("Could not make the S-124 message " + row[0], e);
                }
            });
            // The published message is read by its short id (its GML's id), the draft by its UID (S124-5)
            ctx.putRow(row[0], row[0].equals(S124_PUBLISHED) ? row[0] : uid);
        }
    }

    /** A detached NW draft with one English details part, as S124RestServiceITTest builds it */
    private static Message s124Draft(String title) {
        Message msg = new Message();
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(Type.COASTAL_WARNING.getMainType());
        msg.setMessageSeries(series);
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        msg.setPublishDateFrom(c.getTime());
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject(title);
        pd.setDetails("<p>" + title + "</p>");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    private void batchInstance(SweepContext ctx) throws Exception {
        SystemSweepBatch.Job job = SystemSweepBatch.find(ctx, BATCH_SCRIPT_FILE);
        if (job == null) {
            job = SystemSweepBatch.runScript(ctx, "sysadmin", BATCH_SCRIPT_FILE, BATCH_SCRIPT_TEXT);
            SystemSweepBatch.awaitEnd(ctx, job.execution());
        }
        ctx.putRow(BATCH_SCRIPT, job.instance());
        batchExecution = job.execution();
        List<String> logs = ctx.ok("sysadmin", SystemSweepBatch.logs(job.instance())).json().getList("$", String.class);
        firstLogName = logs.isEmpty() ? null : logs.getFirst();
    }

    /** The first log file of the fixture's batch instance */
    String firstLogName(SweepContext ctx) {
        if (firstLogName == null) {
            throw new IllegalStateException("The fixture's batch instance " + ctx.row(BATCH_SCRIPT) + " has no log file");
        }
        return firstLogName;
    }

    private void contact(SweepContext ctx) {
        Integer id = contactId(ctx, CONTACT_EMAIL);
        if (id == null) {
            id = ctx.ok("admin", SweepRequest.post("/rest/contacts/contact").json(contactBody(null, CONTACT_EMAIL,
                    CONTACT, "en"))).json().getInt("id");
        }
        ctx.putRow(CONTACT, id);
    }

    private void scriptResource(SweepContext ctx) {
        Integer id = scriptResourceId(ctx, SCRIPT_RESOURCE_PATH);
        if (id == null) {
            id = ctx.ok("sysadmin", SweepRequest.post("/rest/script-resources/script-resource")
                    .json(scriptResourceBody(null, SCRIPT_RESOURCE_PATH, SCRIPT_RESOURCE_V1))).json().getInt("id");
            // A second version, so the history holds two entries
            ctx.ok("sysadmin", SweepRequest.put("/rest/script-resources/script-resource/{id}").var("id", id)
                    .json(scriptResourceBody(id, SCRIPT_RESOURCE_PATH, SCRIPT_RESOURCE_V2)));
        }
        ctx.putRow(SCRIPT_RESOURCE, id);
    }

    private void user(SweepContext ctx) {
        EntityManager em = em(ctx);
        Integer id = Tx.requiringNew().call(() -> {
            List<User> found = em.createNamedQuery("User.findByUsername", User.class).setParameter("username", USER)
                    .getResultList();
            if (!found.isEmpty()) {
                return found.getFirst().getId();
            }
            User user = new User();
            user.setUsername(USER);
            user.setEmail(USER_EMAIL);
            user.setFirstName(USER);
            user.setLanguage("en");
            em.persist(user);
            em.flush();
            return user.getId();
        });
        ctx.putRow(USER, id);
    }

    // ------------------------------------------------------------------------------------------- //
    // Tear-down
    // ------------------------------------------------------------------------------------------- //

    List<String> tearDown(SweepContext ctx) {
        List<String> left = new ArrayList<>();
        SystemSweepKeycloak.restoreTheSetting(ctx);
        EntityManager em = em(ctx);

        if (ctx.hasRow(CONTACT)) {
            ctx.send("admin", SweepRequest.delete("/rest/contacts/contact/{id}").var("id", ctx.row(CONTACT)));
            if (contactId(ctx, CONTACT_EMAIL) != null) {
                left.add(CONTACT + " (" + CONTACT_EMAIL + ", still found by the search)");
            }
        }
        if (ctx.hasRow(SCRIPT_RESOURCE)) {
            ctx.send("sysadmin", SweepRequest.delete("/rest/script-resources/script-resource/{id}")
                    .var("id", ctx.row(SCRIPT_RESOURCE)));
            if (scriptResourceId(ctx, SCRIPT_RESOURCE_PATH) != null) {
                left.add(SCRIPT_RESOURCE + " (" + SCRIPT_RESOURCE_PATH + ", still listed)");
            }
        }
        if (ctx.hasRow(BATCH_SCRIPT)) {
            long instance = ((Number) ctx.row(BATCH_SCRIPT)).longValue();
            if (!SystemSweepBatch.removeAndReadGone(ctx, instance)) {
                left.add(BATCH_SCRIPT + " (batch instance " + instance + ", its logs still read)");
            }
        }
        for (String row : List.of(S124_PUBLISHED, S124_DRAFT)) {
            Tx.requiringNew().run(() -> {
                for (Message m : em.createQuery("select m from Message m where m.shortId = :s", Message.class)
                        .setParameter("s", row).getResultList()) {
                    em.createQuery("delete from MessageHistory h where h.message.id = :id")
                            .setParameter("id", m.getId()).executeUpdate();
                    em.remove(m);
                }
            });
            // Read gone in the database: the S-124 read of a missing message logs an ERROR (as the old system's did)
            Long count = Tx.requiringNew().call(() -> em.createQuery(
                    "select count(m) from Message m where m.shortId = :s", Long.class)
                    .setParameter("s", row).getSingleResult());
            if (count != 0) {
                left.add(row + " (" + count + " message rows after the removal)");
            }
        }
        if (ctx.hasRow(USER)) {
            removeUser(ctx, USER);
            if (userId(ctx, USER) != null) {
                left.add(USER + " (still found by its user name)");
            }
        }
        return left;
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    static EntityManager em(SweepContext ctx) {
        return SharedEntityManagerCreator.createSharedEntityManager(ctx.bean(EntityManagerFactory.class));
    }

    /** A map that keeps its order and takes null values */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    static Map<String, Object> contactBody(Integer id, String email, String name, String language) {
        return map("id", id, "email", email, "name", name, "language", language);
    }

    /** The id of the contact of the address, or null */
    static Integer contactId(SweepContext ctx, String email) {
        SweepResponse found = ctx.ok("admin", SweepRequest.get("/rest/contacts/search").query("name", email));
        return found.json().get("data.find { it.email == '" + email + "' }.id");
    }

    static Map<String, Object> scriptResourceBody(Integer id, String path, String content) {
        return map("id", id, "type", path.endsWith(".js") ? "JS" : "FM", "path", path, "content", content);
    }

    /** The id of the script resource of the path, or null */
    static Integer scriptResourceId(SweepContext ctx, String path) {
        SweepResponse all = ctx.ok("admin", SweepRequest.get("/rest/script-resources/all"));
        return all.json().get("find { it.path == '" + path + "' }.id");
    }

    /** The id of the local user of the user name, or null */
    static Integer userId(SweepContext ctx, String username) {
        EntityManager em = em(ctx);
        return Tx.requiringNew().call(() -> em.createNamedQuery("User.findByUsername", User.class)
                .setParameter("username", username).getResultList().stream().map(User::getId).findFirst()
                .orElse(null));
    }

    /** Removes the local user of the user name (no endpoint removes a user's record) */
    static void removeUser(SweepContext ctx, String username) {
        EntityManager em = em(ctx);
        Tx.requiringNew().run(() -> em.createNamedQuery("User.findByUsername", User.class)
                .setParameter("username", username).getResultList().forEach(em::remove));
    }
}
