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

import org.grad.eNav.s100.utils.S100ExchangeSetUtils;
import org.grad.secomv2.core.utils.SecomPemUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.iso.standards.iso._19115.__3.lan._1.PTLocalePropertyType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.niord.core.NiordApp;
import org.niord.s124.S124Service;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124DatasetVersion;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.pki.TestCertificateAuthority;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import dk.dma.niord.s100.catalog._5_2.S100DatasetDiscoveryMetadata;
import dk.dma.niord.s100.catalog._5_2.S100ExchangeCatalogue;
import dk.dma.niord.s100.catalog._5_2.S100Purpose;
import dk.dma.niord.s100.catalog._5_2.S100SECertificateContainerType;
import dk.dma.niord.s100.catalog._5_2.S100SECertificateType;
import dk.dma.niord.s100.catalog._5_2.S100SESignatureOnData;
import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.impl.DataSetIdentificationTypeImpl;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.impl.BoundingShapeTypeImpl;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.impl.EnvelopeTypeImpl;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.impl.PosImpl;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.Dataset;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.GeneralAreaType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.LocationNameType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.MessageSeriesIdentifierType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnPreamble;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnTypeGeneralLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnTypeGeneralType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.ObjectFactory;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.WarningTypeLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.WarningTypeType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.exchangesets.S124ExchangeSetFactory;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.util.S124DatasetInfo;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.util.S124Utils;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link S124ExchangeSetService}: publishing an immutable dataset version
 * ({@code publish} - signed once, in the build that packages it), serving it verbatim in every
 * later exchange set (same bytes, same entry, same signature, whatever the configuration or
 * certificate has become), the S-100 Part 17 clause 17-4.4.1 fileless cancellation entries
 * reusing that original signature, and the issuing chain every exchange set carries (S-100
 * Part 15, clause 15-8.7) - for the current certificate and for a rotated-out one a version
 * was signed under. Signing uses the committed test PKI under {@code secom-test/} (real ECDSA,
 * so a re-signed payload never equals the published signature by accident).
 */
public class S124ExchangeSetServiceTest {

    private static X509Certificate testCertificate;
    private static X509Certificate testRoot;
    private static PrivateKey testKey;

    /**
     * How the catalogue spells a dataset's {@code fileName}: the path the archive packages it
     * under, relative to {@code CATALOG.XML} (S-100 Part 17, clause 17-4.2), which the bindings
     * emit as of 0.3.1 - the form of the IHO S-164 test data. The persisted
     * {@code S124DatasetVersion.fileName} stays the bare name.
     */
    private static final String CATALOGUE_FILE_URI_PREFIX = "file:/S-124/DATASET_FILES/";

    private static final String BASE_URI = "https://niord.example.org";

    @BeforeAll
    static void loadTestPki() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = S124ExchangeSetServiceTest.class.getResourceAsStream("/secom-test/keystore-test.p12")) {
            assertNotNull(in, "secom-test keystore must be on the test classpath");
            keyStore.load(in, "changeit".toCharArray());
        }
        testCertificate = (X509Certificate) keyStore.getCertificate("1");
        testKey = (PrivateKey) keyStore.getKey("1", "changeit".toCharArray());
        // The keystore entry stores the leaf with its root, as the committed PKI script packs it
        testRoot = (X509Certificate) keyStore.getCertificateChain("1")[1];
    }

    /** A PKI mock signing as the committed test identity, whose root issues the leaf directly */
    private static McpSecurityService testPki() throws Exception {
        McpSecurityService pki = Mockito.mock(McpSecurityService.class);
        when(pki.serviceCertificate()).thenReturn(testCertificate);
        when(pki.serviceCertificateChain()).thenReturn(List.of(testCertificate, testRoot));
        when(pki.sign(anyString(), any(byte[].class))).thenAnswer(invocation -> {
            Signature signature = Signature.getInstance(invocation.getArgument(0));
            signature.initSign(testKey);
            signature.update((byte[]) invocation.getArgument(1));
            return signature.sign();
        });
        return pki;
    }

    /** The service under test on top of the test PKI; the persistence-facing em is never touched */
    private static S124ExchangeSetService service() throws Exception {
        return service(testPki());
    }

    /** The service under test on top of the given PKI (a failing one, for the fault cases) */
    private static S124ExchangeSetService service(McpSecurityService pki) {
        return service(pki, BASE_URI);
    }

    private static S124ExchangeSetService service(McpSecurityService pki, String baseUri) {
        S124Service s124Service = Mockito.mock(S124Service.class);
        when(s124Service.responsibleAgency()).thenReturn("Danish Maritime Authority");
        when(s124Service.producerCode()).thenReturn("DK00");
        NiordApp app = Mockito.mock(NiordApp.class);
        when(app.getBaseUri()).thenReturn(baseUri);
        return new S124ExchangeSetService(pki, s124Service, app);
    }

    /** A PKI mock signing as the given minted identity, whose certification path runs up to its root */
    private static McpSecurityService pkiFor(TestCertificateAuthority.Identity identity) throws Exception {
        McpSecurityService pki = Mockito.mock(McpSecurityService.class);
        when(pki.serviceCertificate()).thenReturn(identity.certificate());
        when(pki.serviceCertificateChain()).thenReturn(identity.chain());
        when(pki.sign(anyString(), any(byte[].class))).thenAnswer(invocation -> {
            Signature signature = Signature.getInstance(invocation.getArgument(0));
            signature.initSign(identity.key());
            signature.update((byte[]) invocation.getArgument(1));
            return signature.sign();
        });
        return pki;
    }

    /** An MCP-shaped PKI for the tests below: a root, the identity registry as its intermediate */
    private record TestMcp(TestCertificateAuthority root, TestCertificateAuthority intermediate) {
        static TestMcp create() {
            TestCertificateAuthority root = TestCertificateAuthority.create("Test MCP Root");
            return new TestMcp(root, root.issueIntermediate("Test MCP Identity Registry"));
        }

        TestCertificateAuthority.Identity mintService(String name) {
            return intermediate.mintLeaf("urn:mrn:mcp:service:mcc:test:instance:" + name,
                    TestCertificateAuthority.daysFromNow(-1), TestCertificateAuthority.daysFromNow(365));
        }
    }

    /***************************************/
    /** Publishing                        **/
    /***************************************/

    @Test
    public void publishRecordsExactlyWhatTheExchangeSetShipped() throws Exception {
        S124ExchangeSetService service = service();
        Dataset gml = newGml("NW-069-26", "urn:mrn:iho:s124:dk:nw:069:26", "Test S-124 Dataset");

        S124DatasetVersion version = service.publish(gml, List.of("en"));

        // The S-100 Part 17 clause 17-4.3 name the factory published: the header's own
        // datasetFileIdentifier (Part 10b Table 10b-4 makes it the name of the packaged file)
        assertEquals("124DK00NW06926.GML", version.getFileName());
        // The identification GetSummary reports, captured so serving never parses the GML
        assertEquals("NW-069-26", version.getDatasetId());
        assertEquals("Test S-124 Dataset", version.getDatasetTitle());
        // The bytes are the factory's - which are the generator's marshalled XML, the string the
        // dataset store compares a regeneration against to tell unchanged content
        assertEquals(S124Utils.marshalS124(gml), version.getGml());

        // The signature cryptographically verifies against the service certificate over the
        // exact bytes the version holds, and is the one embedded in the published entry
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(testCertificate.getPublicKey());
        verifier.update(version.gmlBytes());
        assertTrue(verifier.verify(version.getSignature()), "the published signature must verify");
        S100DatasetDiscoveryMetadata entry = S124ExchangeSetFactory.discoveryMetadataFromXml(version.getDiscoveryMetadata());
        assertEquals(CATALOGUE_FILE_URI_PREFIX + version.getFileName(), entry.getFileName());
        assertEquals(LocalDate.of(2026, 1, 15), entry.getIssueDate());
        assertArrayEquals(version.getSignature(), datasetSignature(entry));
        // The chain that made it: the leaf alone, since the test root issues it directly
        assertEquals(List.of(SecomPemUtils.getMinifiedPemFromCert(testCertificate)), version.certificatePems());
        // The languages the file is spelled in, recorded so serving can declare them
        assertEquals(List.of("en"), version.getLanguages());
    }

    @Test
    public void exchangeSetsDeclareTheDatasetLanguagesAsTheirLocales() throws Exception {
        S124ExchangeSetService service = service();

        // A Danish-primary dataset with English texts as well: the catalogue's default locale
        // is the primary language and the others follow (S-100 Part 17, Table 17-2)
        S124Dataset dataset = publishedDataset(service, "NW-070-26", "urn:mrn:iho:s124:dk:nw:070:26",
                List.of("da", "en"));
        assertEquals(List.of("da", "en"), dataset.getCurrentVersion().getLanguages());

        S100ExchangeCatalogue catalogue = catalogueOf(service.buildExchangeSet(List.of(dataset), List.of()));
        assertEquals("dan", localeLanguage(catalogue.getDefaultLocale()));
        assertEquals(List.of("eng"), catalogue.getOtherLocales().stream().map(S124ExchangeSetServiceTest::localeLanguage).toList());

        // A set shipping several datasets declares the union, in encounter order
        S124Dataset english = publishedDataset(service, "NW-071-26", "urn:mrn:iho:s124:dk:nw:071:26");
        S100ExchangeCatalogue both = catalogueOf(service.buildExchangeSet(List.of(english, dataset), List.of()));
        assertEquals("eng", localeLanguage(both.getDefaultLocale()));
        assertEquals(List.of("dan"), both.getOtherLocales().stream().map(S124ExchangeSetServiceTest::localeLanguage).toList());
    }

    @Test
    public void publishedEntriesDeclareTheDatasetLanguagesAsWell() throws Exception {
        // The per-dataset discovery-metadata entry has its own default/other locales (S-100
        // Part 17, Table 17-3), expected to follow the build's locale list like the catalogue's
        S124ExchangeSetService service = service();
        Dataset gml = newGml("NW-072-26", "urn:mrn:iho:s124:dk:nw:072:26", "Test S-124 Dataset");
        S124DatasetVersion version = service.publish(gml, List.of("da", "en"));

        S100DatasetDiscoveryMetadata entry = S124ExchangeSetFactory.discoveryMetadataFromXml(version.getDiscoveryMetadata());
        // bindings 0.3.3 leaves the entry's locales empty (optional per the table, so the entry
        // stays valid); the assertions arm themselves once the factory copies the build's
        // locales into each entry - docs/secom-go-live.md, 'Languages and the S-124 bindings'
        Assumptions.assumeTrue(entry.getDefaultLocale() != null,
                "S124ExchangeSetFactory does not yet declare per-entry locales");
        assertEquals("dan", localeLanguage(entry.getDefaultLocale()));
        assertEquals(List.of("eng"), entry.getOtherLocales().stream().map(S124ExchangeSetServiceTest::localeLanguage).toList());
    }

    /** The ISO 639-2 language code of a catalogue PT_Locale */
    private static String localeLanguage(PTLocalePropertyType locale) {
        return locale.getPTLocale().getValue().getLanguage().getLanguageCode().getCodeListValue();
    }

    @Test
    public void exchangeSetsShipTheVersionVerbatimAndStably() throws Exception {
        S124ExchangeSetService service = service();
        S124Dataset dataset = publishedDataset(service, "NW-001-26", "urn:mrn:iho:s124:dk:nw:001:26");
        S124DatasetVersion version = dataset.getCurrentVersion();

        byte[] zip1 = service.buildExchangeSet(List.of(dataset), List.of());
        byte[] zip2 = service.buildExchangeSet(List.of(dataset), List.of());

        // Both ship the version's bytes byte for byte, under the persisted signature - not a
        // fresh one per request - and under the persisted file name
        String file = "S100_ROOT/S-124/DATASET_FILES/" + version.getFileName();
        assertArrayEquals(version.gmlBytes(), unzip(zip1).get(file));
        assertArrayEquals(version.gmlBytes(), unzip(zip2).get(file));
        assertArrayEquals(version.getSignature(), datasetSignature(soleDatasetEntry(zip1)));
        assertArrayEquals(version.getSignature(), datasetSignature(soleDatasetEntry(zip2)));
        assertEquals(CATALOGUE_FILE_URI_PREFIX + version.getFileName(), soleDatasetEntry(zip1).getFileName());

        // The catalogue itself is signed fresh each build (it embeds build timestamps)
        assertFalse(java.util.Arrays.equals(unzip(zip1).get("S100_ROOT/CATALOG.SIGN"),
                unzip(zip2).get("S100_ROOT/CATALOG.SIGN")), "CATALOG.SIGN must be signed per build");
    }

    @Test
    public void servedEntryIsThePublishedEntryNotTheCurrentConfiguration() throws Exception {
        // The entry consumers see is the one a later cancellation reproduces (clause 17-4.4.1),
        // so it must be what was published - not rebuilt from configuration that has moved on
        S124Dataset dataset = publishedDataset(service(), "NW-002-26", "urn:mrn:iho:s124:dk:nw:002:26");
        S124ExchangeSetService reconfigured = service(testPki(), "https://elsewhere.example.org");

        S100DatasetDiscoveryMetadata served = soleDatasetEntry(reconfigured.buildExchangeSet(List.of(dataset), List.of()));

        assertEquals(S100Purpose.NEW_DATASET, served.getPurpose());
        String published = S124ExchangeSetFactory.discoveryMetadataToXml(
                S124ExchangeSetFactory.discoveryMetadataFromXml(dataset.getCurrentVersion().getDiscoveryMetadata()));
        assertEquals(withoutSignature(published), withoutSignature(S124ExchangeSetFactory.discoveryMetadataToXml(served)),
                "the served entry is the published one, base URI and all");
        assertTrue(published.contains(BASE_URI));
        assertFalse(published.contains("elsewhere"));
    }

    /**
     * A marshalled discovery metadata entry with its signature elements removed, whatever
     * namespace prefix JAXB gave them in this JVM. The signature is compared separately: the
     * published entry writes the substitution-group head with an {@code xsi:type} and the
     * reproduced one the {@code S100_SE_SignatureOnData} member element, which is the same
     * signature to a reader but not the same text.
     */
    private static String withoutSignature(String entryXml) {
        return entryXml.replaceAll(
                "(?s)<(?:[\\w.-]+:)?digitalSignatureValue>.*?</(?:[\\w.-]+:)?digitalSignatureValue>", "");
    }

    /***************************************/
    /** Fileless cancellations            **/
    /***************************************/

    @Test
    public void cancellationEntryReusesTheOriginalSignature() throws Exception {
        S124ExchangeSetService service = service();
        S124Dataset active = publishedDataset(service, "NW-010-26", "urn:mrn:iho:s124:dk:nw:010:26");
        S124Dataset cancelled = publishedDataset(service, "NW-005-26", "urn:mrn:iho:s124:dk:nw:005:26");
        cancelled.setStatus(S124Dataset.Status.CANCELLED);
        cancelled.setCancelled(new Date());

        byte[] zip = service.buildExchangeSet(List.of(active), List.of(cancelled));

        // Fileless: only the active dataset ships as a file
        Map<String, byte[]> entries = unzip(zip);
        assertTrue(entries.containsKey("S100_ROOT/S-124/DATASET_FILES/" + active.getCurrentVersion().getFileName()));
        assertFalse(entries.containsKey("S100_ROOT/S-124/DATASET_FILES/" + cancelled.getCurrentVersion().getFileName()),
                "the cancelled dataset must not ship a file");

        List<S100DatasetDiscoveryMetadata> metadata = catalogueOf(zip)
                .getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas();
        assertEquals(2, metadata.size());

        S100DatasetDiscoveryMetadata entry = metadata.stream()
                .filter(m -> m.getPurpose() == S100Purpose.CANCELLATION)
                .findFirst().orElseThrow();
        assertEquals(CATALOGUE_FILE_URI_PREFIX + cancelled.getCurrentVersion().getFileName(), entry.getFileName());
        assertEquals(cancelled.getMrn(), entry.getDatasetID());
        // Clause 17-4.4.1 reproduces the original entry "with the exception of the issueDate",
        // which is the date of the cancellation itself
        assertEquals(cancelled.getCancelled().toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                entry.getIssueDate());
        // The original, published signature is reused verbatim - the consumer's matching key
        assertArrayEquals(cancelled.getCurrentVersion().getSignature(), datasetSignature(entry));

        // Made by the current certificate, so the signature references the catalogue's own
        assertEquals("cer1", certificateRefOf(entry));
        assertTrue(certificatesById(catalogueOf(zip).getCertificates().get(0)).containsKey("cer1"));
    }

    @Test
    public void unmappableCancellationCandidateIsSkipped() throws Exception {
        S124ExchangeSetService service = service();
        S124Dataset active = publishedDataset(service, "NW-012-26", "urn:mrn:iho:s124:dk:nw:012:26");
        S124Dataset broken = publishedDataset(service, "NW-007-26", "urn:mrn:iho:s124:dk:nw:007:26");
        broken.setStatus(S124Dataset.Status.CANCELLED);
        broken.setCancelled(new Date());
        // The published entry a cancellation reproduces (clause 17-4.4.1) cannot be read back
        broken.getCurrentVersion().setDiscoveryMetadata("<not-the-published-discovery-metadata/>");

        // The broken candidate is skipped; the exchange set still builds
        List<S100DatasetDiscoveryMetadata> metadata =
                catalogueOf(service.buildExchangeSet(List.of(active), List.of(broken)))
                        .getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas();

        assertEquals(1, metadata.size());
        assertEquals(S100Purpose.NEW_DATASET, metadata.get(0).getPurpose());
    }

    @Test
    public void aPageBuiltInOneBatchEqualsSeparateBuildsAndNeverCancelsTheShippedDataset() throws Exception {
        // Get/GetSummary build a page's exchange sets in one batch that reads the cancellation
        // candidates once; each set must still be exactly the one a separate build produces
        S124ExchangeSetService service = service();
        S124Dataset a = publishedDataset(service, "NW-030-26", "urn:mrn:iho:s124:dk:nw:030:26");
        S124Dataset b = publishedDataset(service, "NW-031-26", "urn:mrn:iho:s124:dk:nw:031:26");
        S124Dataset c = publishedDataset(service, "NW-032-26", "urn:mrn:iho:s124:dk:nw:032:26");
        for (S124Dataset cancelled : List.of(c, a)) {
            cancelled.setStatus(S124Dataset.Status.CANCELLED);
            cancelled.setCancelled(new Date());
        }

        List<byte[]> batch = service.buildExchangeSets(List.of(a, b), List.of(c, a));

        assertEquals(2, batch.size(), "one exchange set per dataset, in page order");
        // a's set ships a and cancels only c - a dataset is never shipped and cancelled at once
        assertEquals(entryXmls(service.buildExchangeSet(List.of(a), List.of(c))), entryXmls(batch.get(0)));
        assertEquals(List.of(a.getMrn()), shippedIds(batch.get(0)));
        assertEquals(List.of(c.getMrn()), cancelledIds(batch.get(0)));
        // b's set ships b and cancels both c and a
        assertEquals(entryXmls(service.buildExchangeSet(List.of(b), List.of(c, a))), entryXmls(batch.get(1)));
        assertEquals(List.of(b.getMrn()), shippedIds(batch.get(1)));
        assertEquals(List.of(c.getMrn(), a.getMrn()), cancelledIds(batch.get(1)));

        assertEquals(List.of(), service.buildExchangeSets(List.of()), "an empty page builds nothing");
    }

    /** The catalogue's discovery-metadata entries of an exchange set, as XML, in catalogue order */
    private static List<String> entryXmls(byte[] zip) throws Exception {
        return catalogueOf(zip).getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas().stream()
                .map(S124ExchangeSetFactory::discoveryMetadataToXml)
                .toList();
    }

    /** The dataset IDs of the entries an exchange set ships as files */
    private static List<String> shippedIds(byte[] zip) throws Exception {
        return catalogueOf(zip).getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas().stream()
                .filter(m -> m.getPurpose() != S100Purpose.CANCELLATION)
                .map(S100DatasetDiscoveryMetadata::getDatasetID)
                .toList();
    }

    /** The dataset IDs of the fileless cancellation entries of an exchange set */
    private static List<String> cancelledIds(byte[] zip) throws Exception {
        return catalogueOf(zip).getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas().stream()
                .filter(m -> m.getPurpose() == S100Purpose.CANCELLATION)
                .map(S100DatasetDiscoveryMetadata::getDatasetID)
                .toList();
    }

    @Test
    public void catalogueWithCancellationIsSchemaValid() throws Exception {
        S124ExchangeSetService service = service();
        S124Dataset active = publishedDataset(service, "NW-013-26", "urn:mrn:iho:s124:dk:nw:013:26");
        S124Dataset cancelled = publishedDataset(service, "NW-008-26", "urn:mrn:iho:s124:dk:nw:008:26");
        cancelled.setStatus(S124Dataset.Status.CANCELLED);
        cancelled.setCancelled(new Date());

        String catalogXml = new String(
                unzip(service.buildExchangeSet(List.of(active), List.of(cancelled))).get("S100_ROOT/CATALOG.XML"),
                StandardCharsets.UTF_8);

        List<String> errors = new ArrayList<>();
        Validator validator = catalogueSchema().newValidator();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
            }

            @Override
            public void error(SAXParseException e) {
                errors.add("line " + e.getLineNumber() + ": " + e.getMessage());
            }

            @Override
            public void fatalError(SAXParseException e) {
                errors.add("fatal, line " + e.getLineNumber() + ": " + e.getMessage());
            }
        });
        validator.validate(new StreamSource(new StringReader(catalogXml)));
        assertEquals(List.of(), errors, "XSD validation errors in CATALOG.XML");
    }

    /**
     * The S-100 5.2.0 exchange catalogue schema. The catalogue XSD's ISO 19115-3 imports are
     * relative paths that only resolve next to the schema's source-tree location, so the
     * schema is compiled from the bindings repository at the pinned release tag (which also
     * lets the absolute schemas.isotc211.org imports resolve). Skips when offline.
     */
    private static Schema catalogueSchema() {
        try {
            return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                    .newSchema(URI.create("https://raw.githubusercontent.com/NiordOrg/niord-xml-bindings/0.3.0/"
                            + "s-100/src/main/resources/xsd/S100Catalog/20240415/S100_ExchangeCatalogue.xsd").toURL());
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "exchange catalogue schema unavailable (offline?): " + e.getMessage());
            throw new IllegalStateException("unreachable");
        }
    }

    /***************************************/
    /** Issuing chain and rotation        **/
    /***************************************/

    @Test
    public void exchangeSetCarriesTheIssuingChainAndNamesTheMcpRootAsSchemeAdministrator() throws Exception {
        // S-100 Part 15, clause 15-8.7: the Data Server includes its Domain Coordinator's
        // certificate - for an MCP identity the identity registry's intermediate - so the
        // consumer validates the path with nothing but the root it has installed; the root is
        // never carried, and the scheme administrator the catalogue names is that root, not IHO
        TestMcp mcp = TestMcp.create();
        TestCertificateAuthority.Identity identity = mcp.mintService("niord-chain");
        S124ExchangeSetService service = service(pkiFor(identity));

        S124Dataset dataset = publishedDataset(service, "NW-020-26", "urn:mrn:iho:s124:dk:nw:020:26");

        // The version records the chain that made it: leaf first, then the intermediate, no root
        assertEquals(List.of(SecomPemUtils.getMinifiedPemFromCert(identity.certificate()),
                        SecomPemUtils.getMinifiedPemFromCert(mcp.intermediate().certificate())),
                dataset.getCurrentVersion().certificatePems());

        byte[] zip = service.buildExchangeSet(List.of(dataset), List.of());
        S100SECertificateContainerType container = catalogueOf(zip).getCertificates().get(0);
        assertEquals("Test MCP Root", container.getSchemeAdministrator().getId());
        Map<String, S100SECertificateType> byId = certificatesById(container);
        assertEquals(Set.of("cer1", "ca1"), byId.keySet(), "leaf and intermediate, never the root");
        assertArrayEquals(identity.certificate().getEncoded(), byId.get("cer1").getValue());
        assertEquals("ca1", byId.get("cer1").getIssuer(), "the leaf resolves to the carried intermediate");
        assertArrayEquals(mcp.intermediate().certificate().getEncoded(), byId.get("ca1").getValue());
        assertEquals("Test MCP Root", byId.get("ca1").getIssuer(), "the intermediate resolves to the scheme administrator");
        assertEquals("cer1", certificateRefOf(soleDatasetEntry(zip)), "the version's own certificate is the current one");

        // CATALOG.SIGN declares the same chain and scheme administrator, or the two files disagree
        String catalogSign = new String(unzip(zip).get("S100_ROOT/CATALOG.SIGN"), StandardCharsets.UTF_8);
        assertTrue(catalogSign.contains("schemeAdministrator id=\"Test MCP Root\""), catalogSign);
        assertTrue(catalogSign.contains("id=\"ca1\" issuer=\"Test MCP Root\""), catalogSign);
        assertTrue(catalogSign.contains("id=\"cer1\" issuer=\"ca1\""), catalogSign);
    }

    @Test
    public void versionPublishedUnderARotatedOutCertificateShipsWithItsChain() throws Exception {
        // A key rotation changes nothing about a published version: it ships under the signature
        // it was published with - the one every cancellation entry will reproduce - with the
        // rotated-out certificate and its issuing chain carried so the signature verifies (S-100
        // Part 15, clause 15-8.7), while the catalogue is signed by the current certificate
        TestMcp mcp = TestMcp.create();
        TestCertificateAuthority.Identity previous = mcp.mintService("niord-previous");
        TestCertificateAuthority.Identity current = mcp.mintService("niord-current");
        S124Dataset dataset = publishedDataset(service(pkiFor(previous)), "NW-021-26", "urn:mrn:iho:s124:dk:nw:021:26");
        S124DatasetVersion version = dataset.getCurrentVersion();

        byte[] zip = service(pkiFor(current)).buildExchangeSet(List.of(dataset), List.of());

        S100ExchangeCatalogue catalogue = catalogueOf(zip);
        S100DatasetDiscoveryMetadata entry = soleDatasetEntry(zip);
        assertArrayEquals(version.getSignature(), datasetSignature(entry), "the published signature ships, not a fresh one");
        String ref = certificateRefOf(entry);
        assertNotEquals("cer1", ref, "the shipped signature must not claim the current certificate");
        Map<String, S100SECertificateType> byId = certificatesById(catalogue.getCertificates().get(0));
        assertArrayEquals(previous.certificate().getEncoded(), byId.get(ref).getValue(),
                "the referenced certificate is the rotated-out one that made the signature");
        assertArrayEquals(current.certificate().getEncoded(), byId.get("cer1").getValue(),
                "the catalogue is signed by the current certificate");
        // Both leaves chain to the one intermediate, carried once and resolving to the root
        assertEquals(byId.get("cer1").getIssuer(), byId.get(ref).getIssuer());
        S100SECertificateType intermediate = byId.get(byId.get(ref).getIssuer());
        assertArrayEquals(mcp.intermediate().certificate().getEncoded(), intermediate.getValue());
        assertEquals("Test MCP Root", intermediate.getIssuer());
        assertEquals(3, byId.size(), "current leaf, previous leaf and the one shared intermediate");

        // And the signature verifies against that certificate over the shipped file bytes
        byte[] file = unzip(zip).get("S100_ROOT/S-124/DATASET_FILES/" + version.getFileName());
        assertArrayEquals(version.gmlBytes(), file);
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(previous.certificate().getPublicKey());
        verifier.update(file);
        assertTrue(verifier.verify(datasetSignature(entry)));
    }

    @Test
    public void cancellationEntryUnderARotatedOutCertificateCarriesItsIssuingChain() throws Exception {
        TestMcp mcp = TestMcp.create();
        TestCertificateAuthority.Identity previous = mcp.mintService("niord-previous");
        TestCertificateAuthority.Identity current = mcp.mintService("niord-current");
        S124Dataset cancelled = publishedDataset(service(pkiFor(previous)), "NW-022-26", "urn:mrn:iho:s124:dk:nw:022:26");
        cancelled.setStatus(S124Dataset.Status.CANCELLED);
        cancelled.setCancelled(new Date());
        S124ExchangeSetService rotated = service(pkiFor(current));
        S124Dataset active = publishedDataset(rotated, "NW-023-26", "urn:mrn:iho:s124:dk:nw:023:26");

        S100ExchangeCatalogue catalogue = catalogueOf(rotated.buildExchangeSet(List.of(active), List.of(cancelled)));

        S100DatasetDiscoveryMetadata cancellation = catalogue.getDatasetDiscoveryMetadata()
                .getS100DatasetDiscoveryMetadatas().stream()
                .filter(m -> m.getPurpose() == S100Purpose.CANCELLATION)
                .findFirst().orElseThrow(() -> new AssertionError("no cancellation entry in the catalogue"));
        assertArrayEquals(cancelled.getCurrentVersion().getSignature(), datasetSignature(cancellation));
        String ref = certificateRefOf(cancellation);
        assertNotEquals("cer1", ref, "the reused signature must not claim the current certificate");
        Map<String, S100SECertificateType> byId = certificatesById(catalogue.getCertificates().get(0));
        assertArrayEquals(previous.certificate().getEncoded(), byId.get(ref).getValue(),
                "the carried certificate must be the one the signature was made under");
        // The intermediate that issued it is carried (shared with the current chain) and resolves to the root
        S100SECertificateType intermediate = byId.get(byId.get(ref).getIssuer());
        assertArrayEquals(mcp.intermediate().certificate().getEncoded(), intermediate.getValue());
        assertEquals("Test MCP Root", intermediate.getIssuer());
        assertEquals(3, byId.size(), "current leaf, previous leaf and the one shared intermediate");
    }

    /***************************************/
    /** Server faults                     **/
    /***************************************/

    @Test
    public void corruptPublishedRecordIsAnExchangeSetFault() throws Exception {
        S124ExchangeSetService service = service();
        S124Dataset dataset = publishedDataset(service, "NW-007-26", "urn:mrn:iho:s124:dk:nw:007:26");
        dataset.getCurrentVersion().setDiscoveryMetadata("<not-the-entry/>");

        // A dedicated fault type, not an IllegalStateException: RestExceptionStatusAdvice renders
        // that as a 409 client-state conflict, and the sysadmin republish chain reaches this
        // site uncaught (potential-errors ERR2)
        S124ExchangeSetException e = assertThrows(S124ExchangeSetException.class,
                () -> service.buildExchangeSet(List.of(dataset), List.of()));
        assertTrue(e.getMessage().contains(dataset.getMrn()), e.getMessage());
        assertNotNull(e.getCause(), "the bindings' cause travels along");
    }

    @Test
    public void datasetWithoutAPublishedVersionIsAnExchangeSetFault() throws Exception {
        S124ExchangeSetService service = service();
        S124Dataset dataset = newDataset("urn:mrn:iho:s124:dk:nw:099:26");

        assertTrue(assertThrows(S124ExchangeSetException.class,
                () -> service.buildExchangeSet(List.of(dataset), List.of())).getMessage().contains(dataset.getMrn()));
        assertTrue(assertThrows(S124ExchangeSetException.class,
                () -> service.datasetFile(dataset)).getMessage().contains(dataset.getMrn()));
    }

    @Test
    public void signingFailureFailsThePublish() throws Exception {
        McpSecurityService pki = Mockito.mock(McpSecurityService.class);
        when(pki.serviceCertificate()).thenReturn(testCertificate);
        when(pki.serviceCertificateChain()).thenReturn(List.of(testCertificate, testRoot));
        when(pki.sign(anyString(), any(byte[].class))).thenThrow(new GeneralSecurityException("HSM unavailable"));
        S124ExchangeSetService service = service(pki);

        // The version is signed inside the factory's build, whose signer callback wraps whatever
        // the callback throws in the bindings' own ExchangeSetException - nothing is returned, so
        // nothing half-signed can be stored
        S124ExchangeSetFactory.ExchangeSetException wrapped = assertThrows(
                S124ExchangeSetFactory.ExchangeSetException.class,
                () -> service.publish(newGml("NW-008-26", "urn:mrn:iho:s124:dk:nw:008:26", "Test S-124 Dataset"), List.of("en")));
        assertTrue(wrapped.getCause() instanceof S124ExchangeSetException, String.valueOf(wrapped.getCause()));
        assertEquals("HSM unavailable", wrapped.getCause().getCause().getMessage());

        // Serving a published version needs no dataset signature, but the catalogue is signed fresh
        S124Dataset dataset = publishedDataset(service(), "NW-009-26", "urn:mrn:iho:s124:dk:nw:009:26");
        S124ExchangeSetFactory.ExchangeSetException serve = assertThrows(
                S124ExchangeSetFactory.ExchangeSetException.class,
                () -> service.buildExchangeSet(List.of(dataset), List.of()));
        assertEquals("HSM unavailable", serve.getCause().getCause().getMessage());
    }

    @Test
    public void unencodableServiceCertificateIsAnExchangeSetFault() throws Exception {
        X509Certificate broken = Mockito.mock(X509Certificate.class);
        when(broken.getEncoded()).thenThrow(new CertificateEncodingException("no DER"));
        McpSecurityService pki = Mockito.mock(McpSecurityService.class);
        when(pki.serviceCertificate()).thenReturn(broken);
        S124ExchangeSetService service = service(pki);
        S124Dataset dataset = publishedDataset(service(), "NW-011-26", "urn:mrn:iho:s124:dk:nw:011:26");

        S124ExchangeSetException e = assertThrows(S124ExchangeSetException.class,
                () -> service.buildExchangeSet(List.of(dataset), List.of()));
        assertEquals("no DER", e.getCause().getMessage());
        assertEquals("no DER", assertThrows(S124ExchangeSetException.class,
                () -> service.publish(newGml("NW-014-26", "urn:mrn:iho:s124:dk:nw:014:26", "Test S-124 Dataset"), List.of("en")))
                .getCause().getMessage());
    }

    /***************************************/
    /** Fixtures + helpers                **/
    /***************************************/

    /** A dataset row with no published version yet */
    private static S124Dataset newDataset(String mrn) {
        S124Dataset dataset = new S124Dataset();
        dataset.setMrn(mrn);
        dataset.setStatus(S124Dataset.Status.ACTIVE);
        return dataset;
    }

    /** A dataset row whose current version the given service published from a synthetic English S-124 dataset */
    private static S124Dataset publishedDataset(S124ExchangeSetService service, String gmlId, String mrn) {
        return publishedDataset(service, gmlId, mrn, List.of("en"));
    }

    /** A dataset row whose current version the given service published, spelled in the given languages */
    private static S124Dataset publishedDataset(S124ExchangeSetService service, String gmlId, String mrn,
                                                List<String> languages) {
        S124Dataset dataset = newDataset(mrn);
        S124DatasetVersion version = service.publish(newGml(gmlId, mrn, "Test S-124 Dataset"), languages);
        version.setDataset(dataset);
        dataset.setCurrentVersion(version);
        return dataset;
    }

    /**
     * A minimal conformant S-124 dataset: the bindings' conformance check (run by
     * {@code S124Utils.marshalS124} and again by the exchange-set factory) requires exactly one
     * {@code NavwarnPreamble} carrying an S-62 producer code, and the factory validates the
     * marshalled document against the S-124 schema, so every mandatory element is present.
     */
    private static Dataset newGml(String id, String mrn, String title) {
        ObjectFactory of = new ObjectFactory();
        Dataset dataset = of.createDataset();
        dataset.setId(id);

        // The identification block via the library's own carrier: the clause 17-4.3 file
        // identifier 124DK00<alphanumeric id>.GML, which the factory names the packaged file
        // after, and the Table 10b-4 applicationProfile/datasetPurpose pairing
        S124DatasetInfo info = new S124DatasetInfo(id, "DK00");
        info.setTitle(title);
        info.setLanguage("eng");
        info.setAbstractText("Synthetic dataset used for unit tests");
        info.setReferenceDate(LocalDate.of(2026, 1, 15));
        DataSetIdentificationTypeImpl ident = new DataSetIdentificationTypeImpl();
        info.applyTo(ident);
        dataset.setDatasetIdentificationInformation(ident);

        // GML positions use lat,lon order
        PosImpl lower = new PosImpl();
        lower.setValue(new Double[] { 54.0, 8.0 });
        PosImpl upper = new PosImpl();
        upper.setValue(new Double[] { 58.0, 14.0 });
        EnvelopeTypeImpl envelope = new EnvelopeTypeImpl();
        envelope.setSrsName("EPSG:4326");
        envelope.setLowerCorner(lower);
        envelope.setUpperCorner(upper);
        BoundingShapeTypeImpl boundedBy = new BoundingShapeTypeImpl();
        boundedBy.setEnvelope(envelope);
        dataset.setBoundedBy(boundedBy);

        // The one NavwarnPreamble S-124 clause 4 requires, with the schema's mandatory elements;
        // its interoperabilityIdentifier is what the catalogue's datasetID must equal
        NavwarnPreamble preamble = of.createNavwarnPreamble();
        preamble.setId("P." + id);
        GeneralAreaType generalArea = of.createGeneralAreaType();
        LocationNameType locationName = of.createLocationNameType();
        locationName.setLanguage("eng");
        locationName.setText("Kattegat");
        generalArea.getLocationNames().add(locationName);
        preamble.getGeneralAreas().add(generalArea);
        MessageSeriesIdentifierType series = of.createMessageSeriesIdentifierType();
        series.setAgencyResponsibleForProduction("DK00");
        series.setInteroperabilityIdentifier(mrn);
        series.setNameOfSeries("dma-nw");
        series.setNationality("DK");
        series.setWarningNumber(1);
        series.setYear(2026);
        WarningTypeType warningType = of.createWarningTypeType();
        warningType.setValue(WarningTypeLabel.COASTAL_NAVIGATIONAL_WARNING); // code filled in by the library
        series.setWarningType(warningType);
        preamble.setMessageSeriesIdentifier(series);
        preamble.setIntService(true);
        NavwarnTypeGeneralType typeGeneral = of.createNavwarnTypeGeneralType();
        typeGeneral.setValue(NavwarnTypeGeneralLabel.AIDS_TO_NAVIGATION_CHANGES);
        preamble.setNavwarnTypeGeneral(typeGeneral);
        preamble.setPublicationTime(OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC));
        Dataset.Members members = of.createDatasetMembers();
        members.getNavwarnPartsAndNavwarnAreaAffectedsAndTextPlacements().add(preamble);
        dataset.setMembers(members);
        return dataset;
    }

    private static S100ExchangeCatalogue catalogueOf(byte[] zip) throws Exception {
        String catalogXml = new String(unzip(zip).get("S100_ROOT/CATALOG.XML"), StandardCharsets.UTF_8);
        return S100ExchangeSetUtils.unmarshallS100ExchangeSetCatalogue(catalogXml);
    }

    private static S100DatasetDiscoveryMetadata soleDatasetEntry(byte[] zip) throws Exception {
        List<S100DatasetDiscoveryMetadata> metadata = catalogueOf(zip)
                .getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas();
        assertEquals(1, metadata.size());
        return metadata.get(0);
    }

    /** The per-dataset signature bytes of a catalogue discovery-metadata entry */
    private static byte[] datasetSignature(S100DatasetDiscoveryMetadata entry) {
        assertEquals(1, entry.getDigitalSignatureValues().size());
        return entry.getDigitalSignatureValues().get(0).getS100SEDigitalSignature().getValue().getValue();
    }

    /** The id of the certificate a catalogue entry's signature references */
    private static String certificateRefOf(S100DatasetDiscoveryMetadata entry) {
        return ((S100SESignatureOnData) entry.getDigitalSignatureValues().get(0)
                .getS100SEDigitalSignature().getValue()).getCertificateRef();
    }

    private static Map<String, S100SECertificateType> certificatesById(S100SECertificateContainerType container) {
        return container.getCertificates().stream()
                .collect(Collectors.toMap(S100SECertificateType::getId, Function.identity()));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }
}
