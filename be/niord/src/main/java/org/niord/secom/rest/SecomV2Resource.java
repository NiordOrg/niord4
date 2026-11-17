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
package org.niord.secom.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import org.grad.secomv2.core.exceptions.SecomNotAuthorisedException;
import org.grad.secomv2.core.exceptions.SecomNotFoundException;
import org.grad.secomv2.core.exceptions.SecomNotImplementedException;
import org.grad.secomv2.core.exceptions.SecomValidationException;
import org.grad.secomv2.core.interfaces.AcknowledgementServiceInterface;
import org.grad.secomv2.core.interfaces.CapabilityServiceInterface;
import org.grad.secomv2.core.interfaces.GetServiceInterface;
import org.grad.secomv2.core.interfaces.GetSummaryServiceInterface;
import org.grad.secomv2.core.interfaces.PingServiceInterface;
import org.grad.secomv2.core.interfaces.PostGetServiceInterface;
import org.grad.secomv2.core.interfaces.PostGetSummaryServiceInterface;
import org.grad.secomv2.core.interfaces.RemoveSubscriptionServiceInterface;
import org.grad.secomv2.core.interfaces.SubscriptionServiceInterface;
import org.grad.secomv2.core.models.AbstractEnvelope;
import org.grad.secomv2.core.models.AcknowledgementObject;
import org.grad.secomv2.core.models.AcknowledgementResponseObject;
import org.grad.secomv2.core.models.CapabilityObject;
import org.grad.secomv2.core.models.CapabilityResponseObject;
import org.grad.secomv2.core.models.DataResponseObject;
import org.grad.secomv2.core.models.EnvelopeAckObject;
import org.grad.secomv2.core.models.EnvelopeGetFilterObject;
import org.grad.secomv2.core.models.EnvelopeGetSummaryFilterObject;
import org.grad.secomv2.core.models.EnvelopeSubscriptionObject;
import org.grad.secomv2.core.models.GetFilterObject;
import org.grad.secomv2.core.models.GetResponseObject;
import org.grad.secomv2.core.models.GetSummaryFilterObject;
import org.grad.secomv2.core.models.GetSummaryResponseObject;
import org.grad.secomv2.core.models.ImplementedInterfaces;
import org.grad.secomv2.core.models.PaginationObject;
import org.grad.secomv2.core.models.PingResponseObject;
import org.grad.secomv2.core.models.RemoveSubscriptionResponseObject;
import org.grad.secomv2.core.models.SubscriptionRequestObject;
import org.grad.secomv2.core.models.SubscriptionResponseObject;
import org.grad.secomv2.core.models.SummaryObject;
import org.grad.secomv2.core.models.enums.AckTypeEnum;
import org.grad.secomv2.core.models.enums.ContainerTypeEnum;
import org.grad.secomv2.core.models.enums.SECOM_DataProductType;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.niord.core.geojson.JtsConverter;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124DatasetVersion;
import org.niord.secom.model.SecomSubscription;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.S124ExchangeSetService;
import org.niord.secom.service.SecomOutboxService;
import org.niord.secom.service.SecomSubscriptionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The SECOM v2 service endpoints of this Niord node, mounted on the RESTEasy servlet at
 * {@code /api/secom}: Capability, Ping, Get, GetSummary (each in both its URL-parameter and
 * its signed POST-body form), Subscription, RemoveSubscription and Acknowledgement.
 * One resource for every interface, backed by the persisted S-124 dataset store (stable
 * dataReferences, honored filters, real pagination).
 * <p>
 * SECOM's capability document has one {@code get} flag covering both query forms, so a
 * client that reads {@code get: true} is entitled to either, and the body form
 * ({@code POST /v2/object/search}) is the only one that can carry a geometry too large for
 * a URL. The POST methods therefore unpack the envelope and call the URL-parameter method
 * rather than repeating it, so the two forms cannot answer the same query differently.
 * <p>
 * Payloads are served as S-100 Part 17 exchange sets (the design-mandated containerType 1,
 * also the default when the request does not specify one) or as bare {@code S100_DataSet}
 * GML when explicitly requested (legacy peers).
 * <p>
 * Not exposed (and absent from the capability): Upload/UploadLink (Niord is a producer),
 * the GetByLink flows, and the access/encryption-key interfaces.
 * <p>
 * Authentication happens in {@link MrnCertificateFilter}; the requesting node's MRN is read
 * from the {@code niord.secom.mrn} request attribute.
 */
@Component
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@Path("/")
@SuppressWarnings("unused")
public class SecomV2Resource implements CapabilityServiceInterface, PingServiceInterface, GetServiceInterface,
        GetSummaryServiceInterface, PostGetServiceInterface, PostGetSummaryServiceInterface,
        SubscriptionServiceInterface, RemoveSubscriptionServiceInterface,
        AcknowledgementServiceInterface {

    /**
     * The SECOM v2 default page size, advertised in the pagination metadata - and the largest
     * page served: every exchange set on a page is built and signed per request, so a
     * requested pageSize above it is capped to it.
     */
    static final int DEFAULT_PAGE_SIZE = 100;

    private final S124DatasetService datasetService;
    private final S124ExchangeSetService exchangeSetService;
    private final SecomSubscriptionService subscriptionService;
    private final SecomOutboxService outboxService;

    @Context
    HttpServletRequest servletRequest;

    public SecomV2Resource(S124DatasetService datasetService, S124ExchangeSetService exchangeSetService,
                           SecomSubscriptionService subscriptionService, SecomOutboxService outboxService) {
        this.datasetService = datasetService;
        this.exchangeSetService = exchangeSetService;
        this.subscriptionService = subscriptionService;
        this.outboxService = outboxService;
    }

    /** Returns the MRN of the authenticated remote node (set by {@link MrnCertificateFilter}) */
    private String mrn() {
        String mrn = (String) servletRequest.getAttribute(MrnCertificateFilter.MRN_PROPERTY);
        if (mrn == null) {
            throw new SecomNotAuthorisedException("No authenticated MRN on request");
        }
        return mrn;
    }

    /***************************************/
    /** Capability + Ping                 **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public CapabilityResponseObject capability() {
        ImplementedInterfaces implementedInterfaces = new ImplementedInterfaces();
        implementedInterfaces.setGet(true);
        implementedInterfaces.setGetSummary(true);
        implementedInterfaces.setSubscription(true);

        CapabilityObject capabilityObject = new CapabilityObject();
        // The design-mandated payload packaging (§6.1.4/§6.3.5): S-100 exchange sets.
        // Bare S100_DataSet payloads remain available on explicit request (legacy peers).
        capabilityObject.setContainerType(ContainerTypeEnum.S100_ExchangeSet);
        capabilityObject.setDataProductType(SECOM_DataProductType.S124);
        capabilityObject.setImplementedInterfaces(implementedInterfaces);
        capabilityObject.setServiceVersion(S124DatasetService.S124_PRODUCT_VERSION);

        CapabilityResponseObject response = new CapabilityResponseObject();
        response.setCapability(List.of(capabilityObject));
        return response;
    }

    /** {@inheritDoc} */
    @Override
    public PingResponseObject ping() {
        // The SECOM ping timestamp is the service-side "lastUpdatedAt": when the S-124
        // dataset store last changed (falling back to now for an empty store)
        PingResponseObject response = new PingResponseObject();
        response.setTimestamp(Optional.ofNullable(datasetService.lastUpdated())
                .map(Date::toInstant)
                .orElseGet(Instant::now));
        return response;
    }

    /***************************************/
    /** Get + GetSummary                  **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public GetResponseObject get(@QueryParam("dataReference") UUID dataReference,
                                 @QueryParam("containerType") ContainerTypeEnum containerType,
                                 @QueryParam("dataProductType") SECOM_DataProductType dataProductType,
                                 @QueryParam("productVersion") String productVersion,
                                 @QueryParam("geometry") String geometry,
                                 @QueryParam("unlocode") String unlocode,
                                 @QueryParam("validFrom") Instant validFrom,
                                 @QueryParam("validTo") Instant validTo,
                                 @QueryParam("page") Integer page,
                                 @QueryParam("pageSize") Integer pageSize) {

        List<S124Dataset> matches = search(dataReference, containerType, dataProductType, productVersion,
                geometry, unlocode, validFrom, validTo);
        // "If no data is returned, the response is one of the HTTP codes 400, 403 or 404"
        // (design §6.3.5) - an empty result set is a 404, not an empty 200
        if (matches.isEmpty()) {
            throw new SecomNotFoundException("No S-124 datasets match the given criteria");
        }
        List<S124Dataset> pageSlice = paginate(matches, page, pageSize);

        ContainerTypeEnum effectiveContainer = effectiveContainerType(containerType);
        List<DataResponseObject> data = new ArrayList<>();
        for (byte[] payload : payloadsFor(pageSlice, effectiveContainer)) {
            DataResponseObject dro = new DataResponseObject();
            // Exchange metadata, signing and base64 encoding are applied by the SECOM writer interceptor
            dro.setData(payload);
            data.add(dro);
        }

        GetResponseObject response = new GetResponseObject();
        response.setDataResponseObject(data);
        response.setPagination(new PaginationObject(matches.size(), effectivePageSize(pageSize)));
        return response;
    }

    /** {@inheritDoc} */
    @Override
    public GetSummaryResponseObject getSummary(@QueryParam("containerType") ContainerTypeEnum containerType,
                                               @QueryParam("dataProductType") SECOM_DataProductType dataProductType,
                                               @QueryParam("productVersion") String productVersion,
                                               @QueryParam("geometry") String geometry,
                                               @QueryParam("unlocode") String unlocode,
                                               @QueryParam("validFrom") Instant validFrom,
                                               @QueryParam("validTo") Instant validTo,
                                               @QueryParam("page") Integer page,
                                               @QueryParam("pageSize") Integer pageSize) {

        List<S124Dataset> matches = search(null, containerType, dataProductType, productVersion,
                geometry, unlocode, validFrom, validTo);
        List<S124Dataset> pageSlice = paginate(matches, page, pageSize);

        ContainerTypeEnum effectiveContainer = effectiveContainerType(containerType);
        List<byte[]> payloads = payloadsFor(pageSlice, effectiveContainer);
        List<SummaryObject> summaries = new ArrayList<>();
        for (int i = 0; i < pageSlice.size(); i++) {
            S124Dataset dataset = pageSlice.get(i);
            SummaryObject summary = new SummaryObject();
            summary.setDataReference(dataset.getUuid());
            summary.setDataProtection(Boolean.FALSE);
            summary.setDataCompression(Boolean.FALSE);
            summary.setContainerType(effectiveContainer);
            summary.setDataProductType(SECOM_DataProductType.S124);
            // The info_* fields are defined against the S-124 dataset content (design §6.4.5):
            // info_identifier = the dataset gml:id, info_name = datasetFileIdentifier (the file
            // name the version was published under), info_description = datasetTitle - all
            // captured on the version when it was published, so nothing is parsed here
            S124DatasetVersion version = dataset.getCurrentVersion();
            summary.setInfo_identifier(version != null && version.getDatasetId() != null
                    ? version.getDatasetId() : dataset.getMrn());
            summary.setInfo_name(version != null ? version.getFileName() : dataset.getMrn());
            if (version != null && version.getDatasetTitle() != null) {
                summary.setInfo_description(version.getDatasetTitle());
            }
            summary.setInfo_status(dataset.getStatus().name());
            summary.setInfo_lastModifiedDate(dataset.getUpdated().toInstant());
            summary.setInfo_productVersion(S124DatasetService.S124_PRODUCT_VERSION);
            // "The size of the dataset attribute when retrieved via the Get interface" - i.e.
            // the size of the payload a Get with the same containerType would return
            summary.setInfo_size((long) payloads.get(i).length);
            summaries.add(summary);
        }

        GetSummaryResponseObject response = new GetSummaryResponseObject();
        response.setSummaryObject(summaries);
        response.setPagination(new PaginationObject(matches.size(), effectivePageSize(pageSize)));
        return response;
    }

    /**
     * {@inheritDoc}
     *
     * The body form of {@link #get}: SECOM puts the same ten filters in a signed envelope
     * instead of the URL, which is what a client has to use once its geometry outgrows a
     * query string. The envelope signature has already been verified by the SECOM signature
     * filter when this runs; the envelope is unpacked and handed to the URL-parameter method,
     * so both forms answer a given query with the same datasets, the same paging and the same
     * exchange sets.
     */
    @Override
    public GetResponseObject get(@Valid GetFilterObject getFilterObject) {
        EnvelopeGetFilterObject envelope = requireEnvelope(getFilterObject != null ? getFilterObject.getEnvelope() : null);
        return get(envelope.getDataReference(), envelope.getContainerType(), envelope.getDataProductType(),
                envelope.getProductVersion(), envelope.getGeometry(), envelope.getUnlocode(),
                envelope.getValidFrom(), envelope.getValidTo(), envelope.getPage(), envelope.getPageSize());
    }

    /**
     * {@inheritDoc}
     *
     * The body form of {@link #getSummary}, unpacked and delegated the same way
     * {@link #get(GetFilterObject)} is.
     */
    @Override
    public GetSummaryResponseObject getSummary(@Valid GetSummaryFilterObject getSummaryFilterObject) {
        EnvelopeGetSummaryFilterObject envelope =
                requireEnvelope(getSummaryFilterObject != null ? getSummaryFilterObject.getEnvelope() : null);
        return getSummary(envelope.getContainerType(), envelope.getDataProductType(), envelope.getProductVersion(),
                envelope.getGeometry(), envelope.getUnlocode(), envelope.getValidFrom(), envelope.getValidTo(),
                envelope.getPage(), envelope.getPageSize());
    }

    /** Validates that a POST body form carries an envelope with the SECOM-required signing attributes */
    private static <T extends AbstractEnvelope> T requireEnvelope(T envelope) {
        if (envelope == null) {
            throw new SecomValidationException("Envelope was missing from the request");
        } else if (envelope.getEnvelopeRootCertificateThumbprint() == null) {
            throw new SecomValidationException("Required attribute 'envelopeRootCertificateThumbprint' was missing from envelope");
        } else if (envelope.getEnvelopeSignatureTime() == null) {
            throw new SecomValidationException("Required attribute 'envelopeSignatureTime' was missing from envelope");
        }
        return envelope;
    }

    /** The container type a Get/GetSummary serves: the design-mandated exchange set unless the bare dataset is requested */
    private static ContainerTypeEnum effectiveContainerType(ContainerTypeEnum requested) {
        return requested != null ? requested : ContainerTypeEnum.S100_ExchangeSet;
    }

    /**
     * The payload bytes Get serves for each dataset of the page, in the given container and in
     * page order: each dataset's current version, verbatim - bare, or in an exchange set of its
     * own. The exchange sets of a page are built in one batch, which reads the fileless
     * cancellation candidates once rather than once per dataset.
     */
    private List<byte[]> payloadsFor(List<S124Dataset> datasets, ContainerTypeEnum containerType) {
        return containerType == ContainerTypeEnum.S100_DataSet
                ? datasets.stream().map(exchangeSetService::datasetFile).toList()
                : exchangeSetService.buildExchangeSets(datasets);
    }

    /** Shared Get/GetSummary parameter validation + dataset query */
    private List<S124Dataset> search(UUID dataReference, ContainerTypeEnum containerType,
                                     SECOM_DataProductType dataProductType, String productVersion,
                                     String geometry, String unlocode, Instant validFrom, Instant validTo) {
        if (containerType == ContainerTypeEnum.NONE) {
            throw new SecomValidationException("NONE cannot be specified for containerType");
        }
        if (dataProductType != null && dataProductType != SECOM_DataProductType.S124) {
            throw new SecomNotImplementedException(dataProductType + " not supported, supported products: [S124]");
        }
        if (unlocode != null && !unlocode.isBlank()) {
            throw new SecomValidationException("UNLOCODE filtering is not supported by this service");
        }
        if (productVersion != null && !productVersion.equals(S124DatasetService.S124_PRODUCT_VERSION)) {
            return List.of();
        }
        return datasetService.search(dataReference, parseWkt(geometry),
                validFrom != null ? Date.from(validFrom) : null,
                validTo != null ? Date.from(validTo) : null);
    }

    /**
     * The page size actually applied - must match what the pagination metadata advertises:
     * the requested size, capped at {@link #DEFAULT_PAGE_SIZE}. A capped caller still sees the
     * truncation, as {@code totalItems} exceeding {@code maxItemsPerPage}.
     */
    private static int effectivePageSize(Integer pageSize) {
        return pageSize != null ? Math.min(pageSize, DEFAULT_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
    }

    /** Applies SECOM v2 pagination (page is 1-based, default page size {@link #DEFAULT_PAGE_SIZE}) */
    private static List<S124Dataset> paginate(List<S124Dataset> matches, Integer page, Integer pageSize) {
        int size = effectivePageSize(pageSize);
        int first = (page != null ? page - 1 : 0) * size;
        if (first < 0 || size < 0) {
            throw new SecomValidationException("Invalid pagination parameters");
        }
        if (first >= matches.size()) {
            return List.of();
        }
        return matches.subList(first, Math.min(first + size, matches.size()));
    }

    private static Geometry parseWkt(String wkt) {
        try {
            return JtsConverter.wktToJts(wkt);
        } catch (ParseException e) {
            throw new SecomValidationException("Invalid WKT geometry: " + e.getMessage());
        }
    }

    /***************************************/
    /** Subscriptions                     **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public SubscriptionResponseObject subscription(@Valid SubscriptionRequestObject request) {
        EnvelopeSubscriptionObject envelope = request != null ? request.getEnvelope() : null;
        if (envelope == null) {
            throw new SecomValidationException("Envelope was missing from the subscription request");
        }
        if (envelope.getDataProductType() != null && envelope.getDataProductType() != SECOM_DataProductType.S124) {
            throw new SecomNotImplementedException(envelope.getDataProductType() + " not supported, supported products: [S124]");
        }
        // Both containers can be served: the design-mandated S100_ExchangeSet (also the
        // default when unspecified) and the bare S100_DataSet for legacy peers
        if (envelope.getContainerType() == ContainerTypeEnum.NONE) {
            throw new SecomValidationException("NONE cannot be specified for containerType");
        }
        if (envelope.getUnlocode() != null && !envelope.getUnlocode().isBlank()) {
            throw new SecomValidationException("UNLOCODE filtering is not supported by this service");
        }
        // Normalize the version filter and reject unsupported editions loudly: a subscription
        // whose productVersion can never match (the matcher only serves 2.0.0) would otherwise
        // be accepted but silently never receive a push - the trap behind the spec's own
        // §6.5.4 example, which says "2.2.0" (docs/s124-spec-wg-feedback.md item 1)
        String productVersion = envelope.getProductVersion() != null && !envelope.getProductVersion().isBlank()
                ? envelope.getProductVersion().trim()
                : null;
        if (productVersion != null && !S124DatasetService.S124_PRODUCT_VERSION.equals(productVersion)) {
            throw new SecomValidationException("productVersion " + productVersion
                    + " not supported, supported versions: [" + S124DatasetService.S124_PRODUCT_VERSION + "]");
        }

        SecomSubscription template = new SecomSubscription();
        template.setDataProductType(SECOM_DataProductType.S124.name());
        template.setProductVersion(productVersion);
        template.setContainerType(envelope.getContainerType() != null ? envelope.getContainerType().name() : null);
        template.setDataReference(envelope.getDataReference());
        if (envelope.getGeometry() != null && !envelope.getGeometry().isBlank()) {
            template.setGeometry(parseWkt(envelope.getGeometry()));
            template.setWkt(envelope.getGeometry());
        }
        template.setPeriodStart(envelope.getSubscriptionPeriodStart() != null ? Date.from(envelope.getSubscriptionPeriodStart()) : null);
        template.setPeriodEnd(envelope.getSubscriptionPeriodEnd() != null ? Date.from(envelope.getSubscriptionPeriodEnd()) : null);

        // The service also enqueues the SUBSCRIPTION_CREATED confirmation notification
        SecomSubscription subscription = subscriptionService.subscribe(mrn(), template);

        SubscriptionResponseObject response = new SubscriptionResponseObject();
        response.setSubscriptionIdentifier(subscription.getUuid());
        return response;
    }

    /** {@inheritDoc} */
    @Override
    public RemoveSubscriptionResponseObject removeSubscription(@QueryParam(value = "subscriptionIdentifier") UUID subscriptionIdentifier) {
        if (subscriptionIdentifier == null) {
            throw new SecomValidationException("Required attribute 'subscriptionIdentifier' was missing");
        }

        // The service also enqueues the SUBSCRIPTION_REMOVED notification
        subscriptionService.unsubscribe(mrn(), subscriptionIdentifier);

        RemoveSubscriptionResponseObject response = new RemoveSubscriptionResponseObject();
        response.setMessage("Subscription " + subscriptionIdentifier + " removed");
        return response;
    }

    /***************************************/
    /** Acknowledgement                   **/
    /***************************************/

    /** {@inheritDoc} */
    @Override
    public AcknowledgementResponseObject acknowledgment(@Valid AcknowledgementObject acknowledgementObject) {
        EnvelopeAckObject envelope = acknowledgementObject != null ? acknowledgementObject.getEnvelope() : null;
        if (envelope == null) {
            throw new SecomValidationException("EnvelopeAckObject was missing");
        } else if (envelope.getEnvelopeRootCertificateThumbprint() == null) {
            throw new SecomValidationException("Required attribute 'envelopeRootCertificateThumbprint' was missing from envelope");
        } else if (envelope.getEnvelopeSignatureTime() == null) {
            throw new SecomValidationException("Required attribute 'envelopeSignatureTime' was missing from envelope");
        } else if (envelope.getCreatedAt() == null) {
            throw new SecomValidationException("Required attribute 'createdAt' was missing from envelope");
        } else if (envelope.getTransactionIdentifier() == null) {
            throw new SecomValidationException("Required attribute 'transactionIdentifier' was missing from envelope");
        } else if (envelope.getAckType() == null) {
            throw new SecomValidationException("Required attribute 'ackType' was missing from envelope");
        }
        if (envelope.getAckType() == AckTypeEnum.ERROR && envelope.getNackType() == null) {
            throw new SecomValidationException("AckType was Error, but nackType was not specified");
        }

        outboxService.applyAcknowledgement(mrn(), envelope.getTransactionIdentifier(),
                envelope.getAckType(), envelope.getNackType());

        AcknowledgementResponseObject response = new AcknowledgementResponseObject();
        response.setMessage(String.format("Received Acknowledgement for transaction, uuid = %s, ackType = %s%s",
                envelope.getTransactionIdentifier(), envelope.getAckType(),
                envelope.getNackType() != null ? ", nackType = " + envelope.getNackType() : ""));
        return response;
    }
}
