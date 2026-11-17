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
package org.niord.secom.pki;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.secom.pki.TestCertificateAuthority.daysFromNow;

/**
 * Unit tests for the MIR client's mTLS credential selection: the robot store is reduced to
 * exactly the configured alias entry before it reaches the key manager - the default key
 * manager picks an arbitrary suitable entry from a multi-entry store, which could present a
 * different identity than the alias the renewal service validated and derived the robot MRN
 * from. The HTTP flows themselves run in {@code SecomCertificateRenewalITTest} against a
 * stub MIR.
 */
public class McpMirRestClientTest {

    static final TestCertificateAuthority CA = TestCertificateAuthority.create("MIR Client Test CA");
    static final String ROBOT_MRN = "urn:mrn:mcp:user:mcc:test:robot";
    static final String OLD_ROBOT_MRN = "urn:mrn:mcp:user:mcc:test:old-robot";
    static final char[] PASSWORD = "changeit".toCharArray();

    /** A robot store holding TWO private-key identities - the rotation-leftover scenario */
    static KeyStore multiEntryStore() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        copyEntry(store, "old-robot", OLD_ROBOT_MRN);
        copyEntry(store, "1", ROBOT_MRN);
        return store;
    }

    static void copyEntry(KeyStore target, String alias, String mrn) throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        source.load(new ByteArrayInputStream(
                CA.mintIdentity(mrn, daysFromNow(-1), daysFromNow(180), "1", "changeit")), PASSWORD);
        target.setKeyEntry(alias, source.getKey("1", PASSWORD), PASSWORD, source.getCertificateChain("1"));
    }

    @Test
    public void singleEntryPinsTheConfiguredAlias() throws Exception {
        KeyStore reduced = McpMirRestClient.singleEntry(multiEntryStore(), "1", PASSWORD);

        assertEquals(List.of("1"), Collections.list(reduced.aliases()),
                "only the configured alias survives - the key manager cannot pick another identity");
        assertEquals(ROBOT_MRN, CertificateRenewalUtils.subjectMrn((X509Certificate) reduced.getCertificate("1")));
        assertEquals(2, reduced.getCertificateChain("1").length, "the full chain travels with the entry");
        assertNotNull(reduced.getKey("1", PASSWORD));
    }

    @Test
    public void singleEntryRejectsAMissingAlias() throws Exception {
        KeyStore store = multiEntryStore();
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                McpMirRestClient.singleEntry(store, "no-such-alias", PASSWORD));
        assertTrue(e.getMessage().contains("no-such-alias"), "unexpected: " + e.getMessage());
    }

    @Test
    public void mirSslContextBuildsOverTheAliasEntry() throws Exception {
        assertNotNull(McpMirRestClient.mirSslContext(multiEntryStore(), "1", PASSWORD));
    }
}
