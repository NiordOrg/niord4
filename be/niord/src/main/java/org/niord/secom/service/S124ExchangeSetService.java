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

import org.grad.secomv2.core.models.enums.DigitalSignatureAlgorithmEnum;
import org.grad.secomv2.core.utils.SecomPemUtils;
import org.niord.core.NiordApp;
import org.niord.core.service.BaseService;
import org.niord.s124.S124Service;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124DatasetVersion;
import org.niord.secom.pki.McpSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import dk.dma.niord.s100.catalog._5_2.S100DatasetDiscoveryMetadata;
import dk.dma.niord.s100.catalog._5_2.S100SEDigitalSignatureReference;
import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.DataSetIdentificationType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.Dataset;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.exchangesets.S124ExchangeSetFactory;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Publishes S-124 dataset versions and packages them as S-100 Ed 5.0 Part 17 exchange sets
 * (the payload format the service design mandates on Upload, Get, GetSummary and Subscription
 * — containerType 1 = {@code S100_ExchangeSet}), via the bindings library's
 * {@link S124ExchangeSetFactory}: spec directory layout, {@code CATALOG.XML} discovery
 * metadata and the S-100 Part 15 signatures (per-dataset + catalogue), signed with the MCP
 * service certificate using ECDSA-384-SHA2 as the design (§3.3.3) requires.
 * <p>
 * A dataset is signed exactly once, when its version is published ({@link #publish}): the
 * typed dataset goes through one exchange-set build, and what that build packaged - the file
 * bytes, the catalogue entry with its signature, and the chain that made it - is kept as an
 * immutable {@link S124DatasetVersion}. Every exchange set built afterwards ships that record
 * verbatim ({@link S124ExchangeSetFactory.RetainedDataset}): the same bytes under the same
 * signature and the same entry, whatever the current configuration or certificate, so a
 * consumer can later match a cancellation by the signature it holds (S-100 Part 17, clause
 * 17-4.4.1). Nothing is parsed or re-marshalled on the serve path. Only the catalogue itself —
 * which embeds build timestamps and is inherently per-request — is signed fresh on every build.
 * <p>
 * Every exchange set carries the certificates a consumer needs to validate its signatures
 * without external access (S-100 Part 15, clause 15-8.7): for the catalogue, the current
 * service certificate and the MCP issuing certificates between it and the MCP root - the
 * Domain Coordinator of the clause - with the root's name as the scheme administrator (see
 * {@link #signingIdentity()}); for a version signed under a since rotated-out certificate,
 * that certificate and its chain, which are part of the version's record.
 * <p>
 * Every built exchange set also carries <em>fileless dataset cancellations</em> (S-100
 * Part 17 clause 17-4.4.1) for the datasets cancelled within the previous
 * {@value #CANCELLATION_WINDOW_DAYS} days, as S-124R010/R011 require: the cancelled dataset's
 * current version's entry reproduced with {@code purpose=cancellation} and its <em>original</em>
 * signature, but shipping no dataset file.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class S124ExchangeSetService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(S124ExchangeSetService.class);

    /** The S-100 exchange-set signature algorithm mandated by the service design (§3.3.3) */
    static final S100SEDigitalSignatureReference EXCHANGE_SET_SIGNATURE_ALGORITHM =
            S100SEDigitalSignatureReference.ECDSA_384_SHA_2;

    /**
     * The fileless-cancellation window: "each dataset provided by the S-124 service must
     * include cancellation information for datasets cancelled within the previous 365 days"
     * (S-124R010 in the no-S128 service-spec variant / S-124R011 in the S-128 variant)
     */
    static final int CANCELLATION_WINDOW_DAYS = 365;

    /**
     * The IHO S-62 producer code used in exchange-set dataset file names
     * ({@code 124<producer><unique code>.GML}) and the catalogue metadata: the same code the
     * datasets themselves carry, read from {@link S124Service#producerCode()} so the exchange
     * set and the GML can never disagree. Resolved at construction, so a SECOM-enabled
     * application without a configured {@code niord.s124.producer-code} fails startup with
     * that method's actionable error rather than serving at the first request.
     */
    final String producerCode;

    private final McpSecurityService pki;
    private final S124Service s124Service;
    private final NiordApp app;

    public S124ExchangeSetService(McpSecurityService pki, S124Service s124Service, NiordApp app) {
        this.pki = pki;
        this.s124Service = s124Service;
        this.app = app;
        this.producerCode = s124Service.producerCode();
    }

    /***************************************/
    /** Publishing                        **/
    /***************************************/

    /**
     * Publishes a dataset version: runs the typed dataset through the one exchange-set build
     * that signs it, and returns what that build packaged as a new, unsaved
     * {@link S124DatasetVersion} - the file bytes, the file name, the catalogue entry with its
     * signature, and the chain that made it. The caller attaches it to its dataset and persists
     * it; from then on every exchange set ships the record as it is. The file name and the entry
     * come from the factory rather than from a mirror of its naming and metadata rules, which is
     * the only way to get them right - and the entry is what a later fileless cancellation has to
     * reproduce verbatim (S-100 Part 17, clause 17-4.4.1).
     *
     * @param gml       the typed dataset
     * @param languages the two-letter codes of the languages the dataset is spelled in, primary
     *                  first - declared as the exchange set's locales (S-100 Part 17 catalogue
     *                  defaultLocale/otherLocale) and recorded on the version
     * @throws S124ExchangeSetException if the identity cannot be encoded
     * @throws S124ExchangeSetFactory.ExchangeSetException if the build fails, including when
     *                                  signing fails: nothing is published half-signed
     */
    public S124DatasetVersion publish(Dataset gml, List<String> languages) {
        S124ExchangeSetFactory.ExchangeSet built = factory(signingIdentity())
                .locales(toLocales(languages))
                .datasets(List.of(gml))
                .build()
                .toExchangeSet();
        S124ExchangeSetFactory.PublishedDataset published = built.datasets().get(0);
        DataSetIdentificationType identification = gml.getDatasetIdentificationInformation();

        S124DatasetVersion version = new S124DatasetVersion();
        version.setGml(new String(published.bytes(), StandardCharsets.UTF_8));
        version.setFileName(published.fileName());
        version.setDatasetId(gml.getId());
        version.setDatasetTitle(identification != null ? identification.getDatasetTitle() : null);
        version.setDiscoveryMetadata(S124ExchangeSetFactory.discoveryMetadataToXml(published.discoveryMetadata()));
        version.setSignature(signatureOf(published.discoveryMetadata()));
        version.setCertificatePems(built.signingCertificatePems());
        version.setLanguages(languages);
        return version;
    }

    /** The factory's locales for the given two-letter codes, in order (it spells them as ISO 639-2) */
    private static List<Locale> toLocales(List<String> languages) {
        return languages.stream().map(Locale::of).toList();
    }

    /**
     * The locales an exchange set shipping the given datasets declares: the languages of every
     * included version, in encounter order, so the first dataset's primary language is the
     * catalogue's default locale and the rest are its other locales. Empty for a set that
     * ships no dataset (cancellations only), which then takes the factory's default.
     */
    private static List<Locale> catalogueLocales(List<S124Dataset> datasets) {
        Set<String> languages = new LinkedHashSet<>();
        for (S124Dataset dataset : datasets) {
            languages.addAll(currentVersion(dataset).getLanguages());
        }
        return toLocales(List.copyOf(languages));
    }

    /** The per-dataset signature bytes the factory embedded in the entry it published */
    private static byte[] signatureOf(S100DatasetDiscoveryMetadata entry) {
        return entry.getDigitalSignatureValues().get(0).getS100SEDigitalSignature().getValue().getValue();
    }

    /***************************************/
    /** Serving                           **/
    /***************************************/

    /** Builds an exchange-set ZIP containing the single given dataset's current version */
    public byte[] buildExchangeSet(S124Dataset dataset) {
        return buildExchangeSet(List.of(dataset));
    }

    /** Builds an exchange-set ZIP containing the given datasets' current versions */
    public byte[] buildExchangeSet(List<S124Dataset> datasets) {
        return buildExchangeSet(datasets, findCancellationCandidates(datasets));
    }

    /**
     * Builds an exchange-set ZIP shipping the given datasets' current versions verbatim, plus
     * fileless cancellation entries for the given cancelled datasets (which must not overlap
     * the included datasets).
     */
    byte[] buildExchangeSet(List<S124Dataset> datasets, List<S124Dataset> cancelledDatasets) {
        return buildExchangeSetWith(datasets, new ArrayList<>(cancellationEntries(cancelledDatasets).values()));
    }

    /**
     * Builds one exchange-set ZIP per given dataset, in order, each exactly what
     * {@link #buildExchangeSet(S124Dataset)} returns for that dataset - the payloads of a Get or
     * GetSummary page. The fileless-cancellation candidates are queried, and their published
     * entries read, once for the whole page rather than once per dataset.
     *
     * @param datasets the datasets to package, one exchange set each
     * @return the exchange-set ZIPs, in the order of the datasets
     */
    public List<byte[]> buildExchangeSets(List<S124Dataset> datasets) {
        if (datasets.isEmpty()) {
            return List.of();
        }
        return buildExchangeSets(datasets, findCancelledWithinWindow());
    }

    /**
     * As {@link #buildExchangeSets(List)}, with the cancelled datasets given. Each dataset's set
     * carries the cancellation entries of every given cancelled dataset except its own: a
     * dataset is never shipped and cancelled fileless in the same set.
     */
    List<byte[]> buildExchangeSets(List<S124Dataset> datasets, List<S124Dataset> cancelledDatasets) {
        // Read once and shared by every build: the factory copies an entry before it adapts it
        Map<String, S124ExchangeSetFactory.Cancellation> cancellations = cancellationEntries(cancelledDatasets);
        List<byte[]> exchangeSets = new ArrayList<>(datasets.size());
        for (S124Dataset dataset : datasets) {
            List<S124ExchangeSetFactory.Cancellation> others = cancellations.entrySet().stream()
                    .filter(cancellation -> !cancellation.getKey().equals(dataset.getMrn()))
                    .map(Map.Entry::getValue)
                    .toList();
            exchangeSets.add(buildExchangeSetWith(List.of(dataset), others));
        }
        return exchangeSets;
    }

    /** Builds an exchange-set ZIP shipping the given datasets' current versions verbatim plus the given cancellations */
    private byte[] buildExchangeSetWith(List<S124Dataset> datasets, List<S124ExchangeSetFactory.Cancellation> cancellations) {
        List<S124ExchangeSetFactory.RetainedDataset> retained = new ArrayList<>(datasets.size());
        for (S124Dataset dataset : datasets) {
            retained.add(retainedDataset(dataset));
        }
        S124ExchangeSetFactory.Builder builder = factory(signingIdentity());
        List<Locale> locales = catalogueLocales(datasets);
        if (!locales.isEmpty()) {
            builder.locales(locales);
        }
        return builder
                .retainedDatasets(retained)
                .cancellations(cancellations)
                .build()
                .toBytes();
    }

    /** The dataset file bytes of the dataset's current version - the bare {@code S100_DataSet} payload */
    public byte[] datasetFile(S124Dataset dataset) {
        return currentVersion(dataset).gmlBytes();
    }

    /** The dataset's current version as the factory ships it again: bytes, entry and chain, as published */
    private static S124ExchangeSetFactory.RetainedDataset retainedDataset(S124Dataset dataset) {
        S124DatasetVersion version = currentVersion(dataset);
        try {
            return new S124ExchangeSetFactory.RetainedDataset(version.gmlBytes(),
                    S124ExchangeSetFactory.discoveryMetadataFromXml(version.getDiscoveryMetadata()),
                    version.certificatePems());
        } catch (RuntimeException e) {
            throw new S124ExchangeSetException(
                    "Published record of dataset " + dataset.getMrn() + " cannot be read", e);
        }
    }

    /** The dataset's current version, which every dataset row has from the transaction that created it */
    private static S124DatasetVersion currentVersion(S124Dataset dataset) {
        S124DatasetVersion version = dataset.getCurrentVersion();
        if (version == null) {
            throw new S124ExchangeSetException("Dataset " + dataset.getMrn() + " has no published version");
        }
        return version;
    }

    /**
     * The exchange-set factory configured for this node, shared by {@link #publish} and the
     * serve path, so what is published is built exactly as it is later catalogued.
     */
    private S124ExchangeSetFactory.Builder factory(SigningIdentity identity) {
        return S124ExchangeSetFactory.builder()
                .organization(s124Service.responsibleAgency())
                .producerCode(producerCode)
                // S-100 Part 17, Table 17-3: the producing agency's contactInfo is mandatory and
                // must document at least one way of reaching it. This Niord instance's public
                // base URI (the baseUri setting) is the online resource the datasets come from.
                .onlineResource(app.getBaseUri())
                .certificatePem(identity.certificatePem())
                // S-100 Part 15, clause 15-8.7: "The Data Server must always include the digital
                // certificate of its Domain Coordinator" - for an MCP identity, the issuing CA(s)
                // between the service certificate and the MCP root; the root is never included
                .intermediateCertificatePems(identity.intermediatePems())
                // ... and the root it all chains to is the scheme administrator the catalogue
                // names, not the IHO the factory defaults to: MCP certificates are not issued
                // under the IHO data protection scheme
                .schemeAdministrator(identity.schemeAdministrator())
                .signer(this::sign)
                .signatureAlgorithm(EXCHANGE_SET_SIGNATURE_ALGORITHM);
    }

    /***************************************/
    /** Signing identity                  **/
    /***************************************/

    /**
     * What this node signs exchange sets under: the MCP service certificate, the issuing
     * certificates between it and the MCP root (both as the minified PEM the factory takes),
     * and the scheme-administrator id naming that root.
     */
    record SigningIdentity(String certificatePem, List<String> intermediatePems, String schemeAdministrator) {
    }

    /**
     * Resolves the current signing identity from the PKI service's certification path: the
     * leaf, then every certificate up to but excluding the self-signed root - which the
     * consumer installs separately and an exchange set must not carry (S-100 Part 15, clause
     * 15-8.7) - and, as the scheme administrator, the issuer of the topmost carried
     * certificate, i.e. the MCP root CA, named by its common name.
     */
    SigningIdentity signingIdentity() {
        try {
            List<X509Certificate> path = pki.serviceCertificateChain();
            if (path == null || path.isEmpty()) {
                path = List.of(pki.serviceCertificate());
            }
            X509Certificate topmost = path.get(0);
            List<String> intermediates = new ArrayList<>();
            for (int i = 1; i < path.size(); i++) {
                X509Certificate certificate = path.get(i);
                if (McpSecurityService.isSelfSigned(certificate)) {
                    break;
                }
                intermediates.add(minifiedPem(certificate));
                topmost = certificate;
            }
            return new SigningIdentity(minifiedPem(path.get(0)), List.copyOf(intermediates),
                    schemeAdministratorId(topmost.getIssuerX500Principal()));
        } catch (CertificateEncodingException e) {
            throw new S124ExchangeSetException("Cannot PEM-encode the SECOM service certificate", e);
        }
    }

    /**
     * The scheme-administrator id for the catalogue: S-100 Part 15, clause 15-8.11.1, carries
     * the Scheme Administrator's identity in the id attribute ({@code IHO} for the IHO data
     * protection scheme), and every certificate's issuer reference resolves to it in the end.
     * For an MCP identity that is the MCP root CA, named by the common name of the issuer of
     * the topmost carried certificate - the whole distinguished name when it has no CN.
     */
    static String schemeAdministratorId(X500Principal issuer) {
        String name = issuer.getName(X500Principal.RFC2253);
        try {
            for (Rdn rdn : new LdapName(name).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType()) && rdn.getValue() != null
                        && !rdn.getValue().toString().isBlank()) {
                    return rdn.getValue().toString();
                }
            }
        } catch (InvalidNameException ignored) {
            // fall through to the full name
        }
        return name;
    }

    /***************************************/
    /** Fileless cancellations            **/
    /***************************************/

    /**
     * The datasets whose fileless cancellation entry every built exchange set must carry:
     * cancelled within the previous {@value #CANCELLATION_WINDOW_DAYS} days, minus the datasets
     * already included in the set as files (a dataset cannot be shipped and simultaneously
     * cancelled fileless).
     */
    private List<S124Dataset> findCancellationCandidates(List<S124Dataset> included) {
        Set<String> includedMrns = included.stream().map(S124Dataset::getMrn).collect(Collectors.toSet());
        return findCancelledWithinWindow().stream()
                .filter(dataset -> !includedMrns.contains(dataset.getMrn()))
                .toList();
    }

    /** The datasets cancelled within the previous {@value #CANCELLATION_WINDOW_DAYS} days */
    private List<S124Dataset> findCancelledWithinWindow() {
        Date cutoff = Date.from(Instant.now().minus(Duration.ofDays(CANCELLATION_WINDOW_DAYS)));
        return em.createNamedQuery("S124Dataset.findCancelledSince", S124Dataset.class)
                .setParameter("status", S124Dataset.Status.CANCELLED)
                .setParameter("cutoff", cutoff)
                .getResultList();
    }

    /**
     * Maps cancelled datasets to the factory's fileless {@link S124ExchangeSetFactory.Cancellation}
     * entries, keyed by dataset MRN in the given order: each reproduces the current version's
     * entry with its original signature (clause 17-4.4.1: the signature is the consumer's
     * matching key for the exact dataset it holds) and carries the chain that made it, so the
     * entry stays verifiable after a certificate rotation (S-100 Part 15, clause 15-8.7; the
     * factory carries a certificate it already has once). A dataset that cannot be mapped is
     * skipped rather than failing the whole exchange set.
     */
    private Map<String, S124ExchangeSetFactory.Cancellation> cancellationEntries(List<S124Dataset> cancelledDatasets) {
        Map<String, S124ExchangeSetFactory.Cancellation> entries = new LinkedHashMap<>();
        for (S124Dataset dataset : cancelledDatasets) {
            try {
                S124DatasetVersion version = currentVersion(dataset);
                entries.put(dataset.getMrn(), new S124ExchangeSetFactory.Cancellation(
                        S124ExchangeSetFactory.discoveryMetadataFromXml(version.getDiscoveryMetadata()),
                        cancellationIssueDate(dataset),
                        version.certificatePems()));
            } catch (Exception e) {
                log.error("Skipping fileless cancellation entry for dataset {}: {}",
                        dataset.getMrn(), e.getMessage());
            }
        }
        return entries;
    }

    /**
     * The issue date of the cancellation itself - the one field S-100 Part 17, clause 17-4.4.1,
     * excepts from verbatim reproduction of the original entry. It is when the dataset was
     * cancelled, which {@code S124Dataset.findCancelledSince} guarantees is set.
     */
    private static LocalDate cancellationIssueDate(S124Dataset dataset) {
        return dataset.getCancelled().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    /***************************************/
    /** Signing                           **/
    /***************************************/

    /**
     * A certificate as minified PEM (base64 without armour) — the format the exchange-catalogue
     * builder embeds and Base64-decodes, and the form {@link S124DatasetVersion} persists.
     */
    private static String minifiedPem(X509Certificate certificate) throws CertificateEncodingException {
        return SecomPemUtils.getMinifiedPemFromCert(certificate);
    }

    /** Signs exchange-set content (dataset files, CATALOG.XML) with the MCP service key */
    private byte[] sign(S100SEDigitalSignatureReference algorithm, byte[] payload) {
        String jcaAlgorithm = switch (algorithm) {
            case ECDSA_384_SHA_2 -> DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA.getValue();
            case ECDSA_384_SHA_3 -> DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA.getValue();
            default -> throw new IllegalArgumentException("Unsupported exchange-set signature algorithm " + algorithm);
        };
        try {
            return pki.sign(jcaAlgorithm, payload);
        } catch (GeneralSecurityException e) {
            throw new S124ExchangeSetException("Failed to sign S-124 exchange-set content", e);
        }
    }
}
