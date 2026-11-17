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
package org.niord.secom.model;

import org.niord.core.db.JpaStringListAttributeConverter;
import org.niord.core.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * One published version of an S-124 dataset: the immutable record of exactly what was signed
 * and shipped when the warning was published, updated or cancelled.
 * <p>
 * A version is written once, by {@code S124ExchangeSetService.publish}, from the one exchange-set
 * build that signs it, and never modified afterwards - every later exchange set that serves the
 * dataset ships this record verbatim, and a fileless cancellation of it (S-100 Part 17, clause
 * 17-4.4.1) reproduces this record's catalogue entry. That is what makes the signature a
 * consumer matches the cancellation by stable: the bytes, the entry that published them and the
 * signature in it are one thing by construction, so there is nothing to keep consistent later and
 * nothing to parse when serving. A certificate rotation changes nothing here: the chain that made
 * the signature is part of the record and travels with every exchange set that ships it (S-100
 * Part 15, clause 15-8.7), while the catalogue around it is signed by whatever certificate is then
 * current.
 * <p>
 * The {@link S124Dataset} row points at its current version; earlier versions are kept as the
 * history of what was served and pushed when.
 */
@Entity
@Table(indexes = @Index(name = "s124_dataset_version_dataset", columnList = "dataset_id"))
@NamedQueries({
        @NamedQuery(name = "S124DatasetVersion.findByDataset",
                query = "SELECT v FROM S124DatasetVersion v where v.dataset = :dataset order by v.id asc")
})
@SuppressWarnings("unused")
public class S124DatasetVersion extends BaseEntity<Integer> {

    /** Separates the minified PEMs of {@link #signingCertificates} */
    static final String CERTIFICATE_SEPARATOR = "\n";

    /** The dataset this is a version of */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id", nullable = false)
    S124Dataset dataset;

    /**
     * The dataset file exactly as the exchange-set factory packaged and signed it (UTF-8): the
     * bytes served in every exchange set and pushed to subscribers as the bare {@code S100_DataSet}
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    String gml;

    /** The bare S-100 Part 17, clause 17-4.3, dataset file name ({@code 124<producer><unique code>.GML}) */
    @Column(nullable = false, length = 128)
    String fileName;

    /**
     * The S-100 dataset identification the SECOM GetSummary fields are defined against, read
     * off the typed dataset when it was published so serving never parses the GML: the dataset's
     * {@code gml:id} and its header's {@code datasetTitle}. The file name above is the header's
     * {@code datasetFileIdentifier}.
     */
    @Column(length = 256)
    String datasetId;

    @Column(length = 256)
    String datasetTitle;

    /**
     * The {@code S100_DatasetDiscoveryMetadata} entry the exchange-set factory published this
     * version under, as XML ({@code S124ExchangeSetFactory.discoveryMetadataToXml}). It carries the
     * signature, and it is what every exchange set shipping this version reproduces and what a
     * later fileless cancellation reproduces verbatim apart from the issue date - a producer's own
     * columns cannot reconstruct the producing-agency block, issue time and comment the clause
     * requires unchanged, so the entry outlives the catalogue it shipped in here.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    String discoveryMetadata;

    /**
     * The S-100 Part 15 signature over {@link #gml}, as embedded in {@link #discoveryMetadata}:
     * the consumer's matching key for this version, kept on its own column so it can be read
     * without parsing the entry
     */
    @Column(nullable = false)
    byte[] signature;

    /**
     * The chain that made the signature, signing certificate first, then the MCP issuing
     * certificates up to but excluding the root, as newline-joined minified PEMs - what the
     * factory reported publishing under. A later exchange set hands it back so the signature stays
     * verifiable after the certificate has been rotated out (S-100 Part 15, clause 15-8.7).
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    String signingCertificates;

    /**
     * The languages the dataset file is spelled in, as two-letter model-language codes, primary
     * (= the header's datasetLanguage) first - what the generator emitted, never empty, kept so
     * an exchange set can declare its locales without parsing the GML.
     */
    @Convert(converter = JpaStringListAttributeConverter.class)
    @Column(length = 64, nullable = false)
    List<String> languages;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false)
    Date created;

    @PrePersist
    protected void onCreate() {
        created = new Date();
    }

    /** {@return the dataset file bytes as packaged and signed} */
    public byte[] gmlBytes() {
        return gml.getBytes(StandardCharsets.UTF_8);
    }

    /** {@return the signing chain as the exchange-set factory takes it, signing certificate first} */
    public List<String> certificatePems() {
        return Arrays.asList(signingCertificates.split(CERTIFICATE_SEPARATOR));
    }

    /** Stores the signing chain as the exchange-set factory reported it, signing certificate first */
    public void setCertificatePems(List<String> certificatePems) {
        this.signingCertificates = String.join(CERTIFICATE_SEPARATOR, certificatePems);
    }


    /*************************/
    /** Getters and Setters **/
    /*************************/

    public S124Dataset getDataset() {
        return dataset;
    }

    public void setDataset(S124Dataset dataset) {
        this.dataset = dataset;
    }

    public String getGml() {
        return gml;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public void setLanguages(List<String> languages) {
        this.languages = languages;
    }

    public void setGml(String gml) {
        this.gml = gml;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public String getDatasetTitle() {
        return datasetTitle;
    }

    public void setDatasetTitle(String datasetTitle) {
        this.datasetTitle = datasetTitle;
    }

    public String getDiscoveryMetadata() {
        return discoveryMetadata;
    }

    public void setDiscoveryMetadata(String discoveryMetadata) {
        this.discoveryMetadata = discoveryMetadata;
    }

    public byte[] getSignature() {
        return signature;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    public String getSigningCertificates() {
        return signingCertificates;
    }

    public void setSigningCertificates(String signingCertificates) {
        this.signingCertificates = signingCertificates;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
