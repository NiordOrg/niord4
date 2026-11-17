/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.s124;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.model.message.MainType;
import org.niord.s124.mapper.S124Mapper;
import org.niord.s124.mapper.S124Mapper.GenerationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.util.S124DatasetInfo;

/**
 * Service for converting Niord navigational warnings to S-124 v2.0.0 GML.
 * <p>
 * Ported from the spring-niord {@code org.niord.message.s124.S124Service}; the generated
 * datasets are built with the NiordOrg S-124 JAXB bindings and validated against the
 * S-124 v2.0.0 schema before they are returned. Replaces the original STM-era Freemarker
 * template generator.
 * <p>
 * Deliberately NOT {@code @Transactional}: all callers (the REST endpoint, the SECOM
 * dataset store) already run in a transaction, and a transactional proxy here would mark
 * the caller's transaction rollback-only when the not-found/validation
 * IllegalArgumentExceptions cross it — turning handled 404s into 500s.
 * <p>
 * Exists only while {@code niord.s124.enabled} is on (the default; {@link S124SwitchCheck}).
 */
@Service
@ConditionalOnProperty(name = S124SwitchCheck.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class S124Service {

    /**
     * The language a dataset is spelled in when no preference applies: English, the language
     * the WWNWS requires NAVAREA and sub-area warnings to be issued in and the one S-124
     * consumers expect first, so a multilingual dataset always leads with it when it is there
     */
    static final String DEFAULT_LANGUAGE = "en";

    /** The property naming the deployment's IHO S-62 producer code */
    static final String PRODUCER_CODE_PROPERTY = "niord.s124.producer-code";

    private final MessageService messageService;
    private final MessageSeriesService messageSeriesService;
    private final DictionaryService dictionaryService;
    private final NiordApp app;

    /**
     * The deployment's IHO S-62 producer code, or null when none is configured. Every S-124
     * dataset carries it as {@code agencyResponsibleForProduction} (S-124 clause 4.3.3) and in
     * its {@code datasetFileIdentifier} (S-100 Part 17 clause 17-4.3), and the SECOM exchange
     * sets name their dataset files with it, so it is read once here and shared. It is a
     * deployment identity the base library ships no default for; a country deployable supplies
     * its own in its {@code application.yml}. A malformed value fails startup, a missing
     * one fails at the first generation instead (and startup when SECOM is on). With
     * {@code niord.s124.enabled} false this bean does not exist, so the code is neither read
     * nor validated.
     */
    private final String producerCode;

    S124Service(MessageService messageService, MessageSeriesService messageSeriesService,
                DictionaryService dictionaryService, NiordApp app,
                @Value("${" + PRODUCER_CODE_PROPERTY + ":}") String producerCode) {
        this.messageService = messageService;
        this.messageSeriesService = messageSeriesService;
        this.dictionaryService = dictionaryService;
        this.app = app;
        this.producerCode = StringUtils.isBlank(producerCode) ? null : validateProducerCode(producerCode);
    }

    /**
     * Validates a configured producer code, failing with an actionable error for a malformed
     * value: exactly four uppercase ASCII letters/digits, the four-character S-100 codes of the
     * IHO S-62 producer-code register. The value is used verbatim - a lowercase or otherwise
     * malformed code is rejected, never silently repaired.
     */
    static String validateProducerCode(String producerCode) {
        if (producerCode == null || !producerCode.matches("[A-Z0-9]{4}")) {
            throw new IllegalStateException(
                    PRODUCER_CODE_PROPERTY + " '" + producerCode + "' is not a valid IHO S-62 producer "
                    + "code: expected exactly four uppercase ASCII letters/digits, e.g. DK00");
        }
        return producerCode;
    }

    /**
     * The deployment's IHO S-62 producer code. Fails with an actionable error when none is
     * configured: no conformant S-124 dataset can be generated without it, and a SECOM-enabled
     * application needs it at startup.
     */
    public String producerCode() {
        if (producerCode == null) {
            throw new IllegalStateException(
                    PRODUCER_CODE_PROPERTY + " is required to generate S-124 datasets: set it to the "
                    + "deployment's IHO S-62 producer code - exactly four uppercase ASCII letters/digits, "
                    + "e.g. DK00");
        }
        return producerCode;
    }

    /**
     * The deployment's country, from the {@code country} setting, trimmed and upper case: the
     * nationality of the generated warnings, the prefix of their ids and the country token of
     * their MRN. Fails with an actionable error when the setting is blank or not a two-letter
     * code (ISO 3166-1 alpha-2, or XX in the base product), as {@link #producerCode()} does for
     * the producer code: any other value would make the gml:ids and the MRN invalid, and the XSD
     * validator's error would not name the setting.
     */
    public String country() {
        String country = StringUtils.trimToNull(app.getCountry());
        if (country == null) {
            throw new IllegalStateException("the country setting is required to generate S-124 datasets: "
                    + "set it to the deployment's country, e.g. in the country's settings file");
        }
        String code = country.toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{2}")) {
            throw new IllegalStateException("the country setting '" + country + "' is not a two-letter "
                    + "country code (ISO 3166-1 alpha-2), e.g. XX");
        }
        return code;
    }

    /**
     * Generates S-124 compliant GML for the message. Only publicly visible messages
     * (published, cancelled or expired — {@link org.niord.model.message.Status#isPublic()})
     * are served: this id-resolving entry backs the anonymous {@code /rest/S-124} endpoint,
     * and unpublished drafts must not leak through it. Non-public messages are reported as
     * not found, so the response does not confirm that the id exists.
     *
     * @param messageId the message UID or short ID
     * @param language the language
     * @return the generated GML
     */
    public String generateGML(String messageId, String language) throws Exception {
        return generate(messageId, language).xml();
    }

    /**
     * Generates the S-124 dataset for the message - the typed model together with its validated
     * GML - under the same visibility rule as {@link #generateGML(String, String)}. This is what
     * the SECOM dataset store consumes: it hands the typed model to the exchange-set factory,
     * which marshals and signs it, and keeps the factory's bytes as the published version.
     *
     * @param messageId the message UID or short ID
     * @param language the language
     * @return the generated dataset and its GML
     */
    public S124Mapper.Generated generate(String messageId, String language) throws Exception {
        Message message = messageService.resolveMessage(messageId);
        if (message == null || !message.getStatus().isPublic()) {
            throw new IllegalArgumentException("Message not found " + messageId);
        }
        return generate(message, language, GenerationType.FINAL);
    }

    /**
     * The ordered languages a dataset is generated in: the model languages, narrowed to the
     * given restriction when it names any of them (a restriction naming none restricts
     * nothing), with the preferred language first when it is among them, else
     * {@link #DEFAULT_LANGUAGE}, else the first. The generator then drops the ones the message
     * has no content in and spells the dataset in the first that remains
     * ({@link S124Mapper.Generated#languages()}).
     *
     * @param preferred   the language to spell the dataset in when the message has it, e.g. the
     *                    REST caller's choice; null or not a candidate for the default
     * @param restriction the series' language restriction (two-letter codes); null or empty
     *                    for none
     */
    public List<String> languagesFor(String preferred, List<String> restriction) {
        List<String> model = Arrays.asList(app.getLanguages());
        List<String> candidates = restriction == null || restriction.isEmpty()
                ? model
                : model.stream().filter(restriction::contains).toList();
        if (candidates.isEmpty()) {
            candidates = model;
        }
        String wanted = preferred == null ? null : preferred.trim().toLowerCase(Locale.ROOT);
        String primary = candidates.contains(wanted) ? wanted
                : candidates.contains(DEFAULT_LANGUAGE) ? DEFAULT_LANGUAGE
                : candidates.getFirst();
        return Stream.concat(Stream.of(primary), candidates.stream().filter(l -> !l.equals(primary))).toList();
    }

    /**
     * Generates S-124 compliant GML for the message in every model language it has content
     * in, spelled in the given language when it has that
     * @param message the message
     * @param language the preferred language
     * @param generationType FINAL for production output, PREVIEW for editor previews of
     *                       messages that may lack a short ID or warning number
     * @return the generated GML
     */
    public String generateGML(Message message, String language, GenerationType generationType) throws Exception {
        return generate(message, language, generationType).xml();
    }

    /**
     * Generates the S-124 dataset for the message: the typed model and its validated GML
     * @param message the message
     * @param language the language
     * @param generationType FINAL for production output, PREVIEW for editor previews of
     *                       messages that may lack a short ID or warning number
     * @return the generated dataset and its GML
     */
    public S124Mapper.Generated generate(Message message, String language, GenerationType generationType) throws Exception {
        return generate(message, languagesFor(language, List.of()), generationType);
    }

    /**
     * Generates the S-124 dataset for the message in the given languages: the typed model and
     * its validated GML, carrying the message texts in every listed language the message has
     * content in and spelled in the first of those. This is the SECOM dataset store's entry,
     * with the list from {@link #languagesFor} under the series' restriction.
     *
     * <p>For FINAL output the message's references resolve only to publicly visible messages
     * ({@link org.niord.model.message.Status#isPublic()}); a reference to any other message is
     * left out of the dataset, as an unresolvable one is. PREVIEW resolves every reference.
     *
     * @param message the message
     * @param languages the candidate languages (two-letter model-language codes), preferred first
     * @param generationType FINAL for production output, PREVIEW for editor previews
     * @return the generated dataset, its GML and the languages it is spelled in
     */
    public S124Mapper.Generated generate(Message message, List<String> languages, GenerationType generationType)
            throws Exception {
        if (message.getMainType() == MainType.NM) {
            throw new IllegalArgumentException("S-124 does not currently support Notices to Mariners");
        }
        if (generationType == GenerationType.FINAL && message.getNumber() == null) {
            throw new IllegalArgumentException("S-124 does not currently support un-numbered navigational warnings");
        }

        // S-124 clause 4.3.3: agencyResponsibleForProduction is the agency's S-62 producer code,
        // not its name - the name is the exchange-set organization, see responsibleAgency()
        String producerCode = producerCode();
        String country = country();
        String datasetId = StringUtils.isNotBlank(message.getShortId()) ? message.getShortId() : "PREVIEW";
        S124DatasetInfo di = new S124DatasetInfo(datasetId, producerCode);
        // The dataset language is set by the mapper: the first candidate the message has content in
        di.setTitle("Niord S-124 Dataset");
        di.setAbstractText(buildAbstractText(List.of(message)));
        if (generationType == GenerationType.PREVIEW) {
            di.setTitle("PREVIEW - Niord S-124 Dataset");
        }

        // FINAL output is served anonymously and pushed to SECOM subscribers, so a reference only
        // resolves to a message the public may see; a non-public one is left out, exactly like an
        // unresolvable reference, and contributes no References feature, affects link or extent.
        // The editor-only PREVIEW resolves every reference.
        // The warning's dates are days of its series' domain zone, as everywhere else in Niord (a draft without
        // a series has the JVM's zone, seriesZone's own fallback).
        return S124Mapper.toValidated(
                message,
                languages,
                producerCode,
                country,
                di,
                generationType,
                messageSeriesService.seriesZone(message.getMessageSeries()),
                id -> Optional.ofNullable(messageService.resolveMessage(id))
                        .filter(m -> generationType == GenerationType.PREVIEW
                                || (m.getStatus() != null && m.getStatus().isPublic())),
                Message::getTags);
    }

    private static String buildAbstractText(List<Message> messages) {
        return "Autogenerated S-124 Dataset for"
                + Optional.ofNullable(messages).orElse(Collections.emptyList()).stream()
                        .map(Message::getUid)
                        .map(uid -> String.format(" %s", uid))
                        .collect(Collectors.joining());
    }

    /**
     * The name of the agency responsible for production: the message dictionary's English
     * {@code msg.responsible.agency} when it is not blank, else the {@code organisation}
     * setting. Fails with an actionable error when both are blank: the producing agency is
     * mandatory in an S-100 exchange-set catalogue (Part 17). Used as the producing organization
     * of those catalogues; the GML itself identifies the agency by its
     * {@link #producerCode() producer code}, as S-124 clause 4.3.3 requires.
     */
    public String responsibleAgency() {
        String agency = dictionaryService.value("message", "en", "msg.responsible.agency");
        if (StringUtils.isNotBlank(agency)) {
            return agency;
        }
        String organisation = StringUtils.trimToNull(app.getOrganisation());
        if (organisation == null) {
            throw new IllegalStateException("the organisation setting is required to build S-100 exchange sets: "
                    + "set it in the country's settings file or on the Settings page");
        }
        return organisation;
    }
}
