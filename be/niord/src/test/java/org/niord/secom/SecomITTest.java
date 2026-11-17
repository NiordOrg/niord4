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
package org.niord.secom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import org.grad.secomv2.core.base.DigitalSignatureCertificate;
import org.grad.secomv2.core.base.SecomCertificateProvider;
import org.grad.secomv2.core.base.SecomSignatureProvider;
import org.grad.secomv2.core.models.enums.DigitalSignatureAlgorithmEnum;
import org.grad.secomv2.core.utils.SecomPemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.message.Message;
import org.niord.core.message.MessageKind;
import org.niord.core.message.MessageService;
import org.niord.model.message.Status;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124DatasetVersion;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.rest.SecomV2Application;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.SecomConfigService;
import org.niord.secom.service.SecomOutboxService;
import org.niord.secom.service.SecomSubscriptionService;
import org.niord.test.ITTest;
import org.niord.test.MessageTestFactory;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;

/**
 * Base class for the SECOM integration tests: boots the application with the SECOM
 * integration enabled against the committed test PKI ({@code secom-test/}, see
 * {@code generate-test-pki.sh}), scopes the seeded {@code dma-nw} series, wipes the SECOM
 * tables before each test (SECOM protocol calls commit outside test transactions), and removes
 * the messages a test published through {@link #publishNw} after it.
 */
@TestPropertySource(properties = {
        "niord.secom.enabled=true",
        "niord.secom.keystore.location=classpath:secom-test/keystore-test.p12",
        "niord.secom.keystore.password=changeit",
        "niord.secom.truststore.location=classpath:secom-test/truststore-test.p12",
        "niord.secom.truststore.password=changeit",
        // The ITs present the peer certificate in the X-Secom-Cert header, which the filter
        // ignores unless the deployment vouches for it (off by default since SECOM Edition 2)
        "niord.secom.trust-cert-header=true",
        // The scheduled reconciliation would backfill datasets for OTHER tests' still-published
        // messages mid-test (the per-test wipe clears SECOM rows, not messages) - tests drive
        // the pipeline explicitly, so the sweep is disabled ("-" = Spring's CRON_DISABLED)
        "niord.secom.reconcile-cron=-",
        // The daily certificate-expiry check would race the expiry ITs' exact mail-count
        // assertions - those tests drive checkAndAlert()/check(now) explicitly
        "niord.secom.cert-expiry-cron=-",
        // The store-file watch would race the rotation ITs' file swaps and mail counts -
        // those tests drive watch() explicitly
        "niord.secom.store-watch-cron=-",
        // The daily certificate-renewal job would race the renewal ITs' stub-MIR flows and
        // mail counts - those tests drive renew(now) explicitly
        "niord.secom.renewal-cron=-",
        // The outbox dispatcher's tick would send a test's committed, due entry a second time
        // next to the test's own dispatchAllDue() (a second upload, a second attempt) - those
        // tests drive dispatchAllDue() explicitly
        "niord.secom.outbox-dispatch-cron=-"
})
public abstract class SecomITTest extends ITTest {

    /** The MRN in the subject UID of the trusted test client certificate (client-one.pem) */
    public static final String CLIENT_MRN = "urn:mrn:mcp:service:mcc:test:instance:client-one";

    /** SECOM wire JSON is Jackson 2 with ISO timestamps (matches the servlet's mapper) */
    protected static final ObjectMapper SECOM_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Autowired
    protected MessageService messageService;

    @Autowired
    protected SecomConfigService secomConfigService;

    @Autowired
    protected S124DatasetService datasetService;

    @Autowired
    protected SecomSubscriptionService subscriptionService;

    @Autowired
    protected SecomOutboxService outboxService;

    @Autowired
    protected EntityManager em;

    @Autowired
    private ApplicationContext applicationContext;

    /** The UIDs of the messages {@link #publishNw} created for the running test */
    private final List<String> publishedMessageUids = new ArrayList<>();

    @BeforeEach
    void secomSetup() {
        // The SECOM servlet takes its beans from a static handed over by the last context refreshed, and keeps them
        // from its first request on. A subclass with a context of its own (S124ReconcileFailureITTest) refreshes after
        // this class's context, so without this the first SECOM request of a later test of this context is served by
        // the other context's beans.
        SecomV2Application.setApplicationContext(applicationContext);
        Tx.requiringNew().run(() -> {
            em.createQuery("delete from SecomOutboxEntry").executeUpdate();
            em.createQuery("update S124Dataset d set d.currentVersion = null").executeUpdate();
            em.createQuery("delete from S124DatasetVersion").executeUpdate();
            em.createQuery("delete from S124Dataset").executeUpdate();
            em.createQuery("delete from SecomSubscription").executeUpdate();
            em.createQuery("delete from SecomNode").executeUpdate();
            em.createQuery("delete from S124SeriesConfig").executeUpdate();
        });
        // Committed separately so the exposure is visible to the server threads of REST-driven tests
        Tx.requiringNew().run(() -> secomConfigService.upsertSeriesConfig("dma-nw", true, List.of()));
    }

    /**
     * Removes the messages {@link #publishNw} committed, with their datasets, outbox entries,
     * history and any cancellation warning minted for them. A rolled-back test leaves nothing to
     * remove - a fresh transaction cannot see its rows - so this only reaches the committed
     * fixtures the REST- and sweep-driven tests need. Without it every such run left PUBLISHED
     * rows in the shared database for good: the seed holds no messages, so any test counting
     * rows globally saw this suite's residue, and saw a different amount depending on the
     * platform's class order (the mechanism behind the former potential-errors TESTQ3).
     */
    @AfterEach
    void removePublishedMessages() {
        for (String uid : publishedMessageUids) {
            try {
                Tx.requiringNew().run(() -> removeMessage(uid));
            } catch (Exception e) {
                // The real JMS status listener may still be writing the dataset row of a message
                // committed moments ago; let it settle and try once more
                try {
                    Thread.sleep(250);
                    Tx.requiringNew().run(() -> removeMessage(uid));
                } catch (Exception second) {
                    System.err.println("Could not remove published test message " + uid + ": " + second);
                }
            }
        }
        publishedMessageUids.clear();
    }

    /** Removes the message with the given UID and the cancellation warning minted for it, if any */
    private void removeMessage(String uid) {
        List<Message> rows = em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                .setParameter("uid", uid)
                .getResultList();
        if (rows.isEmpty()) {
            return;
        }
        em.createQuery("select cw from Message cw join cw.references r where cw.kind = :kind and r.messageId = :uid",
                        Message.class)
                .setParameter("kind", MessageKind.CANCELLATION)
                .setParameter("uid", uid)
                .getResultList()
                .forEach(this::removeMessageRow);
        rows.forEach(this::removeMessageRow);
    }

    /** Removes one message row and the rows that reference it without a cascade from Message */
    private void removeMessageRow(Message message) {
        em.createQuery("delete from SecomOutboxEntry o where o.dataset in "
                        + "(select d from S124Dataset d where d.message = :message)")
                .setParameter("message", message)
                .executeUpdate();
        deleteDatasetsOfMessages(List.of(message.getUid()));
        em.createQuery("delete from MessageHistory mh where mh.message = :message")
                .setParameter("message", message)
                .executeUpdate();
        message.getTags().forEach(tag -> tag.getMessages().remove(message));
        em.remove(message);
    }

    /**
     * Deletes the dataset rows of the given messages together with their published versions.
     * The dataset points at its current version and every version points back at its dataset,
     * so the pointer is cleared first; outbox entries referencing the datasets must already be
     * gone.
     */
    protected void deleteDatasetsOfMessages(List<String> messageUids) {
        em.createQuery("update S124Dataset d set d.currentVersion = null where d.message.uid in :uids")
                .setParameter("uids", messageUids)
                .executeUpdate();
        em.createQuery("delete from S124DatasetVersion v where v.dataset in "
                        + "(select d from S124Dataset d where d.message.uid in :uids)")
                .setParameter("uids", messageUids)
                .executeUpdate();
        em.createQuery("delete from S124Dataset d where d.message.uid in :uids")
                .setParameter("uids", messageUids)
                .executeUpdate();
    }

    /** {@return every published version of the dataset, oldest first - the immutable history of what was served} */
    protected List<S124DatasetVersion> versionsOf(S124Dataset dataset) {
        return em.createNamedQuery("S124DatasetVersion.findByDataset", S124DatasetVersion.class)
                .setParameter("dataset", dataset)
                .getResultList();
    }

    /***************************************/
    /** SECOM client plumbing             **/
    /***************************************/

    /** Returns the given classpath resource as a string */
    protected static String classpathResource(String path) {
        try (InputStream in = SecomITTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Cannot read " + path, e);
        }
    }

    /** The X-Secom-Cert header value of the trusted test client (PEM) */
    protected static String clientCertHeader() {
        return classpathResource("/secom-test/client-one.pem");
    }

    /** The X-Secom-Cert header value of a client from an untrusted root */
    protected static String untrustedCertHeader() {
        return classpathResource("/secom-test/untrusted-client.pem");
    }

    /** A RestAssured spec authenticated as the trusted test client */
    protected RequestSpecification secomGiven() {
        return given().header(org.niord.secom.rest.MrnCertificateFilter.CERT_HEADER, clientCertHeader());
    }

    /** SECOMLib providers signing as the remote test client (used to sign acknowledgements) */
    protected static ClientSigner clientSigner() {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            try (InputStream in = SecomITTest.class.getResourceAsStream("/secom-test/client-one.p12")) {
                keystore.load(in, "changeit".toCharArray());
            }
            KeyStore truststore = KeyStore.getInstance("PKCS12");
            try (InputStream in = SecomITTest.class.getResourceAsStream("/secom-test/truststore-test.p12")) {
                truststore.load(in, "changeit".toCharArray());
            }
            X509Certificate clientCert = (X509Certificate) keystore.getCertificate("1");
            PrivateKey clientKey = (PrivateKey) keystore.getKey("1", "changeit".toCharArray());
            X509Certificate root = null;
            for (var aliases = truststore.aliases(); aliases.hasMoreElements(); ) {
                if (truststore.getCertificate(aliases.nextElement()) instanceof X509Certificate x509) {
                    root = x509;
                }
            }
            return new ClientSigner(clientCert, clientKey, root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Signs SECOM envelopes with the remote test client's certificate */
    public record ClientSigner(X509Certificate certificate, PrivateKey key, X509Certificate root)
            implements SecomCertificateProvider, SecomSignatureProvider {

        @Override
        public DigitalSignatureCertificate getDigitalSignatureCertificate() {
            DigitalSignatureCertificate cert = new DigitalSignatureCertificate();
            cert.setCertificateAlias(new String[] { "1" });
            cert.setCertificate(new X509Certificate[] { certificate });
            cert.setPublicKey(certificate.getPublicKey());
            cert.setRootCertificate(root);
            return cert;
        }

        @Override
        public DigitalSignatureAlgorithmEnum getSignatureAlgorithm() {
            return DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA;
        }

        @Override
        public byte[] generateSignature(DigitalSignatureCertificate signatureCertificate, byte[] payload) {
            try {
                Signature signature = Signature.getInstance(getSignatureAlgorithm().getValue());
                signature.initSign(key);
                signature.update(payload);
                return signature.sign();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean validateSignature(String[] signatureCertificates, byte[] signature, byte[] content) {
            try {
                X509Certificate cert = SecomPemUtils.getCertFromPem(signatureCertificates[0]);
                Signature verification = Signature.getInstance(getSignatureAlgorithm().getValue());
                verification.initVerify(cert);
                verification.update(content);
                return verification.verify(signature);
            } catch (Exception e) {
                return false;
            }
        }
    }

    /***************************************/
    /** Message helpers                   **/
    /***************************************/

    /**
     * Waits for the real JMS status listener to have created (committed) the dataset row of
     * the given message, and returns it (null on timeout). Committed publishes fire the
     * listener concurrently with the test thread, so tests that reorganize dataset rows must
     * let it settle first.
     */
    protected S124Dataset awaitDatasetForMessage(String messageUid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        S124Dataset dataset;
        while ((dataset = Tx.requiringNew().call(() -> datasetService.findByMessageUid(messageUid))) == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        return dataset;
    }

    /** Creates and publishes a fresh NW (DRAFT -> VERIFIED -> PUBLISHED) with a point geometry */
    protected Message publishNw(String title, double lat, double lon) {
        return publishNw(title, lat, lon, draft -> { });
    }

    /**
     * As {@link #publishNw(String, double, double)}, with a hook to adjust the draft before it
     * is created - e.g. to add descriptions in a second language
     */
    protected Message publishNw(String title, double lat, double lon, Consumer<Message> customizer) {
        try {
            Message draft = MessageTestFactory.draftNw(title);
            FeatureCollection featureCollection = new FeatureCollection();
            Feature feature = new Feature();
            feature.setGeometry(JtsConverter.toJtsPoint(lat, lon));
            featureCollection.addFeature(feature);
            draft.getParts().get(0).setGeometry(featureCollection);
            customizer.accept(draft);

            Message created = messageService.createMessage(draft);
            publishedMessageUids.add(created.getUid());
            messageService.updateStatus(created.getUid(), Status.VERIFIED);
            return messageService.updateStatus(created.getUid(), Status.PUBLISHED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected static String uniq() {
        return Long.toHexString(System.nanoTime());
    }

    /***************************************/
    /** Store staging                     **/
    /***************************************/

    /** Copies a classpath resource over the given file */
    protected static void copyClasspathResource(String resource, Path target) {
        try (InputStream in = SecomITTest.class.getResourceAsStream(resource)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot copy " + resource, e);
        }
    }

    /**
     * Stages a classpath PKCS12 store as a temp <em>file</em> a test can rotate in place (a
     * {@code classpath:} store cannot be overwritten). Classes that boot from the staged file
     * keep their own {@code @DynamicPropertySource} pointing at it, so each forms its own
     * cached application context.
     */
    protected static Path stagedStore(String prefix, String resource) {
        try {
            Path file = Files.createTempFile(prefix, ".p12");
            file.toFile().deleteOnExit();
            copyClasspathResource(resource, file);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot stage " + resource, e);
        }
    }

    /** {@return the leaf certificate of the given classpath PKCS12 store} */
    protected static X509Certificate loadCert(String p12Resource) {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            try (InputStream in = SecomITTest.class.getResourceAsStream(p12Resource)) {
                keystore.load(in, "changeit".toCharArray());
            }
            return (X509Certificate) keystore.getCertificate("1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /***************************************/
    /** Assertion helpers                 **/
    /***************************************/

    /** {@return the entries of the given SECOM exchange-set zip, in zip order} */
    protected static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    /** {@return the number of durably committed mails - alerts are queued in their own transaction} */
    protected long committedMailCount() {
        return Tx.requiringNew().call(() ->
                em.createQuery("select count(m) from ScheduledMail m", Long.class).getSingleResult());
    }

    /** {@return the HTML body of the mail, unwrapping the checked exception} */
    protected static String htmlOf(ScheduledMail mail) {
        try {
            return mail.getHtmlContents();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@return the queued outbox UPLOAD entries} */
    protected List<SecomOutboxEntry> uploadEntries() {
        return outboxService.search(null, 100).stream()
                .filter(o -> o.getOperation() == SecomOutboxEntry.Operation.UPLOAD)
                .toList();
    }
}
