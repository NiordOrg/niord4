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

import io.restassured.specification.RequestSpecification;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.promulgation.NavtexMessagePromulgation;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.BatchJobs;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for {@link MessageExportRestService}: the HTTP contract and authorization of the
 * {@code /rest/message-io} endpoints.
 *
 * <p><b>Authorization model under test.</b> The class itself is {@code @PermitAll}, but each endpoint
 * has its own contract. {@code GET /export.zip} is genuinely {@code @PermitAll}: an anonymous caller
 * reaches the handler and receives the ZIP archive of the (possibly empty) search result. The
 * {@code POST /import} multipart endpoint is {@code @RolesAllowed(ADMIN)}: a request with no token is
 * rejected by the container with {@code 401}, and an authenticated identity one tier too low (an
 * {@code editor}) is rejected with {@code 403}.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}). The seeded identities (see {@link KeycloakTestTokens}) give one
 * token per tier. Every authenticated request carries {@code NiordDomain: niord-nw} so the domain
 * augmentor realizes the full role chain. A missing token yields the container's {@code 401} on the
 * {@code @RolesAllowed(ADMIN)} import endpoint; an authenticated {@code editor} (one tier too low) yields
 * {@code 403}.
 *
 * <p><b>Export oracle.</b> {@code GET /export.zip} streams {@code application/zip} with a fixed
 * {@code Content-Disposition: attachment; filename="messages.zip"} header regardless of caller, so the
 * test asserts {@code 200}, the content type, and that the disposition header names {@code messages.zip}.
 * The archive's {@code messages.json} is read back to check its shape: an anonymous caller gets the public
 * details without promulgations or the unacknowledged-comment count, a signed-in caller keeps the
 * promulgations, and so does the role-less ticket the admin export sends.
 *
 * <p><b>Import validation oracle.</b> {@code AbstractBatchableRestService.executeBatchJobFromUploadedFile}
 * invokes {@code checkBatchJob} as a guard <em>before</em> launching any batch job. The guard streams the
 * uploaded zip looking for a {@code messages.json} entry; an arbitrary (non-zip) byte payload fails that
 * check, so {@code checkBatchJob} refuses it with HTTP {@code 400}. The body mentions the missing
 * {@code messages.json} entry, and no batch job is started for an invalid upload (hence nothing to clean up).
 * A valid archive passes the guard and starts the {@code msg-archive-import} job, which reads the upload
 * through a stream of its own; an archive holding no message lets that job complete without importing
 * anything, and its batch row is removed afterwards. Two archives in one upload each start and complete
 * their own job. A form that names a PUBLIC tag of another domain is refused with 403 before any job starts (TAG3).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageExportRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String EXPORT_ZIP = "/rest/message-io/export.zip";
    private static final String IMPORT     = "/rest/message-io/import";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /export.zip — genuinely @PermitAll: anonymous caller gets the zip stream with a fixed
    // attachment filename and an application/zip content type.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exportZip_allowsAnonymousAndStreamsZipArchive() {
        given().when().get(EXPORT_ZIP)
                .then().statusCode(200)
                .contentType("application/zip")
                .header("Content-Disposition", containsString("messages.zip"));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /export.zip — the JSON shape. An anonymous export carries the public message details: no
    // promulgations (internal texts and settings) and no unacknowledged-comment count. A signed-in
    // export keeps the promulgations, which the archive import reads back.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exportZip_anonymousArchiveCarriesNoPromulgationsOrCommentCount() throws Exception {
        String token = "zzexp" + System.nanoTime();
        Message published = createPublishedWithNavtex(token);
        MessageTag tag = createPublicTag("IT-export-anon-" + token, published);

        byte[] zip = given()
                .queryParam("tag", tag.getTagId())
                .when().get(EXPORT_ZIP)
                .then().statusCode(200)
                .extract().asByteArray();

        JsonNode message = exportedMessage(zip, published.getUid());
        assertFalse(message.has("promulgations"), "an anonymous export must not carry promulgations");
        assertFalse(message.has("unackComments"), "an anonymous export must not carry the comment count");
        assertFalse(readEntry(zip, "messages-preview.html").contains(token + " NAVTEX"),
                "the preview page is built from the same JSON and carries no promulgation text either");
    }

    @Test
    void exportZip_signedInArchiveKeepsPromulgations() throws Exception {
        String token = "zzexp" + System.nanoTime();
        Message published = createPublishedWithNavtex(token);
        MessageTag tag = createPublicTag("IT-export-auth-" + token, published);

        byte[] zip = as(accessToken())
                .queryParam("tag", tag.getTagId())
                .when().get(EXPORT_ZIP)
                .then().statusCode(200)
                .extract().asByteArray();

        JsonNode message = exportedMessage(zip, published.getUid());
        assertTrue(message.has("promulgations"), "a signed-in export keeps the promulgations");
        assertTrue(message.get("promulgations").toString().contains(token + " NAVTEX"),
                "the NAVTEX text is exported for a signed-in caller");
    }

    @Test
    void exportZip_roleLessTicketArchiveKeepsPromulgations() throws Exception {
        // The admin export is a plain navigation carrying a role-less ticket and no token. The ticket
        // names the signed-in user but grants no role, so the shape must follow the caller's identity,
        // not the USER role: the archive import reads the promulgations back from this JSON.
        String token = "zzexp" + System.nanoTime();
        Message published = createPublishedWithNavtex(token);
        MessageTag tag = createPublicTag("IT-export-ticket-" + token, published);
        String ticket = as(accessToken())
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        byte[] zip = given()
                .queryParam("ticket", ticket)
                .queryParam("tag", tag.getTagId())
                .when().get(EXPORT_ZIP)
                .then().statusCode(200)
                .extract().asByteArray();

        JsonNode message = exportedMessage(zip, published.getUid());
        assertTrue(message.has("promulgations"), "a ticketed staff export keeps the promulgations");
        assertTrue(message.get("promulgations").toString().contains(token + " NAVTEX"),
                "the NAVTEX text is exported for a ticketed staff caller");
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixtures (committed: the HTTP request runs in its own transaction)
    // ------------------------------------------------------------------------------------------- //

    @Autowired
    MessageService messageService;

    @Autowired
    MessageTagService messageTagService;

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @PersistenceContext
    EntityManager em;

    private final List<Integer> createdMessageIds = new ArrayList<>();

    private final List<String> createdTagIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String tagId : createdTagIds) {
            messageTagService.clearMessageTag(tagId);
            messageTagService.deleteMessageTag(tagId);
        }
        createdTagIds.clear();
        for (Integer id : createdMessageIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m); // cascades to the promulgations
                }
            });
        }
        createdMessageIds.clear();
    }

    /**
     * A committed, PUBLISHED dma-nw warning carrying a NAVTEX promulgation whose text contains the
     * token. The walk to PUBLISHED runs in one transaction (a year-2001 publish date keeps it off the
     * current-year number sequence), and the promulgation is added afterwards so no publish hook
     * touches it.
     */
    private Message createPublishedWithNavtex(String token) throws Exception {
        Message template = new Message();
        template.setMessageSeries(new MessageSeries("dma-nw"));
        template.setType(Type.COASTAL_WARNING);
        template.setAutoTitle(false);
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2001, java.util.Calendar.JUNE, 15, 12, 0, 0);
        template.setPublishDateFrom(c.getTime());
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        template.addPart(part);
        MessageDesc d = template.createDesc("en");
        d.setTitle("Export " + token);

        Message created = messageService.createMessage(template);
        createdMessageIds.add(created.getId());
        Tx.requiringNew().call(() -> {
            messageService.updateStatus(created.getUid(), Status.VERIFIED);
            return messageService.updateStatus(created.getUid(), Status.PUBLISHED);
        });
        Tx.requiringNew().run(() -> {
            Message m = em.find(Message.class, created.getId());
            NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
            navtex.setType(promulgationTypeService.getPromulgationType("navtex"));
            navtex.setPromulgate(true);
            navtex.setText(token + " NAVTEX");
            m.addPromulgation(navtex);
            em.persist(navtex);
        });
        return created;
    }

    /** A committed, ownerless PUBLIC tag holding the given message - reachable by an anonymous caller. */
    private MessageTag createPublicTag(String name, Message message) {
        MessageTag template = new MessageTag();
        template.setType(MessageTagType.PUBLIC);
        template.setName(name);
        MessageTag tag = messageTagService.createMessageTag(template);
        createdTagIds.add(tag.getTagId());
        messageTagService.addMessageToTag(tag.getTagId(), List.of(message.getUid()));
        return tag;
    }

    /** The exported JSON of the message with the given uid, from the archive's messages.json. */
    private static JsonNode exportedMessage(byte[] zip, String uid) throws Exception {
        JsonNode result = JsonMapper.builderWithJackson2Defaults().build().readTree(readEntry(zip, "messages.json"));
        for (JsonNode message : result.get("data")) {
            if (uid.equals(message.get("id").asText())) {
                return message;
            }
        }
        throw new AssertionError("message " + uid + " is not in the exported messages.json: " + result);
    }

    /** The UTF-8 text of the named ZIP entry. */
    private static String readEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (name.equals(entry.getName())) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("the archive has no " + name + " entry");
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /import — @RolesAllowed(ADMIN). No token -> container 401; editor (one tier too low) -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void import_rejectsAnonymousWith401() {
        given().multiPart("file", "bad.zip", new byte[]{1, 2, 3})
                .when().post(IMPORT)
                .then().statusCode(401);
    }

    @Test
    void import_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        as(accessToken("editor")).multiPart("file", "bad.zip", new byte[]{1, 2, 3})
                .when().post(IMPORT)
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /import — as ADMIN with a valid series but a zip lacking messages.json. The checkBatchJob
    // guard refuses it with 400, naming the missing entry; no batch job starts.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void import_asAdminWithInvalidZip_isRefusedWith400MentioningMessagesJson() {
        as(accessToken("admin"))
                .multiPart("data", "{\"seriesId\":\"dma-nw\"}")
                .multiPart("file", "bad.zip", new byte[]{1, 2, 3}, "application/zip")
                .when().post(IMPORT)
                .then().statusCode(400)
                .body(containsString("messages.json"));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /import — as ADMIN with a valid archive. The checkBatchJob guard reads the upload, and the
    // job it starts reads the upload again through a stream of its own (the guard used to close the
    // one stream the job then copied): an archive whose messages.json holds no message starts
    // msg-archive-import, which completes and imports nothing.
    // ------------------------------------------------------------------------------------------- //

    /** An empty search result as the export writes it into messages.json */
    private static final String EMPTY_MESSAGES_JSON = "{\"data\":[],\"total\":0,\"size\":0}";

    @Autowired
    JobOperator jobOperator;

    @Autowired
    BatchService batchService;

    @Test
    void import_asAdminWithValidArchive_startsTheImportJobWhichCompletes() throws Exception {
        String fileName = "it-msgio1-" + System.nanoTime() + ".zip";
        try {
            as(accessToken("admin"))
                    .multiPart("data", "{\"seriesId\":\"dma-nw\"}")
                    .multiPart("file", fileName, archive(EMPTY_MESSAGES_JSON), "application/zip")
                    .when().post(IMPORT)
                    .then().statusCode(200)
                    .body(containsString("Started batch job 'msg-archive-import'"));

            assertEquals(BatchStatus.COMPLETED, BatchJobs.awaitCompletion(jobOperator, importExecution(fileName)),
                    "the import job of " + fileName + " completes");
        } finally {
            removeImportRows(fileName);
        }
    }

    /** Removes the batch rows (and their folders) of the uploaded file */
    private void removeImportRows(String fileName) {
        Tx.requiringNew().run(() -> {
            for (BatchData row : em.createQuery("select b from BatchData b where b.dataFileName = :f", BatchData.class)
                    .setParameter("f", fileName)
                    .getResultList()) {
                FileUtils.deleteQuietly(batchService.computeBatchJobPath(row.computeBatchJobFolderPath()).toFile());
                em.remove(row);
            }
        });
    }

    /** The number of batch rows of the uploaded file */
    private long importRows(String fileName) {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select count(b) from BatchData b where b.dataFileName = :f", Long.class)
                .setParameter("f", fileName)
                .getSingleResult());
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /import — the tag (TAG3). The import adds its messages to the tag the form names, so a PUBLIC
    // tag of another domain is refused before any job starts, as the tag endpoints refuse it; a PUBLIC tag
    // of the current domain, or of no domain, is taken.
    // ------------------------------------------------------------------------------------------- //

    /** A committed PUBLIC tag made over REST by the sysadmin from the given domain, which it records */
    private String createPublicTagOfDomain(String name, String domain) {
        String tagId = given().auth().oauth2(accessToken())
                .header("NiordDomain", domain)
                .contentType(io.restassured.http.ContentType.JSON)
                .body(java.util.Map.of("name", name, "type", "PUBLIC", "locked", false))
                .when().post("/rest/tags/tag/")
                .then().statusCode(200)
                .extract().path("tagId");
        createdTagIds.add(tagId);
        return tagId;
    }

    @Test
    void import_intoAPublicTagOfAnotherDomain_isRefusedWith403AndStartsNoJob() throws Exception {
        String tagId = createPublicTagOfDomain("IT-import-nm-" + System.nanoTime(), "niord-nm");
        String fileName = "it-msgio-tag-nm-" + System.nanoTime() + ".zip";
        try {
            // The sysadmin is an admin of niord-nm too, so only the tag's domain can refuse this. FAILS on the
            // pre-fix code (200: the job started, and would add the archive's messages to niord-nm's tag).
            as(accessToken())
                    .multiPart("data", "{\"seriesId\":\"dma-nw\",\"tagId\":\"" + tagId + "\"}")
                    .multiPart("file", fileName, archive(EMPTY_MESSAGES_JSON), "application/zip")
                    .when().post(IMPORT)
                    .then().statusCode(403)
                    .body(containsString("The tag belongs to another domain"));
            assertEquals(0L, importRows(fileName), "no job was started for " + fileName);
        } finally {
            if (importRows(fileName) > 0) {
                BatchJobs.awaitCompletion(jobOperator, importExecution(fileName));
            }
            removeImportRows(fileName);
        }
    }

    @Test
    void import_intoAPublicTagOfTheCurrentDomainOrOfNoDomain_startsTheImportJob() throws Exception {
        String nwTagId = createPublicTagOfDomain("IT-import-nw-" + System.nanoTime(), NW_DOMAIN);
        MessageTag template = new MessageTag();
        template.setType(MessageTagType.PUBLIC);
        template.setName("IT-import-nodomain-" + System.nanoTime());
        String noDomainTagId = messageTagService.createMessageTag(template).getTagId();   // made outside a request
        createdTagIds.add(noDomainTagId);

        for (String tagId : List.of(nwTagId, noDomainTagId)) {
            String fileName = "it-msgio-tag-" + System.nanoTime() + ".zip";
            try {
                as(accessToken())
                        .multiPart("data", "{\"seriesId\":\"dma-nw\",\"tagId\":\"" + tagId + "\"}")
                        .multiPart("file", fileName, archive(EMPTY_MESSAGES_JSON), "application/zip")
                        .when().post(IMPORT)
                        .then().statusCode(200)
                        .body(containsString("Started batch job 'msg-archive-import'"));
                assertEquals(BatchStatus.COMPLETED, BatchJobs.awaitCompletion(jobOperator, importExecution(fileName)),
                        "the import job of " + fileName + " completes");
            } finally {
                removeImportRows(fileName);
            }
        }
    }

    /** A zip archive whose single entry is a messages.json with the given text */
    private static byte[] archive(String messagesJson) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("messages.json"));
            out.write(messagesJson.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * The execution of the msg-archive-import job started for the uploaded file. The upload's answer names no
     * execution, so its instance is found through its BatchData row, by the file name, which the job writes when it
     * starts, and its execution through the job operator.
     */
    private long importExecution(String fileName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Long> ids = Tx.requiringNew().call(() -> em.createQuery(
                            "select b.instanceId from BatchData b where b.dataFileName = :f and b.instanceId is not null",
                            Long.class)
                    .setParameter("f", fileName)
                    .getResultList());
            if (!ids.isEmpty()) {
                for (JobInstance instance : jobOperator.getJobInstances("msg-archive-import", 0, 10_000)) {
                    if (instance.getInstanceId() == ids.get(0)) {
                        return jobOperator.getJobExecutions(instance).get(0).getExecutionId();
                    }
                }
            }
            Thread.sleep(250);
        }
        fail("no msg-archive-import job was started for " + fileName + " within 60 s");
        return -1;
    }
}
