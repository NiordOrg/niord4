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

import org.grad.eNav.s100.utils.S100ExchangeSetUtils;
import org.grad.secomv2.core.utils.SecomPemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.model.message.Status;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124DatasetVersion;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.S124ExchangeSetService;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dk.dma.niord.s100.catalog._5_2.S100DatasetDiscoveryMetadata;
import dk.dma.niord.s100.catalog._5_2.S100ExchangeCatalogue;
import dk.dma.niord.s100.catalog._5_2.S100SECertificateType;
import dk.dma.niord.s100.catalog._5_2.S100SESignatureOnData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for the reloadable MCP keystore seam (the pre-requisite for certificate rotation
 * without a restart): the sysadmin reload endpoint swaps the service identity in place, a
 * failed reload keeps the current material serving, and a rotation leaves the published
 * dataset versions exactly as they are - they keep shipping under the signature they were
 * published with, the rotated-out certificate travelling along, while the catalogue around
 * them is signed by the new identity.
 * <p>
 * Boots against a keystore copied to a temp <em>file</em> (a {@code classpath:} store cannot
 * be overwritten), so this class forms its own cached application context, like
 * {@code SecomDisabledITTest}. Each test restores the original keystore afterwards.
 */
class SecomSecurityReloadITTest extends SecomITTest {

    /** The temp keystore file the app boots from; overwritten by the rotation tests */
    static final Path KEYSTORE_FILE = stagedStore("secom-reload-keystore", "/secom-test/keystore-test.p12");

    @DynamicPropertySource
    static void keystoreLocation(DynamicPropertyRegistry registry) {
        // Overrides the classpath location inherited from SecomITTest's @TestPropertySource
        registry.add("niord.secom.keystore.location", () -> "file:" + KEYSTORE_FILE);
    }

    @Autowired
    McpSecurityService pki;

    @Autowired
    S124ExchangeSetService exchangeSetService;

    @AfterEach
    void restoreOriginalKeystore() throws Exception {
        copyClasspathResource("/secom-test/keystore-test.p12", KEYSTORE_FILE);
        pki.reload();
    }

    // ------------------------------------------------------------------------------------------- //

    @Test
    void reloadIsSysadminOnly() {
        given().post("/rest/secom/security/reload").then().statusCode(401);
        given().auth().oauth2(accessToken("editor"))
                .post("/rest/secom/security/reload").then().statusCode(403);
        given().auth().oauth2(accessToken("admin"))
                .post("/rest/secom/security/reload").then().statusCode(403);
    }

    @Test
    void reloadSwapsTheCertificateWithoutARestart() {
        String originalFp = McpSecurityService.fingerprint(loadCert("/secom-test/keystore-test.p12"));
        String rotatedFp = McpSecurityService.fingerprint(loadCert("/secom-test/client-one.p12"));

        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("serviceCertificate.fingerprint", equalTo(originalFp));

        // Rotate: overwrite the keystore file in place (the k8s secret-update pattern)
        copyClasspathResource("/secom-test/client-one.p12", KEYSTORE_FILE);
        given().auth().oauth2(accessToken())
                .post("/rest/secom/security/reload")
                .then()
                .statusCode(200)
                .body("previousFingerprint", equalTo(originalFp))
                .body("serviceCertificateFingerprint", equalTo(rotatedFp));

        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("serviceCertificate.fingerprint", equalTo(rotatedFp));

        // The SECOM protocol keeps serving under the new identity (truststore unchanged)
        secomGiven().get("/api/secom/v2/capability").then().statusCode(200);
    }

    @Test
    void rotationLeavesPublishedVersionsAloneAndServesThemWithTheCertificateThatMadeThem() throws Exception {
        Message published = Tx.requiringNew().call(() -> publishNw("secom-rotate-" + uniq(), 55.5, 11.1));
        S124Dataset dataset = awaitDatasetForMessage(published.getUid());
        assertNotNull(dataset);
        X509Certificate original = loadCert("/secom-test/keystore-test.p12");
        X509Certificate rotated = loadCert("/secom-test/client-one.p12");
        S124DatasetVersion version = Tx.requiringNew().call(() ->
                datasetService.findByMessageUid(published.getUid()).getCurrentVersion());
        assertEquals(SecomPemUtils.getMinifiedPemFromCert(original), version.certificatePems().get(0),
                "publishing signed the version under the original identity");

        copyClasspathResource("/secom-test/client-one.p12", KEYSTORE_FILE);
        given().auth().oauth2(accessToken())
                .post("/rest/secom/security/reload")
                .then()
                .statusCode(200);

        // The sweep has nothing to re-sign: the version's signature is over its bytes, and the
        // chain that made it is part of the record
        S124DatasetService.ReconcileResult result = datasetService.reconcile();
        Tx.requiringNew().run(() -> {
            S124Dataset afterSweep = datasetService.findByMessageUid(published.getUid());
            assertEquals(version.getId(), afterSweep.getCurrentVersion().getId(), "the current version is untouched");
            assertArrayEquals(version.getSignature(), afterSweep.getCurrentVersion().getSignature());
            assertEquals(version.getSigningCertificates(), afterSweep.getCurrentVersion().getSigningCertificates());
            assertEquals(1, versionsOf(afterSweep).size(), "a rotation appends no version");
        });
        assertTrue(result.unchanged() >= 1, "unchanged content counts as unchanged");
        Tx.requiringNew().run(() -> assertEquals(0, outboxService.search(null, 100).stream()
                        .filter(o -> o.getOperation() == SecomOutboxEntry.Operation.UPLOAD)
                        .count(),
                "a rotation must not push anything"));

        // What a Get serves now: the published signature, referencing the rotated-out
        // certificate the exchange set carries (S-100 Part 15 clause 15-8.7), in a catalogue
        // signed by the new identity
        byte[] zip = Tx.requiringNew().call(() ->
                exchangeSetService.buildExchangeSet(datasetService.findByMessageUid(published.getUid())));
        S100ExchangeCatalogue catalogue = catalogueOf(zip);
        List<S100DatasetDiscoveryMetadata> entries =
                catalogue.getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas();
        assertEquals(1, entries.size());
        assertArrayEquals(version.gmlBytes(), unzip(zip).get("S100_ROOT/S-124/DATASET_FILES/" + version.getFileName()),
                "the version's bytes ship verbatim");
        S100SESignatureOnData signature = signatureOf(entries.get(0));
        assertArrayEquals(version.getSignature(), signature.getValue(),
                "the published signature ships, not a fresh one under the rotated identity");
        assertNotEquals("cer1", signature.getCertificateRef(), "the signature must not claim the current certificate");
        List<S100SECertificateType> certificates = catalogue.getCertificates().get(0).getCertificates();
        assertArrayEquals(original.getEncoded(), certificateWithId(certificates, signature.getCertificateRef()).getValue(),
                "the rotated-out certificate that made the signature travels with the exchange set");
        assertArrayEquals(rotated.getEncoded(), certificateWithId(certificates, "cer1").getValue(),
                "the catalogue itself is signed under the rotated identity");
    }

    @Test
    void aCancelledDatasetFetchedAfterRotationShipsUnderItsOriginalSignature() throws Exception {
        // Use case 2 across a rotation: the exchange set of a cancelled dataset retrieved by
        // dataReference carries the signature the dataset was cancelled with - the one every
        // cancellation entry elsewhere reproduces (clause 17-4.4.1) - with the rotated-out
        // certificate travelling along, not a fresh signature under the new identity
        Message published = Tx.requiringNew().call(() -> publishNw("secom-rotate-cancelled-" + uniq(), 55.5, 11.1));
        assertNotNull(awaitDatasetForMessage(published.getUid()));
        Tx.requiringNew().run(() -> {
            messageService.updateStatus(published.getUid(), Status.CANCELLED);
            datasetService.onMessageStatusChanged(published.getUid());
        });
        S124DatasetVersion finalVersion = Tx.requiringNew().call(() -> {
            S124Dataset cancelled = datasetService.findByMessageUid(published.getUid());
            assertEquals(S124Dataset.Status.CANCELLED, cancelled.getStatus());
            return cancelled.getCurrentVersion();
        });
        X509Certificate original = loadCert("/secom-test/keystore-test.p12");
        assertEquals(SecomPemUtils.getMinifiedPemFromCert(original), finalVersion.certificatePems().get(0),
                "cancelling published the final version under the original identity");

        copyClasspathResource("/secom-test/client-one.p12", KEYSTORE_FILE);
        given().auth().oauth2(accessToken())
                .post("/rest/secom/security/reload")
                .then()
                .statusCode(200);

        // What a Get by dataReference serves now (SecomV2Resource.payloadFor builds exactly this)
        byte[] zip = Tx.requiringNew().call(() ->
                exchangeSetService.buildExchangeSet(datasetService.findByMessageUid(published.getUid())));
        S100ExchangeCatalogue catalogue = catalogueOf(zip);
        List<S100DatasetDiscoveryMetadata> entries =
                catalogue.getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas();
        assertEquals(1, entries.size());
        S100SESignatureOnData signature = signatureOf(entries.get(0));
        assertArrayEquals(finalVersion.getSignature(), signature.getValue(),
                "the persisted signature ships, not a fresh one under the rotated identity");
        assertNotEquals("cer1", signature.getCertificateRef(),
                "the reused signature must not claim the current certificate");
        List<S100SECertificateType> certificates = catalogue.getCertificates().get(0).getCertificates();
        assertArrayEquals(original.getEncoded(), certificateWithId(certificates, signature.getCertificateRef()).getValue(),
                "the rotated-out certificate that made the signature travels with the exchange set");
        assertArrayEquals(loadCert("/secom-test/client-one.p12").getEncoded(),
                certificateWithId(certificates, "cer1").getValue(),
                "the catalogue itself is signed under the rotated identity");
    }

    private static S100ExchangeCatalogue catalogueOf(byte[] zip) throws Exception {
        return S100ExchangeSetUtils.unmarshallS100ExchangeSetCatalogue(
                new String(unzip(zip).get("S100_ROOT/CATALOG.XML"), StandardCharsets.UTF_8));
    }

    private static S100SESignatureOnData signatureOf(S100DatasetDiscoveryMetadata entry) {
        return (S100SESignatureOnData) entry.getDigitalSignatureValues().get(0).getS100SEDigitalSignature().getValue();
    }

    private static S100SECertificateType certificateWithId(List<S100SECertificateType> certificates, String id) {
        return certificates.stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the catalogue carries no certificate " + id));
    }

    @Test
    void failedReloadKeepsServingTheOldMaterial() throws Exception {
        String originalFp = McpSecurityService.fingerprint(loadCert("/secom-test/keystore-test.p12"));

        Files.write(KEYSTORE_FILE, "not a keystore".getBytes());
        given().auth().oauth2(accessToken())
                .post("/rest/secom/security/reload")
                .then()
                .statusCode(400);

        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("serviceCertificate.fingerprint", equalTo(originalFp));

        // Still serving - the swap never happened
        given().get("/api/secom/v2/ping").then().statusCode(200);
    }
}
