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
package org.niord.s124.mapper;

import static org.niord.model.message.MainType.NW;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.area.Area;
import org.niord.core.area.AreaDesc;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryDesc;
import org.niord.core.geojson.Feature;
import org.niord.core.message.DateInterval;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageTag;
import org.niord.core.message.Reference;
import org.niord.core.util.TextUtils;
import org.niord.model.ILocalizedDesc;
import org.niord.model.message.Type;
import org.niord.s124.S124StandardMappings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.CurveProperty;
import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.DataSetIdentificationType;
import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.MDTopicCategoryCode;
import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.PointProperty;
import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.S100SpatialAttributeType;
import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.SurfaceProperty;
import dk.dma.niord.s100.xmlbindings.s100.gml.base._5_0.impl.DataSetIdentificationTypeImpl;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.BoundingShapeType;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.EnvelopeType;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.Pos;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.ReferenceType;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.impl.BoundingShapeTypeImpl;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.impl.EnvelopeTypeImpl;
import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.impl.PosImpl;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.Dataset;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.DateEndType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.DateStartType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.FixedDateRangeType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.GeneralAreaType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.InformationType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.LocalityType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.LocationNameType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.MessageSeriesIdentifierType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnPart;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnPreamble;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnTitleType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnTypeGeneralLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnTypeGeneralType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.ObjectFactory;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.References;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.RestrictionLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.RestrictionType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.WarningInformationType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.WarningTypeLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.WarningTypeType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.impl.DatasetImpl;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.util.GeometryS124Converter;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.util.S124DatasetInfo;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.util.S124Utils;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.util.S124XsdValidator;

/**
 * Maps a Niord {@link Message} to a typed S-124 v2.0.0 {@link Dataset}.
 * <p>
 * Ported from the spring-niord {@code org.niord.message.s124.mapper.S124Mapper}, re-bound to
 * this tree's entity model: message part geometries live in {@link org.niord.core.geojson.FeatureCollection}
 * feature rows (already JTS), and message dates are {@link Date}.
 */
public class S124Mapper {

    private static final Logger log = LoggerFactory.getLogger(S124Mapper.class);

    private static final ObjectFactory S124_FACTORY = new ObjectFactory();
    private static final dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.ObjectFactory PROFILE_FACTORY =
            new dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.ObjectFactory();

    private static final String PREVIEW_SHORT_ID = "PREVIEW";

    private final String producerCode;
    /** The deployment's country (the country setting, upper case): the nationality, the id prefix and the MRN token */
    private final String country;
    /** The candidate languages, preferred first; see {@link #languagesWithContent} */
    private final List<String> languages;
    /** The candidates the message has content in, primary first - what the dataset is spelled in */
    private List<String> emitted = List.of();
    /** The primary language: the dataset language, and the one single-valued names are resolved in */
    private String lang;
    private final S124DatasetInfo datasetInfo;
    private final GenerationType generationType;
    /**
     * The zone of the message series' domain: the warning's dates (the event dates, and the year of a message that
     * stores none) are the days of that zone, as everywhere else in Niord; never the JVM's zone
     */
    private final ZoneId zone;
    private final Function<String, Optional<Message>> referenceLookup;
    private final Function<Message, List<MessageTag>> tagLookup;

    private Map<Reference, Message> resolvedReferences = Map.of();
    private RestrictionType cachedRestriction;
    private int nextGeomId = 1;
    private final Set<String> usedPartIds = new HashSet<>();

    private S124Mapper(List<String> languages,
            String producerCode,
            String country,
            S124DatasetInfo datasetInfo,
            GenerationType generationType,
            ZoneId zone,
            Function<String, Optional<Message>> referenceLookup,
            Function<Message, List<MessageTag>> tagLookup) {
        if (languages == null || languages.isEmpty()) {
            throw new IllegalArgumentException("At least one language is required");
        }
        if (StringUtils.isBlank(country)) {
            throw new IllegalArgumentException("A country is required");
        }
        if (zone == null) {
            throw new IllegalArgumentException("A zone is required");
        }
        this.languages = List.copyOf(languages);
        this.producerCode = producerCode;
        this.country = country;
        this.datasetInfo = datasetInfo;
        this.generationType = generationType;
        this.zone = zone;
        this.referenceLookup = referenceLookup;
        this.tagLookup = tagLookup;
    }

    /**
     * A generated dataset: the typed S-124 model, the schema-validated XML it marshals to, and
     * the languages it is spelled in - the two-letter model-language codes the message had
     * content in, primary (= dataset language) first. The SECOM dataset store consumes the
     * typed model (the exchange-set factory marshals and signs it itself), declares the
     * languages as the exchange set's locales, and compares the XML with what it last
     * published to tell an unchanged message from a changed one; the REST endpoint serves the
     * XML.
     */
    public record Generated(Dataset dataset, String xml, List<String> languages) {
    }

    /**
     * Maps the message to an S-124 dataset, marshals it to XML, and validates the result
     * against the S-124 v2.0.0 schema.
     *
     * @param languages the candidate languages (two-letter model-language codes), preferred
     *                  first: the dataset carries the message texts in every one of them the
     *                  message has content in, and is spelled in the first of those
     * @param country   the deployment's country, upper case (the country setting, see
     *                  {@code S124Service.country()}): the warnings' nationality, the prefix of
     *                  their ids and the country token of their MRN
     * @param zone      the zone of the message series' domain ({@code MessageSeriesService.seriesZone}): the
     *                  event dates are its days, and a message without a stored year takes the year of its
     *                  publish date there
     */
    public static Generated toValidated(Message message,
            List<String> languages,
            String producerCode,
            String country,
            S124DatasetInfo datasetInfo,
            GenerationType generationType,
            ZoneId zone,
            Function<String, Optional<Message>> referenceLookup,
            Function<Message, List<MessageTag>> tagLookup) throws Exception {
        S124Mapper mapper = new S124Mapper(languages, producerCode, country, datasetInfo, generationType,
                zone, referenceLookup, tagLookup);
        Dataset dataset = mapper.map(message);
        String xml = S124Utils.marshalS124(dataset);
        S124XsdValidator.validate(xml);
        return new Generated(dataset, xml, mapper.emitted);
    }

    /**
     * Maps the message in the JVM's zone.
     *
     * @deprecated the warning's dates belong to the zone of its series' domain, not the JVM's: use
     *             {@link #toValidated(Message, List, String, String, S124DatasetInfo, GenerationType, ZoneId,
     *             Function, Function)} with {@code MessageSeriesService.seriesZone}, as {@code S124Service} does
     */
    @Deprecated
    public static Generated toValidated(Message message,
            List<String> languages,
            String producerCode,
            String country,
            S124DatasetInfo datasetInfo,
            GenerationType generationType,
            Function<String, Optional<Message>> referenceLookup,
            Function<Message, List<MessageTag>> tagLookup) throws Exception {
        return toValidated(message, languages, producerCode, country, datasetInfo, generationType,
                ZoneId.systemDefault(), referenceLookup, tagLookup);
    }

    private Dataset map(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        if (message.getMainType() != NW) {
            throw new IllegalArgumentException("Only navigational warning messages (NW) are supported");
        }

        resolvedReferences = resolveReferences(message);
        cachedRestriction = computeRestriction(message);
        emitted = languagesWithContent(message);
        lang = emitted.getFirst();
        // The dataset language is the primary language, decided here rather than by the caller:
        // it depends on which candidate languages the message actually has content in
        datasetInfo.setLanguage(toIso639_2Language(lang));

        Dataset ds = new DatasetImpl();
        ds.setId(datasetInfo.getDatasetId());
        ds.setBoundedBy(datasetBoundingShape(message));

        DataSetIdentificationType identification = new DataSetIdentificationTypeImpl();
        identification.setEncodingSpecification(datasetInfo.getEncodingSpecification());
        identification.setEncodingSpecificationEdition(datasetInfo.getEncodingSpecificationEdition());
        identification.setProductIdentifier(datasetInfo.getProductionIdentifier());
        identification.setProductEdition(datasetInfo.getProductionEdition());
        // applicationProfile and datasetPurpose are one fact written twice - S-100 Part 10b Table
        // 10b-4 defines only "1" (base datasets) and "2" (update datasets) and pairs each with a
        // purpose - so both are read from the S124DatasetInfo, which keeps the pairing in one
        // place. Niord only ever emits base datasets.
        identification.setApplicationProfile(datasetInfo.getApplicationProfile());
        identification.setDatasetFileIdentifier(datasetInfo.getFileIdentifier());
        identification.setDatasetTitle(datasetInfo.getTitle());
        // The date this version of the dataset came to be. Derived from the message rather
        // than the clock: the SECOM dataset store treats regenerated-but-byte-identical GML
        // as unchanged (no re-push), so generation must be deterministic for unchanged input.
        identification.setDatasetReferenceDate(referenceDate(message));
        identification.setDatasetLanguage(datasetInfo.getLanguage());
        identification.setDatasetAbstract(datasetInfo.getAbstractText());
        identification.getDatasetTopicCategories().add(MDTopicCategoryCode.OCEANS);
        identification.setDatasetPurpose(datasetInfo.getPurpose());
        identification.setUpdateNumber(BigInteger.ZERO);
        ds.setDatasetIdentificationInformation(identification);

        Dataset.Members members = S124_FACTORY.createDatasetMembers();
        ds.setMembers(members);

        NavwarnPreamble preamble = toDataModelNavwarnPreamble(message);
        members.getNavwarnPartsAndNavwarnAreaAffectedsAndTextPlacements().add(preamble);

        List<NavwarnPart> parts = new ArrayList<>();
        for (MessagePart p : message.getParts()) {
            parts.add(toDataModelNavwarnPart(message, p));
        }
        for (NavwarnPart p : parts) {
            members.getNavwarnPartsAndNavwarnAreaAffectedsAndTextPlacements().add(p);
            ReferenceType rt = PROFILE_FACTORY.createReferenceType();
            rt.setHref("#" + preamble.getId());
            rt.setRole("header");
            p.setHeader(rt);
        }

        List<References> references = toDataModelReferences(message);
        for (References ref : references) {
            members.getNavwarnPartsAndNavwarnAreaAffectedsAndTextPlacements().add(ref);
        }
        for (References ref : references) {
            ReferenceType rt = PROFILE_FACTORY.createReferenceType();
            rt.setHref("#" + ref.getId());
            rt.setRole("reference");
            preamble.getTheReferences().add(rt);
        }

        return ds;
    }

    private NavwarnPart toDataModelNavwarnPart(Message message, MessagePart messagePart) {
        NavwarnPart part = S124_FACTORY.createNavwarnPart();
        // The xml:id attribute is an NCName, so the MRN form (which contains colons) cannot be used.
        // A preview of an unsaved message has null part ids, so every part would otherwise render as
        // "P.<msg>.null"; xml:id is a schema ID, so disambiguate collisions the same way References do.
        part.setId(uniqueXmlId(usedPartIds, "P." + toMessageId(message) + "." + messagePart.getId()));

        part.setBoundedBy(generateBoundingShape(messagePart));

        for (Map.Entry<Reference, Message> entry : resolvedReferences.entrySet()) {
            Reference ref = entry.getKey();
            ReferenceType rt = PROFILE_FACTORY.createReferenceType();
            // affects is an inter-warning xlink (gml:ReferenceType). The referenced warning lives in
            // its own dataset, so we use its MRN as the href — a local "#id" anchor would dangle.
            rt.setHref(toMrn(entry.getValue()));
            rt.setRole(ref.getType() != null ? ref.getType().name() : "reference");
            part.getAffects().add(rt);
        }

        if (messagePart.getEventDates() != null) {
            for (DateInterval di : messagePart.getEventDates()) {
                part.getFixedDateRanges().add(toComplexFixedDateRange(di));
            }
        }

        // One language-tagged text per emitted language the part has details in (S-124 allows
        // any number of information entries); a part without details in any of them has none
        WarningInformationType wit = S124_FACTORY.createWarningInformationType();
        emitLocalized(messagePart.getDescs(), MessagePartDesc::getDetails, (language, details) -> {
            InformationType it = S124_FACTORY.createInformationType();
            it.setLanguage(language);
            it.setText(TextUtils.html2txt(details, true));
            wit.getInformations().add(it);
        });
        part.setWarningInformation(wit);

        for (Geometry geometry : partGeometries(messagePart)) {
            List<S100SpatialAttributeType> properties =
                    GeometryS124Converter.geometryToS124PointCurveSurfaceGeometry(geometry);
            for (S100SpatialAttributeType s : properties) {
                NavwarnPart.Geometry geo = S124_FACTORY.createNavwarnPartGeometry();
                String geomId = nextGeomId(part.getId());
                switch (s) {
                    case SurfaceProperty sp -> {
                        if (sp.getSurface() != null) {
                            sp.getSurface().setId(geomId);
                        }
                        geo.setSurfaceProperty(sp);
                    }
                    case PointProperty pp -> {
                        if (pp.getPoint() != null) {
                            pp.getPoint().setId(geomId);
                        }
                        geo.setPointProperty(pp);
                    }
                    case CurveProperty cp -> {
                        if (cp.getCurve() != null) {
                            cp.getCurve().setId(geomId);
                        }
                        geo.setCurveProperty(cp);
                    }
                    default -> throw new UnsupportedOperationException("Cannot deal with " + s);
                }
                part.getGeometries().add(geo);
            }
        }

        part.setRestriction(cachedRestriction);
        return part;
    }

    private NavwarnPreamble toDataModelNavwarnPreamble(Message msg) {
        NavwarnPreamble p = S124_FACTORY.createNavwarnPreamble();
        p.setId(toMessageId(msg));

        // No affectedChartPublications: S-124 2.0's chartAffected requires an editionDate after the chartNumber
        // (chartPlanNumber and lastNoticeDate are optional), and affectedChartPublications requires a language; a
        // Niord chart has no edition date and the mapper set no language, so none is written (owner, 2026-10-23,
        // kept 2026-10-24; spec gap analysis §5)

        if (msg.getAreas() != null) {
            for (Area area : msg.getAreas()) {
                GeneralAreaType gat = toGeneralAreaType(area);
                if (gat != null) {
                    p.getGeneralAreas().add(gat);
                }
            }
        }
        // generalArea is [1..*] in the schema; synthesise one from vicinity/shortId if no area is set.
        if (p.getGeneralAreas().isEmpty()) {
            p.getGeneralAreas().add(fallbackGeneralArea(msg));
        }

        // The vicinity in every emitted language it is given in, primary first
        LocalityType locality = S124_FACTORY.createLocalityType();
        emitLocalized(msg.getDescs(), MessageDesc::getVicinity,
                (language, vicinity) -> locality.getLocationNames().add(locationName(language, vicinity)));
        if (!locality.getLocationNames().isEmpty()) {
            p.getLocalities().add(locality);
        }

        p.setMessageSeriesIdentifier(toMessageSeriesIdentifierType(msg));

        emitLocalized(msg.getDescs(), MessageDesc::getTitle, (language, title) -> {
            NavwarnTitleType titleType = S124_FACTORY.createNavwarnTitleType();
            titleType.setLanguage(language);
            titleType.setText(title);
            p.getNavwarnTitles().add(titleType);
        });

        if (msg.getPublishDateTo() != null) {
            p.setCancellationDate(toOffsetDateTime(msg.getPublishDateTo()));
        }

        p.setIntService(msg.getType() != Type.LOCAL_WARNING);

        p.setNavwarnTypeGeneral(toNavwarnTypeGeneral(msg));

        // publicationTime is minOccurs=1 in the schema. A published message always has a
        // publish-from date; a PREVIEW of a not-yet-published draft has none, so - like the
        // warning number above - fall back to the draft's own timestamp for the preview only.
        if (msg.getPublishDateFrom() != null) {
            p.setPublicationTime(toOffsetDateTime(msg.getPublishDateFrom()));
        } else if (generationType == GenerationType.PREVIEW) {
            p.setPublicationTime(previewPublicationTime(msg));
        }

        return p;
    }

    private List<References> toDataModelReferences(Message message) {
        List<References> result = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (Map.Entry<Reference, Message> entry : resolvedReferences.entrySet()) {
            Reference r = entry.getKey();
            Message referenced = entry.getValue();
            References ref = S124_FACTORY.createReferences();
            // S-124 References features need a unique xml:id; "R." prefix avoids collision with the
            // referenced message's own preamble id (which uses the same toMessageId form).
            // Two references to the same message (e.g. a REFERENCE plus a CANCELLATION) must not
            // share an xml:id - it is a schema ID, so a duplicate fails the XSD validation.
            ref.setId(uniqueXmlId(usedIds, "R." + toMessageId(message) + "." + toMessageId(referenced)));
            ref.setNoMessageOnHand(S124StandardMappings.noMessageOnHand(r.getType()));
            ref.setReferenceCategory(S124StandardMappings.buildReferenceCategory(
                    S124StandardMappings.toReferenceCategory(r.getType())));
            // theWarning is a cross-document gml:ReferenceType; the referenced warning lives in its
            // own dataset, so we point at its MRN rather than a local "#id" anchor.
            ref.setTheWarning(S124StandardMappings.buildGmlReference(toMrn(referenced), "warning"));
            ref.getMessageSeriesIdentifiers().add(toMessageSeriesIdentifierType(referenced));
            result.add(ref);
        }
        return result;
    }

    private Map<Reference, Message> resolveReferences(Message message) {
        if (message.getReferences() == null) {
            return Map.of();
        }
        // LinkedHashMap: the References members and per-part affects links must render in the
        // message's own reference order. Reference entities hash by identity, so a HashMap
        // would reorder them per JVM run - and the SECOM dataset store diffs regenerated GML
        // bytes, so unstable ordering would re-push unchanged messages.
        Map<Reference, Message> resolved = new LinkedHashMap<>();
        for (Reference r : message.getReferences()) {
            if (r.getMessageId() == null) {
                continue;
            }
            Message referenced = referenceLookup.apply(r.getMessageId()).orElse(null);
            if (referenced != null && referenced.getMainType() == NW) {
                resolved.put(r, referenced);
            }
        }
        return resolved;
    }

    private String toMessageId(Message msg) {
        StringBuilder sb = new StringBuilder();
        sb.append(country).append(".");
        if (StringUtils.isNotBlank(msg.getShortId())) {
            sb.append(msg.getShortId());
        } else if (msg.getId() != null) {
            sb.append(msg.getId());
        } else {
            sb.append(PREVIEW_SHORT_ID);
        }
        return sb.toString();
    }

    private MessageSeriesIdentifierType toMessageSeriesIdentifierType(Message message) {
        MessageSeriesIdentifierType identifier = S124_FACTORY.createMessageSeriesIdentifierType();

        // year is minOccurs=1 in the schema: the message's stored year, the one its short id carries (the year
        // of its publish date in the zone of its series' domain), and for a message without one the year of
        // its publish date in the series' zone. A PREVIEW of a not-yet-published draft has no publish date,
        // so fall back to the same timestamp used for the preamble publicationTime (the draft's own
        // edit/creation time, else now), read in the same zone. A referenced warning takes its own stored
        // year the same way; only its fallback reads the zone of this message's series.
        if (message.getYear() != null) {
            identifier.setYear(message.getYear());
        } else if (message.getPublishDateFrom() != null) {
            identifier.setYear(LocalDate.ofInstant(message.getPublishDateFrom().toInstant(), zone).getYear());
        } else if (generationType == GenerationType.PREVIEW) {
            identifier.setYear(previewPublicationTime(message).atZoneSameInstant(zone).getYear());
        }
        identifier.setNationality(country);
        identifier.setAgencyResponsibleForProduction(producerCode);

        identifier.setNameOfSeries(nameOfSeries(message));
        identifier.setInteroperabilityIdentifier(toMrn(message));

        if (message.getNumber() != null) {
            identifier.setWarningNumber(message.getNumber());
        } else if (generationType == GenerationType.PREVIEW) {
            identifier.setWarningNumber(0);
        }
        // warningType is minOccurs=1 and carries a required label enum, so it cannot be omitted or
        // left blank. A published NW always has a type; a PREVIEW draft may not have picked a scope
        // yet, so default the preview to a coastal warning (matching intService's non-local default).
        if (message.getType() != null) {
            identifier.setWarningType(toComplexTypeWarningTypeType(message.getType()));
        } else if (generationType == GenerationType.PREVIEW) {
            identifier.setWarningType(toComplexTypeWarningTypeType(Type.COASTAL_WARNING));
        }
        return identifier;
    }

    /**
     * Returns the message-series name, falling back to a type-based default when the message has
     * no series. The S-124 schema requires {@code nameOfSeries}.
     */
    private static String nameOfSeries(Message msg) {
        if (msg.getMessageSeries() != null && StringUtils.isNotBlank(msg.getMessageSeries().getSeriesId())) {
            return msg.getMessageSeries().getSeriesId();
        }
        if (msg.getType() == null) {
            return "Navigational Warning";
        }
        return switch (msg.getType()) {
            case LOCAL_WARNING -> "Local";
            case COASTAL_WARNING -> "Coastal";
            case SUBAREA_WARNING -> "Subarea";
            case NAVAREA_WARNING -> "Navarea";
            default -> "Navigational Warning";
        };
    }

    /**
     * An event date interval as days of the series' zone: an all-day interval is 00:00 to 23:59:59 in the
     * editor's zone ({@link DateInterval}'s all-day flag), so read in any other zone it would start or end a day off
     */
    private FixedDateRangeType toComplexFixedDateRange(DateInterval interval) {
        FixedDateRangeType result = S124_FACTORY.createFixedDateRangeType();
        Date fromDate = interval.getFromDate();
        Date toDate = interval.getToDate();

        if (fromDate != null) {
            DateStartType dst = S124_FACTORY.createDateStartType();
            dst.setDate(LocalDate.ofInstant(fromDate.toInstant(), zone));
            result.setDateStart(dst);
        }
        if (toDate != null) {
            DateEndType det = S124_FACTORY.createDateEndType();
            det.setDate(LocalDate.ofInstant(toDate.toInstant(), zone));
            result.setDateEnd(det);
        }
        return result;
    }

    private WarningTypeType toComplexTypeWarningTypeType(Type type) {
        WarningTypeType result = S124_FACTORY.createWarningTypeType();
        switch (type) {
            case LOCAL_WARNING -> {
                result.setValue(WarningTypeLabel.LOCAL_NAVIGATIONAL_WARNING);
                result.setCode(BigInteger.valueOf(1));
            }
            case COASTAL_WARNING -> {
                result.setValue(WarningTypeLabel.COASTAL_NAVIGATIONAL_WARNING);
                result.setCode(BigInteger.valueOf(2));
            }
            case SUBAREA_WARNING -> {
                result.setValue(WarningTypeLabel.SUB_AREA_NAVIGATIONAL_WARNING);
                result.setCode(BigInteger.valueOf(3));
            }
            case NAVAREA_WARNING -> {
                result.setValue(WarningTypeLabel.NAVAREA_NAVIGATIONAL_WARNING);
                result.setCode(BigInteger.valueOf(4));
            }
            default -> log.warn("Messages of type {} not mapped.", type.name());
        }
        return result;
    }

    private NavwarnTypeGeneralType toNavwarnTypeGeneral(Message message) {
        return navwarnTypeGeneral(toNavwarnTypeGeneralLabel(message));
    }

    private NavwarnTypeGeneralLabel toNavwarnTypeGeneralLabel(Message message) {
        if (message.getCategories() == null || message.getCategories().isEmpty()) {
            return NavwarnTypeGeneralLabel.OTHER_HAZARDS;
        }
        Category primary = message.getCategories().getFirst();
        String categoryName = getCategoryName(primary);
        if (categoryName == null) {
            return NavwarnTypeGeneralLabel.OTHER_HAZARDS;
        }
        return switch (categoryName.toLowerCase()) {
            case "light", "light buoy", "buoy", "beacon" -> NavwarnTypeGeneralLabel.AIDS_TO_NAVIGATION_CHANGES;
            case "drifting object" -> NavwarnTypeGeneralLabel.DRIFTING_HAZARDS;
            case "radio navigation" -> NavwarnTypeGeneralLabel.COMMUNICATION_OR_BROADCAST_SERVICE_CHANGE;
            case "firing exercises" -> NavwarnTypeGeneralLabel.SPECIAL_OPERATIONS;
            case "obstruction" -> {
                String templateType = getObstructionTemplateType(message);
                if ("wreck".equals(templateType)) {
                    yield NavwarnTypeGeneralLabel.DANGEROUS_NATURAL_PHENOMENA;
                } else if ("uncharted obstruction".equals(templateType)
                        || "reduced depth".equals(templateType)) {
                    yield NavwarnTypeGeneralLabel.NEWLY_DISCOVERED_DANGERS;
                } else {
                    yield NavwarnTypeGeneralLabel.SPECIAL_OPERATIONS;
                }
            }
            // navwarnTypeGeneral is required by the schema; fall back to Other Hazards
            default -> NavwarnTypeGeneralLabel.OTHER_HAZARDS;
        };
    }

    /**
     * Builds a {@code navwarnTypeGeneral} element with the label and its codelist code taken
     * from the same table ({@link S124StandardMappings#NAVWARN_TYPE_GENERAL_CODES}) - the XSD
     * declares labels and codes as separate enumerations, so a hand-paired code can be
     * schema-valid yet semantically wrong.
     */
    private static NavwarnTypeGeneralType navwarnTypeGeneral(NavwarnTypeGeneralLabel label) {
        NavwarnTypeGeneralType ngt = S124_FACTORY.createNavwarnTypeGeneralType();
        ngt.setValue(label);
        ngt.setCode(BigInteger.valueOf(S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES
                .getOrDefault(label, S124StandardMappings.NAVWARN_TYPE_GENERAL_DEFAULT_CODE)));
        return ngt;
    }

    private static String getCategoryName(Category category) {
        if (category.getDescs() != null) {
            for (CategoryDesc desc : category.getDescs()) {
                if ("en".equals(desc.getLang()) && desc.getName() != null) {
                    return desc.getName();
                }
            }
        }
        return category.getLegacyId();
    }

    private String getObstructionTemplateType(Message message) {
        if (message.getParts() == null || message.getParts().isEmpty()) {
            return null;
        }
        MessagePart firstPart = message.getParts().getFirst();
        MessagePartDesc desc = findDesc(firstPart.getDescs(), "en");
        if (desc == null || desc.getSubject() == null) {
            return null;
        }
        String subject = desc.getSubject().toLowerCase();
        if (subject.contains("wreck")) {
            return "wreck";
        } else if (subject.contains("uncharted") || subject.contains("obstruction")) {
            return "uncharted obstruction";
        } else if (subject.contains("depth") || subject.contains("reduced")) {
            return "reduced depth";
        }
        return null;
    }

    /**
     * Synthesises a {@code generalArea} when the message has no associated areas, because the
     * S-124 schema requires at least one. Prefers the vicinity, falls back to the short id.
     */
    private GeneralAreaType fallbackGeneralArea(Message msg) {
        GeneralAreaType gat = S124_FACTORY.createGeneralAreaType();
        emitLocalized(msg.getDescs(), MessageDesc::getVicinity,
                (language, vicinity) -> gat.getLocationNames().add(locationName(language, vicinity)));
        if (gat.getLocationNames().isEmpty()) {
            String name = StringUtils.isNotBlank(msg.getShortId()) ? msg.getShortId() : "Unknown area";
            gat.getLocationNames().add(locationName(toIso639_2Language(lang), name));
        }
        return gat;
    }

    private GeneralAreaType toGeneralAreaType(Area area) {
        if (area == null) {
            return null;
        }
        GeneralAreaType gat = S124_FACTORY.createGeneralAreaType();
        emitLocalized(area.getDescs(), AreaDesc::getName,
                (language, name) -> gat.getLocationNames().add(locationName(language, name)));
        if (gat.getLocationNames().isEmpty()) {
            // No name in any emitted language: a name in another language (a proper noun,
            // tagged as such) still beats the bare id
            AreaDesc desc = findDesc(area.getDescs(), lang);
            gat.getLocationNames().add(desc != null && StringUtils.isNotBlank(desc.getName())
                    ? locationName(languageOf(desc), desc.getName())
                    : locationName(toIso639_2Language(lang), String.valueOf(area.getId())));
        }
        return gat;
    }

    private RestrictionType computeRestriction(Message message) {
        List<MessageTag> tags = tagLookup.apply(message);
        if (tags == null) {
            return null;
        }
        for (MessageTag tag : tags) {
            if ("RESTRICTED".equalsIgnoreCase(tag.getName())) {
                return buildRestriction(RestrictionLabel.ENTRY_PROHIBITED);
            } else if ("CAUTION".equalsIgnoreCase(tag.getName())) {
                return buildRestriction(RestrictionLabel.ENTRY_RESTRICTED);
            }
        }
        return null;
    }

    private static RestrictionType buildRestriction(RestrictionLabel label) {
        RestrictionType restriction = S124_FACTORY.createRestrictionType();
        restriction.setValue(label);
        restriction.setCode(BigInteger.valueOf(S124StandardMappings.RESTRICTION_CODES.get(label)));
        return restriction;
    }

    private String toMrn(Message msg) {
        String internalId;
        if (StringUtils.isNotBlank(msg.getShortId())) {
            internalId = msg.getShortId();
        } else if (msg.getUid() != null) {
            // The message uid, not the DB primary key: org.niord.secom.util.S124Mrn keys the
            // SECOM dataset row by the same expression, and the row MRN must always equal the
            // interoperabilityIdentifier embedded in the GML.
            internalId = msg.getUid();
        } else {
            internalId = PREVIEW_SHORT_ID;
        }
        return "urn:mrn:iho:" + msg.getMainType().name().toLowerCase() + ":" + country.toLowerCase(Locale.ROOT)
                + ":" + internalId.toLowerCase();
    }

    private static OffsetDateTime toOffsetDateTime(Date date) {
        return OffsetDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }

    /**
     * The publication time to emit for a PREVIEW of a draft that has never been published (and so
     * has no {@code publishDateFrom}). The schema requires the element, so fall back to the draft's
     * last-updated timestamp, then its creation time, then the current time for a wholly transient
     * draft that carries neither.
     */
    private static OffsetDateTime previewPublicationTime(Message msg) {
        Date date = msg.getUpdated() != null ? msg.getUpdated() : msg.getCreated();
        return date != null ? toOffsetDateTime(date) : OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Returns {@code baseId} if it is still free, otherwise the first unused {@code baseId.2},
     * {@code baseId.3}, ... An {@code xml:id} is a schema ID, so features that would otherwise
     * share an id (two references to the same target, or the null-id parts of an unsaved preview)
     * must be disambiguated or the dataset fails XSD validation.
     */
    private static String uniqueXmlId(Set<String> usedIds, String baseId) {
        String uniqueId = baseId;
        for (int n = 2; !usedIds.add(uniqueId); n++) {
            uniqueId = baseId + "." + n;
        }
        return uniqueId;
    }

    /**
     * The dataset reference date: when the message last changed, falling back to publication, then today. A date
     * of the dataset, not of the warning, so it is the UTC day throughout, today included.
     */
    private static LocalDate referenceDate(Message msg) {
        Date date = msg.getUpdated() != null ? msg.getUpdated() : msg.getPublishDateFrom();
        return date != null ? LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC) : LocalDate.now(ZoneOffset.UTC);
    }

    private String nextGeomId(String id) {
        return String.format("G.%s.%d", id, nextGeomId++);
    }

    /** Returns the JTS geometries of the part's feature collection (empty if the part has none) */
    private static List<Geometry> partGeometries(MessagePart part) {
        List<Geometry> result = new ArrayList<>();
        if (part.getGeometry() != null && part.getGeometry().getFeatures() != null) {
            for (Feature feature : part.getGeometry().getFeatures()) {
                Geometry geometry = feature.getGeometry();
                if (geometry != null && !geometry.isEmpty()) {
                    result.add(geometry);
                }
            }
        }
        return result;
    }

    private static BoundingShapeType generateBoundingShape(MessagePart part) {
        Envelope envelope = new Envelope();
        for (Geometry geometry : partGeometries(part)) {
            expandEnvelope(envelope, geometry);
        }
        return envelopeToBoundingShape(envelope);
    }

    /**
     * The dataset's {@code gml:boundedBy}. S-124 clause 12.2.2 makes the bounding box of the
     * exchange-set discovery metadata mandatory, and the factory reads it off the dataset, so a
     * dataset without an extent cannot be distributed at all. The extent is therefore derived
     * from the nearest spatial information the warning has:
     * <ol>
     *   <li>the geometries of its own parts;</li>
     *   <li>else those of the warnings it references - a cancellation warning carries no parts
     *       of its own ({@code MessageService.composeCancellationWarning}) and concerns exactly
     *       the area of the warning it cancels, the same reading the SECOM push vicinity uses;</li>
     *   <li>else the geometries of its areas (the nearest ancestor that has one), the area of
     *       applicability a warning without positions still states.</li>
     * </ol>
     * A warning with none of these has no extent, and the element is left out.
     */
    private BoundingShapeType datasetBoundingShape(Message message) {
        Envelope envelope = new Envelope();
        expandEnvelope(envelope, message);
        if (envelope.isNull()) {
            for (Message referenced : resolvedReferences.values()) {
                expandEnvelope(envelope, referenced);
            }
        }
        if (envelope.isNull() && message.getAreas() != null) {
            for (Area area : message.getAreas()) {
                Geometry geometry = nearestAreaGeometry(area);
                if (geometry != null) {
                    expandEnvelope(envelope, geometry);
                }
            }
        }
        return envelopeToBoundingShape(envelope);
    }

    /** Expands the envelope to include the geometries of all the message's parts */
    private static void expandEnvelope(Envelope envelope, Message message) {
        if (message.getParts() != null) {
            for (MessagePart p : message.getParts()) {
                for (Geometry geometry : partGeometries(p)) {
                    expandEnvelope(envelope, geometry);
                }
            }
        }
    }

    /** The geometry of the area or, failing that, of its nearest ancestor that has one */
    private static Geometry nearestAreaGeometry(Area area) {
        for (Area a = area; a != null; a = a.getParent()) {
            if (a.getGeometry() != null && !a.getGeometry().isEmpty()) {
                return a.getGeometry();
            }
        }
        return null;
    }

    private static void expandEnvelope(Envelope envelope, Geometry geometry) {
        for (Coordinate c : geometry.getEnvelope().getCoordinates()) {
            envelope.expandToInclude(c);
        }
    }

    private static BoundingShapeType envelopeToBoundingShape(Envelope envelope) {
        if (envelope.isNull()) {
            return null;
        }

        Pos lowerCorner = new PosImpl();
        // JTS Envelope is X,Y (lon,lat); GML expects lat,lon order.
        lowerCorner.setValue(new Double[] { envelope.getMinY(), envelope.getMinX() });
        Pos upperCorner = new PosImpl();
        upperCorner.setValue(new Double[] { envelope.getMaxY(), envelope.getMaxX() });

        BoundingShapeType boundingShapeType = new BoundingShapeTypeImpl();
        EnvelopeType envelopeType = new EnvelopeTypeImpl();
        envelopeType.setSrsName("EPSG:4326");
        envelopeType.setLowerCorner(lowerCorner);
        envelopeType.setUpperCorner(upperCorner);
        boundingShapeType.setEnvelope(envelopeType);
        return boundingShapeType;
    }

    /**
     * The ISO 639-2/T three-letter code of a Niord (ISO 639-1 two-letter) language code - the
     * code system S-100 spells languages in. {@code datasetLanguage} is bound to the schema's
     * {@code ISO-639-2} type, and the S-124 {@code language} sub-attributes ({@code informationType},
     * {@code locationName}, {@code navwarnTitle}) are written in the same system, so the two never
     * disagree and the exchange-set factory, which matches location names against
     * {@code Locale.getISO3Language()}, finds them. A code with no three-letter form is passed
     * through as is, never relabelled: the tag is the only thing telling a consumer what language
     * a text is in, so a wrong tag is worse than an unfamiliar one. Blank yields {@code und}
     * (ISO 639-2: undetermined).
     */
    public static String toIso639_2Language(String lang) {
        if (StringUtils.isBlank(lang)) {
            return "und";
        }
        String code = lang.trim().toLowerCase(Locale.ROOT);
        try {
            String iso3 = Locale.of(code).getISO3Language();
            return iso3.isEmpty() ? code : iso3;
        } catch (MissingResourceException e) {
            return code;
        }
    }

    /** The language tag of a localized description, falling back to the requested language */
    private String languageOf(ILocalizedDesc desc) {
        return toIso639_2Language(desc != null && StringUtils.isNotBlank(desc.getLang()) ? desc.getLang() : lang);
    }

    /**
     * The candidate languages the message has content in - a title, a vicinity or part
     * details - in candidate order, so the first is the primary. A message with content in
     * none of them keeps the first candidate alone: the dataset still needs a language to be
     * spelled in, and the single-valued names then resolve through {@link #findDesc}'s cascade.
     */
    private List<String> languagesWithContent(Message message) {
        List<String> withContent = languages.stream().filter(l -> hasContent(message, l)).toList();
        return withContent.isEmpty() ? List.of(languages.getFirst()) : withContent;
    }

    private static boolean hasContent(Message message, String lang) {
        MessageDesc desc = exactDesc(message.getDescs(), lang);
        if (desc != null && (StringUtils.isNotBlank(desc.getTitle()) || StringUtils.isNotBlank(desc.getVicinity()))) {
            return true;
        }
        if (message.getParts() != null) {
            for (MessagePart part : message.getParts()) {
                MessagePartDesc partDesc = exactDesc(part.getDescs(), lang);
                if (partDesc != null && StringUtils.isNotBlank(partDesc.getDetails())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Emits one language-tagged node per emitted language the descriptions have a non-blank
     * {@code text} for, primary first: {@code emit} receives the ISO 639-2 language tag and
     * the text. The one rule behind every multilingual element of the dataset - part texts,
     * titles, locality and area names.
     */
    private <D extends ILocalizedDesc> void emitLocalized(List<D> descs, Function<D, String> text,
            BiConsumer<String, String> emit) {
        for (String language : emitted) {
            D desc = exactDesc(descs, language);
            String value = desc != null ? text.apply(desc) : null;
            if (StringUtils.isNotBlank(value)) {
                emit.accept(toIso639_2Language(language), value);
            }
        }
    }

    /** A location name in the given ISO 639-2 language */
    private static LocationNameType locationName(String language, String text) {
        LocationNameType locationName = S124_FACTORY.createLocationNameType();
        locationName.setLanguage(language);
        locationName.setText(text);
        return locationName;
    }

    /** The description in exactly the given language, or null - no cascade */
    private static <D extends ILocalizedDesc> D exactDesc(List<D> descs, String lang) {
        if (descs == null) {
            return null;
        }
        for (D desc : descs) {
            if (desc != null && lang.equals(desc.getLang())) {
                return desc;
            }
        }
        return null;
    }

    /**
     * Resolves a description by language with the standard S-124 cascade: requested language,
     * then English, then the first available description.
     */
    private static <D extends ILocalizedDesc> D findDesc(List<D> descs, String lang) {
        if (descs == null || descs.isEmpty()) {
            return null;
        }
        D exact = exactDesc(descs, lang);
        if (exact != null) {
            return exact;
        }
        D english = "en".equals(lang) ? null : exactDesc(descs, "en");
        return english != null ? english : descs.getFirst();
    }

    /**
     * Distinguishes between a final S-124 generation (for production outbound) and a preview
     * generation (for the editor, where the message may not yet have a shortId or warning number).
     */
    public enum GenerationType {
        FINAL,
        PREVIEW
    }

}
