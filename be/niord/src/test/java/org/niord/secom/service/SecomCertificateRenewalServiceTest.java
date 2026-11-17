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
package org.niord.secom.service;

import org.junit.jupiter.api.Test;
import org.niord.secom.pki.KeystoreWriter;
import org.niord.secom.pki.McpMirClient;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.pki.TestCertificateAuthority;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.secom.pki.McpSecurityService.fingerprint;
import static org.niord.secom.pki.TestCertificateAuthority.daysFromNow;

/**
 * Unit tests for the renewal orchestration contracts that the stub-MIR IT cannot reach or
 * that need no application context: default-off, window gating, robot-before-service
 * ordering, robot-failure-does-not-block-the-service, the reload-kept-the-old-certificate
 * guard (unreachable through an honest MIR because the fresh-key hard gate rejects any other
 * chain first), and the commit-before-marker failure-alert dedup. The end-to-end flows run in
 * {@code SecomCertificateRenewalITTest} against a stub MIR and the real database.
 */
public class SecomCertificateRenewalServiceTest {

    static final TestCertificateAuthority CA = TestCertificateAuthority.create("Renewal Service Test CA");
    static final String ORG_MRN = "urn:mrn:mcp:org:mcc:test";
    static final String SERVICE_MRN = "urn:mrn:mcp:service:mcc:test:instance:renewal-service";
    static final String ROBOT_MRN = "urn:mrn:mcp:user:mcc:test:renewal-robot";
    static final String SERVICE_LOCATION = "/virtual/service-keystore.p12";
    static final String ROBOT_LOCATION = "/virtual/robot-keystore.p12";

    /** In-memory persist backend, doubling as the "file system" the PKI stub reloads from */
    static class MemoryWriter implements KeystoreWriter {
        final Map<String, byte[]> stores = new HashMap<>();
        boolean swallowWrites;

        @Override
        public boolean supports(String location) {
            return true;
        }

        @Override
        public void write(String location, byte[] keystoreBytes) {
            if (!swallowWrites) {
                stores.put(location, keystoreBytes);
            }
        }
    }

    /** The k8s-shaped backend: durable writes with deferred pickup */
    static class DeferredWriter extends MemoryWriter {
        @Override
        public boolean deferredPickup() {
            return true;
        }
    }

    /** A PKI stub whose stores live in the {@link MemoryWriter} map instead of on disk */
    static class PkiStub extends McpSecurityService {
        final MemoryWriter writer;
        final KeyStore truststore;
        X509Certificate serviceCert;
        int reloads;

        PkiStub(MemoryWriter writer, byte[] serviceBytes, byte[] robotBytes) {
            super(new DefaultResourceLoader());
            this.writer = writer;
            writer.stores.put(SERVICE_LOCATION, serviceBytes);
            writer.stores.put(ROBOT_LOCATION, robotBytes);
            this.serviceCert = certOf(serviceBytes);
            this.truststore = anchoringTruststore();
        }

        @Override
        public X509Certificate serviceCertificate() {
            return serviceCert;
        }

        @Override
        public KeyStore trustStore() {
            return truststore;
        }

        @Override
        public KeyStore loadExternalStore(String location, String password) throws Exception {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(new ByteArrayInputStream(writer.stores.get(location)), password.toCharArray());
            return keystore;
        }

        @Override
        public synchronized void reload() {
            reloads++;
            serviceCert = certOf(writer.stores.get(SERVICE_LOCATION));
        }
    }

    /** Records the MIR calls in order; issues honest chains via the test CA unless told to fail */
    static class MirStub implements McpMirClient {
        final List<String> calls = new ArrayList<>();
        boolean failUserCalls;
        boolean failServiceCalls;

        @Override
        public String issueServiceCertificate(String orgMrn, String serviceMrn, String csrPem) {
            calls.add("service:" + orgMrn + ":" + serviceMrn);
            if (failServiceCalls) {
                throw new IllegalStateException("MIR POST /service failed with HTTP 500: registry down");
            }
            return CA.signChainFromCsr(csrPem, daysFromNow(-1), daysFromNow(180));
        }

        @Override
        public String issueUserCertificate(String orgMrn, String userMrn, String csrPem) {
            calls.add("user:" + orgMrn + ":" + userMrn);
            if (failUserCalls) {
                throw new IllegalStateException("MIR POST /user failed with HTTP 403: forbidden");
            }
            return CA.signChainFromCsr(csrPem, daysFromNow(-1), daysFromNow(180));
        }
    }

    /** Simulates the mail-commit outcome without an EntityManager: TRUE = mail durably committed */
    static class TxRunnerStub extends SecomTxRunner {
        int attempts;
        boolean fail;

        @Override
        public <T> T call(Supplier<T> work) {
            attempts++;
            if (fail) {
                throw new IllegalStateException("db down");
            }
            @SuppressWarnings("unchecked")
            T committed = (T) Boolean.TRUE;
            return committed;
        }
    }

    record Harness(SecomCertificateRenewalService service, PkiStub pki, MirStub mir,
                   MemoryWriter writer, TxRunnerStub tx) {
    }

    /** A fully configured, enabled renewal service over in-memory stores with the given lifetimes */
    static Harness harness(int robotDaysToExpiry, int serviceDaysToExpiry) {
        return harness(robotDaysToExpiry, serviceDaysToExpiry, new MemoryWriter());
    }

    static Harness harness(int robotDaysToExpiry, int serviceDaysToExpiry, MemoryWriter writer) {
        byte[] serviceBytes = CA.mintIdentity(SERVICE_MRN, daysFromNow(-1), daysFromNow(serviceDaysToExpiry), "1", "changeit");
        byte[] robotBytes = CA.mintIdentity(ROBOT_MRN, daysFromNow(-1), daysFromNow(robotDaysToExpiry), "1", "changeit");
        PkiStub pki = new PkiStub(writer, serviceBytes, robotBytes);
        MirStub mir = new MirStub();
        TxRunnerStub tx = new TxRunnerStub();
        SecomCertificateRenewalService service = new SecomCertificateRenewalService(pki, mir, writer, tx);
        service.renewalEnabled = true;
        service.windowDays = 60;
        service.alertEmails = "ops@example.com";
        service.keystoreLocation = SERVICE_LOCATION;
        service.keystorePassword = "changeit";
        service.keystoreAlias = "1";
        service.mirUrl = "http://mir.invalid";
        service.orgMrn = ORG_MRN;
        service.serviceMrn = "";   // derived from the service certificate subject UID
        service.userMrn = "";      // derived from the robot certificate subject UID
        service.robotKeystoreLocation = ROBOT_LOCATION;
        service.robotKeystorePassword = "changeit";
        service.robotKeystoreAlias = "1";
        return new Harness(service, pki, mir, writer, tx);
    }

    static X509Certificate certOf(byte[] p12) {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(new ByteArrayInputStream(p12), "changeit".toCharArray());
            return (X509Certificate) keystore.getCertificate("1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyStore anchoringTruststore() {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(null, null);
            keystore.setCertificateEntry("ca", CA.certificate());
            return keystore;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------- //

    @Test
    public void renewalServiceOverridesTheInheritedTransactionality() {
        // BaseService's class-level @Transactional is @Inherited - without this override a
        // renewal run would pin a database connection across the MIR/Kubernetes round-trips.
        // All database work (the mails) commits through SecomTxRunner's own transactions.
        org.springframework.transaction.annotation.Transactional tx =
                SecomCertificateRenewalService.class.getAnnotation(
                        org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(tx, "the renewal service must override BaseService's class-level @Transactional");
        assertEquals(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED, tx.propagation());
    }

    @Test
    public void disabledRenewalTouchesNothing() {
        Harness h = harness(30, 30);   // both inside the window - would renew if enabled
        h.service().renewalEnabled = false;
        h.service().renew(new Date());

        h.service().renewalEnabled = null;   // an un-injected field must read as disabled too
        h.service().renew(new Date());

        assertTrue(h.mir().calls.isEmpty(), "a disabled renewal must not contact the MIR");
        assertEquals(0, h.tx().attempts);
        assertEquals(0, h.pki().reloads);
        assertNull(h.service().lastFailure);
    }

    @Test
    public void certificatesOutsideTheWindowAreLeftAlone() {
        Harness h = harness(36500, 36500);
        String fingerprintBefore = fingerprint(h.pki().serviceCertificate());

        h.service().renew(new Date());

        assertTrue(h.mir().calls.isEmpty());
        assertEquals(fingerprintBefore, fingerprint(h.pki().serviceCertificate()));
        assertEquals(0, h.tx().attempts);
        assertNull(h.service().lastFailure);
    }

    @Test
    public void robotCredentialRenewsStrictlyBeforeTheService() {
        Harness h = harness(30, 30);
        String serviceFingerprintBefore = fingerprint(h.pki().serviceCertificate());
        String robotFingerprintBefore = fingerprint(certOf(h.writer().stores.get(ROBOT_LOCATION)));

        h.service().renew(new Date());

        assertEquals(List.of("user:" + ORG_MRN + ":" + ROBOT_MRN, "service:" + ORG_MRN + ":" + SERVICE_MRN),
                h.mir().calls, "robot first (it authenticates the service renewal), MRNs derived from the certs");
        assertNotEquals(serviceFingerprintBefore, fingerprint(h.pki().serviceCertificate()),
                "the live service certificate was swapped");
        assertNotEquals(robotFingerprintBefore, fingerprint(certOf(h.writer().stores.get(ROBOT_LOCATION))),
                "the persisted robot credential was rotated");
        assertEquals(1, h.pki().reloads);
        assertEquals(2, h.tx().attempts, "two success confirmations");
        assertNull(h.service().lastFailure);
    }

    @Test
    public void aFailedRobotRenewalStillAttemptsTheService() {
        Harness h = harness(30, 30);
        h.mir().failUserCalls = true;
        String serviceFingerprintBefore = fingerprint(h.pki().serviceCertificate());

        h.service().renew(new Date());

        assertEquals(2, h.mir().calls.size(), "the service renewal ran despite the robot failure");
        assertNotEquals(serviceFingerprintBefore, fingerprint(h.pki().serviceCertificate()));
        assertNotNull(h.service().lastFailure);
        assertTrue(h.service().lastFailure.contains("Robot credential"), "unexpected: " + h.service().lastFailure);
        assertEquals(2, h.tx().attempts, "one success confirmation + one failure alert");
    }

    @Test
    public void aReloadStillServingTheOldCertificateFailsTheRenewal() {
        Harness h = harness(36500, 30);   // only the service is due
        h.writer().swallowWrites = true;  // the persist silently goes nowhere
        String fingerprintBefore = fingerprint(h.pki().serviceCertificate());

        h.service().renew(new Date());

        assertEquals(fingerprintBefore, fingerprint(h.pki().serviceCertificate()));
        assertNotNull(h.service().lastFailure);
        assertTrue(h.service().lastFailure.contains("still serves the previous certificate"),
                "unexpected: " + h.service().lastFailure);
        assertEquals(1, h.tx().attempts, "just the failure alert - no success mail");
    }

    @Test
    public void uncommittedFailureAlertsAreRetriedOnTheNextRun() {
        Harness h = harness(36500, 30);
        h.mir().failServiceCalls = true;
        h.tx().fail = true;

        h.service().renew(new Date());
        assertNull(h.service().lastFailure, "an uncommitted alert must not advance the dedup marker");
        assertEquals(1, h.tx().attempts);

        h.service().renew(new Date());
        assertEquals(2, h.tx().attempts, "the alert is retried while it cannot be committed");
        assertNull(h.service().lastFailure);

        h.tx().fail = false;
        h.service().renew(new Date());
        assertNotNull(h.service().lastFailure);
        assertEquals(3, h.tx().attempts);

        h.service().renew(new Date());
        assertEquals(3, h.tx().attempts, "the unchanged failure is deduped");
    }

    @Test
    public void blankRecipientsAdvanceTheMarkerWithoutTouchingTheDatabase() {
        Harness h = harness(36500, 30);
        h.mir().failServiceCalls = true;
        h.service().alertEmails = "";

        h.service().renew(new Date());

        assertNotNull(h.service().lastFailure,
                "with no recipients there is nothing to retry - the marker advances so the error logs once");
        assertEquals(0, h.tx().attempts);
    }

    @Test
    public void aDeferredBackendSkipsReadBackAndLeavesPickupToTheStoreWatch() {
        DeferredWriter writer = new DeferredWriter();
        Harness h = harness(30, 30, writer);
        String serviceFingerprintBefore = fingerprint(h.pki().serviceCertificate());
        String robotFingerprintBefore = fingerprint(certOf(writer.stores.get(ROBOT_LOCATION)));

        h.service().renew(new Date());

        // The authoritative stores rotated durably...
        assertNotEquals(serviceFingerprintBefore, fingerprint(certOf(writer.stores.get(SERVICE_LOCATION))));
        assertNotEquals(robotFingerprintBefore, fingerprint(certOf(writer.stores.get(ROBOT_LOCATION))));
        // ...but nothing was read back or reloaded: the mount converges later (kubelet + store watch)
        assertEquals(0, h.pki().reloads, "a deferred backend must not reload the stale mount");
        assertEquals(serviceFingerprintBefore, fingerprint(h.pki().serviceCertificate()),
                "the in-memory material serves the previous certificate until the store watch converges");
        assertEquals(2, h.tx().attempts, "two success confirmations");
        assertNull(h.service().lastFailure);
    }

    @Test
    public void aStalledDeferredPickupPausesReissuingAndAlerts() {
        DeferredWriter writer = new DeferredWriter();
        writer.swallowWrites = true;   // the PATCHed Secret never reaches the mounted stores
        Harness h = harness(30, 30, writer);

        h.service().renew(new Date());
        assertEquals(2, h.mir().calls.size(), "first run issues both credentials");
        assertNull(h.service().lastFailure, "the writes succeeded as far as a deferred backend can tell");

        h.service().renew(new Date());
        assertEquals(2, h.mir().calls.size(),
                "the stalled pickup must pause re-issuing - not mint fresh MIR identities daily");
        assertNotNull(h.service().lastFailure);
        assertTrue(h.service().lastFailure.contains("never became visible"),
                "unexpected: " + h.service().lastFailure);

        // The mounts converge after all (the mapping was fixed and fresh material synced in):
        // the pause clears and - the certificates still being due - renewal resumes
        writer.swallowWrites = false;
        writer.stores.put(SERVICE_LOCATION, CA.mintIdentity(SERVICE_MRN, daysFromNow(-1), daysFromNow(30), "1", "changeit"));
        writer.stores.put(ROBOT_LOCATION, CA.mintIdentity(ROBOT_MRN, daysFromNow(-1), daysFromNow(30), "1", "changeit"));
        h.pki().reload();
        h.service().renew(new Date());
        assertEquals(4, h.mir().calls.size(), "a converged pickup clears the pause");
        assertNull(h.service().lastFailure);
    }
}
