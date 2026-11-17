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
package org.niord.core.settings;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.mail.Mail;
import org.niord.core.mail.MailService;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageService;
import org.niord.model.message.Status;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.MessageTestFactory;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The no-write test (docs/code-data-config-plan.md §5.3, §10): after start-up, no read writes a row.
 *
 * <p>In full: an anonymous message PDF, a template-rendered mail, an ADMIN {@code GET /rest/script-resources/all}, a
 * settings read and the anonymous {@code /rest/settings/web}, the missing-path report and both import previews leave
 * the row counts of {@code ScriptResource}, {@code ScriptResourceHistory}, {@code Setting}, {@code FmReport},
 * {@code ParamType}, {@code Domain}, {@code Dictionary}, {@code DictionaryEntry} and {@code DictionaryEntryDesc}, and
 * the content of every {@code ScriptResource}, as they were. On an empty database {@code /web} returns {@code mode}
 * and {@code osmUrl} and writes no row ({@code SettingsFreshDatabaseITTest}).
 *
 * <p>The settings part: with the {@code country} row removed by hand, settings reads, the anonymous
 * {@code /rest/settings/web}, an anonymous message PDF, a mail and the administration app's GETs leave the
 * {@code Setting} table as it was, and {@code country} still absent: a missing setting resolves in memory and is
 * never created.
 *
 * <p>Two readers at once: until C3b two threads that read a setting the database lacked each inserted it, and the second
 * commit failed on the primary key, taking a two-language release with it. Two readers at once now both succeed, and
 * neither writes.
 *
 * <p>The {@code country} row is removed in a committed transaction and put back afterwards ({@link #restore()}).
 */
class SettingsNoWriteITTest extends ITTest {

    private static final String COUNTRY = "country";

    @Autowired
    SettingsService settingsService;

    @Autowired
    NiordApp app;

    @Autowired
    MailService mailService;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @PersistenceContext
    EntityManager em;

    /** The country row as it was, or null */
    private Setting previous;

    /** The message the PDF is made of, or null */
    private String messageUid;

    @BeforeEach
    void removeTheCountryRow() {
        previous = Tx.requiringNew().call(() -> {
            Setting row = em.find(Setting.class, COUNTRY);
            if (row == null) {
                return null;
            }
            Setting copy = new Setting(row);
            em.remove(row);
            return copy;
        });
        settingsService.evictFromCache(COUNTRY);
    }

    @AfterEach
    void restore() {
        Tx.requiringNew().run(() -> {
            Setting row = em.find(Setting.class, COUNTRY);
            if (row != null) {
                em.remove(row);
            }
        });
        if (previous != null) {
            Tx.requiringNew().run(() -> em.persist(previous));
        }
        settingsService.evictFromCache(COUNTRY);
        if (messageUid != null) {
            Tx.requiringNew().run(() -> {
                org.niord.core.message.Message msg = messageQueryService.findByUid(messageUid);
                if (msg != null) {
                    // MessageHistory is not cascaded from Message, so those rows go first
                    em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                            .setParameter("messageId", msg.getId())
                            .getResultList()
                            .forEach(em::remove);
                    em.remove(em.contains(msg) ? msg : em.merge(msg));
                }
            });
        }
    }

    @Test
    void noReadWritesASetting() throws Exception {
        // A message an anonymous caller may read, for the PDF: a draft made public by its status alone
        messageUid = Tx.requiringNew().call(() -> messageService
                .createMessage(MessageTestFactory.draftNw("IT-NO-WRITE-PDF-" + System.nanoTime()))
                .getUid());
        Tx.requiringNew().run(() -> em
                .createQuery("update Message m set m.status = :status where m.uid = :uid")
                .setParameter("status", Status.PUBLISHED)
                .setParameter("uid", messageUid)
                .executeUpdate());
        long before = settingCount();

        // Settings reads: a key no catalog declares, a catalog key without a row, peek
        String unknown = "it-no-write-" + System.nanoTime();
        assertEquals("default", settingsService.get(new Setting(unknown, "default")));
        assertNotNull(app.getCountry(), "a catalog key without a row resolves to its default");
        assertNotNull(settingsService.peek(COUNTRY));
        assertNull(settingsService.peek(unknown));

        // The anonymous web settings
        given().when().get("/rest/settings/web").then().statusCode(200);

        // An anonymous message PDF
        byte[] pdf = given().queryParam("lang", "en")
                .when().get("/rest/message-reports/message/" + messageUid + ".pdf")
                .then().statusCode(200)
                .extract().asByteArray();
        assertTrue(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));

        // A mail, simulated as the suite's mailValidRecipients = LOG makes it
        mailService.sendMail(new Mail()
                .from(new InternetAddress("niord@e-navigation.net"))
                .subject("IT-NO-WRITE-" + System.nanoTime())
                .recipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"))
                .htmlText("<html><body><p>no write</p></body></html>"));

        // The administration app's GETs
        given().auth().oauth2(accessToken())
                .when().get("/rest/settings/editable-settings")
                .then().statusCode(200);
        given().auth().oauth2(accessToken())
                .when().get("/rest/script-resources/all")
                .then().statusCode(200);

        assertEquals(before, settingCount(), "no read wrote a setting row");
        assertNull(row(COUNTRY), "the country row, removed by hand, is still absent");
    }

    /** The no-write test in full: the reads that rendered or copied templates, or inserted settings, until C4 */
    @Test
    void noReadWritesATemplateASettingAReportAParameterTypeADomainOrAText() throws Exception {
        messageUid = Tx.requiringNew().call(() -> messageService
                .createMessage(MessageTestFactory.draftNw("IT-NO-WRITE-ALL-" + System.nanoTime()))
                .getUid());
        Tx.requiringNew().run(() -> em
                .createQuery("update Message m set m.status = :status where m.uid = :uid")
                .setParameter("status", Status.PUBLISHED)
                .setParameter("uid", messageUid)
                .executeUpdate());
        Map<String, Object> before = snapshot();

        // An anonymous message PDF, rendered from its template rows
        byte[] pdf = given().queryParam("lang", "en")
                .when().get("/rest/message-reports/message/" + messageUid + ".pdf")
                .then().statusCode(200)
                .extract().asByteArray();
        assertTrue(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));

        // A template-rendered mail (messages/message-mail.ftl); the suite's mails go to the log
        given().auth().oauth2(KeycloakTestTokens.editorAccessToken()).header("NiordDomain", "niord-nw")
                .queryParam("mailTo", "it-no-write@example.com").queryParam("lang", "en")
                .when().get("/rest/message-mail/send")
                .then().statusCode(200);

        // An ADMIN GET of every script resource
        given().auth().oauth2(KeycloakTestTokens.adminAccessToken()).header("NiordDomain", "niord-nw")
                .when().get("/rest/script-resources/all")
                .then().statusCode(200);

        // A settings read and the anonymous web settings
        assertNotNull(app.getCountry());
        given().when().get("/rest/settings/web").then().statusCode(200);

        // The missing-path report and both import previews, as a sysadmin
        given().auth().oauth2(accessToken()).when().get("/rest/script-resources/missing-paths")
                .then().statusCode(200);
        String scripts = "[{\"type\": \"JS\", \"path\": \"templates/it-no-write/" + System.nanoTime()
                + ".js\", \"content\": \"var x = 1;\"}]";
        given().auth().oauth2(accessToken())
                .multiPart("file", "script-resources.json", scripts.getBytes(StandardCharsets.UTF_8), "application/json")
                .when().post("/rest/script-resources/import-preview")
                .then().statusCode(200);
        String texts = "[{\"name\": \"web\", \"entries\": [{\"key\": \"it-no-write-" + System.nanoTime()
                + "\", \"descs\": [{\"lang\": \"en\", \"value\": \"x\"}]}]}]";
        given().auth().oauth2(accessToken())
                .multiPart("file", "texts.json", texts.getBytes(StandardCharsets.UTF_8), "application/json")
                .when().post("/rest/dictionaries/import-preview")
                .then().statusCode(200);

        assertEquals(before, snapshot(), "no read wrote a row");
    }

    /** Two languages of one document read a setting the database lacks at the same moment */
    @Test
    void twoReadersOfAMissingSettingAtOnceBothSucceedAndNeitherWrites() throws Exception {
        long before = settingCount();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> readers = List.of(
                    executor.submit(() -> read(ready, go)),
                    executor.submit(() -> read(ready, go)));
            assertTrue(ready.await(60, TimeUnit.SECONDS), "both readers are in their transactions");
            go.countDown();

            for (Future<String> reader : readers) {
                assertEquals(app.getCountry(), reader.get(60, TimeUnit.SECONDS), "each reader gets the default");
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(before, settingCount(), "neither reader wrote a row");
        assertNull(row(COUNTRY));
    }

    // ------------------------------------------------------------------------------------------- //

    /** Reads the country in a transaction of its own, once both readers are ready */
    private String read(CountDownLatch ready, CountDownLatch go) {
        return Tx.requiringNew().call(() -> {
            settingsService.evictFromCache(COUNTRY);
            ready.countDown();
            assertTrue(go.await(60, TimeUnit.SECONDS));
            return app.getCountry();
        });
    }

    /** The row counts of the tables the no-write test watches, and a checksum over every script resource */
    private Map<String, Object> snapshot() {
        return Tx.requiringNew().call(() -> {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            for (String table : List.of("ScriptResource", "ScriptResourceHistory", "Setting", "FmReport", "ParamType",
                    "Domain", "Dictionary", "DictionaryEntry", "DictionaryEntryDesc")) {
                snapshot.put(table, ((Number) em.createNativeQuery("SELECT count(*) FROM public.\"" + table + "\"")
                        .getSingleResult()).longValue());
            }
            snapshot.put("ScriptResource md5", em.createNativeQuery("SELECT md5(coalesce(string_agg(path || ':' "
                    + "|| coalesce(content, ''), '|' ORDER BY path), '')) FROM public.\"ScriptResource\"")
                    .getSingleResult());
            return snapshot;
        });
    }

    private long settingCount() {
        return Tx.requiringNew().call(() -> em.createQuery("select count(s) from Setting s", Long.class)
                .getSingleResult());
    }

    private Setting row(String key) {
        return Tx.requiringNew().call(() -> em.find(Setting.class, key));
    }
}
