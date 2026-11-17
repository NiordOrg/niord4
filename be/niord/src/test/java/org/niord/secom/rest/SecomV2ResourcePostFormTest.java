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

import org.grad.secomv2.core.exceptions.SecomNotFoundException;
import org.grad.secomv2.core.exceptions.SecomValidationException;
import org.grad.secomv2.core.models.EnvelopeGetFilterObject;
import org.grad.secomv2.core.models.EnvelopeGetSummaryFilterObject;
import org.grad.secomv2.core.models.GetFilterObject;
import org.grad.secomv2.core.models.GetSummaryFilterObject;
import org.grad.secomv2.core.models.GetSummaryResponseObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.mockito.ArgumentCaptor;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.S124ExchangeSetService;
import org.niord.secom.service.SecomOutboxService;
import org.niord.secom.service.SecomSubscriptionService;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the POST body form of Get/GetSummary ({@code POST /v2/object/search} and
 * {@code /v2/object/search/summary}): the envelope validation, and that the envelope filters
 * are unpacked and handed to the same query logic as the URL-parameter form — the two forms
 * must not be able to answer the same query differently. The HTTP routing, signature filter
 * and payload assembly are covered by {@code SecomPostGetITTest}.
 */
class SecomV2ResourcePostFormTest {

    private S124DatasetService datasetService;
    private SecomV2Resource resource;

    @BeforeEach
    void setup() {
        datasetService = mock(S124DatasetService.class);
        resource = new SecomV2Resource(datasetService, mock(S124ExchangeSetService.class),
                mock(SecomSubscriptionService.class), mock(SecomOutboxService.class));
    }

    /** {@return an envelope carrying the signing attributes the SECOM signature filter requires} */
    private static EnvelopeGetFilterObject signedEnvelope() {
        EnvelopeGetFilterObject envelope = new EnvelopeGetFilterObject();
        envelope.setEnvelopeRootCertificateThumbprint("AB12CD");
        envelope.setEnvelopeSignatureTime(Instant.now());
        return envelope;
    }

    @Test
    void thePostQueryFiltersReachTheSameDatasetQueryAsTheUrlForm() {
        UUID dataReference = UUID.randomUUID();
        Instant validFrom = Instant.parse("2026-01-01T00:00:00Z");
        Instant validTo = Instant.parse("2026-02-01T00:00:00Z");

        EnvelopeGetFilterObject envelope = signedEnvelope();
        envelope.setDataReference(dataReference);
        envelope.setProductVersion("2.0.0");
        envelope.setGeometry("POINT (11.1 55.5)");
        envelope.setValidFrom(validFrom);
        envelope.setValidTo(validTo);
        GetFilterObject request = new GetFilterObject();
        request.setEnvelope(envelope);

        when(datasetService.search(any(), any(), any(), any())).thenReturn(List.of());

        // An empty result is a 404 in both forms (design §6.3.5) - and reaching that rule at
        // all proves the request got past envelope validation into the shared query logic
        assertThrows(SecomNotFoundException.class, () -> resource.get(request));

        ArgumentCaptor<UUID> ref = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Geometry> geometry = ArgumentCaptor.forClass(Geometry.class);
        ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> to = ArgumentCaptor.forClass(Date.class);
        verify(datasetService).search(ref.capture(), geometry.capture(), from.capture(), to.capture());
        assertEquals(dataReference, ref.getValue());
        assertEquals("POINT (11.1 55.5)", geometry.getValue().toText());
        assertEquals(Date.from(validFrom), from.getValue());
        assertEquals(Date.from(validTo), to.getValue());
    }

    @Test
    void thePostSummaryQueryFiltersReachTheSameDatasetQueryAsTheUrlForm() {
        EnvelopeGetSummaryFilterObject envelope = new EnvelopeGetSummaryFilterObject();
        envelope.setEnvelopeRootCertificateThumbprint("AB12CD");
        envelope.setEnvelopeSignatureTime(Instant.now());
        GetSummaryFilterObject request = new GetSummaryFilterObject();
        request.setEnvelope(envelope);

        when(datasetService.search(any(), any(), any(), any())).thenReturn(List.of());

        // Unlike Get, an empty summary is a 200 with no entries - same as the URL form
        GetSummaryResponseObject response = resource.getSummary(request);
        assertTrue(response.getSummaryObject().isEmpty());
        assertEquals(0, response.getPagination().getTotalItems());
        verify(datasetService).search(null, null, null, null);
    }

    @Test
    void aPostQueryWithoutAnEnvelopeIsRejected() {
        assertThrows(SecomValidationException.class, () -> resource.get((GetFilterObject) null));
        assertThrows(SecomValidationException.class, () -> resource.get(new GetFilterObject()));
        assertThrows(SecomValidationException.class, () -> resource.getSummary((GetSummaryFilterObject) null));
        assertThrows(SecomValidationException.class, () -> resource.getSummary(new GetSummaryFilterObject()));
    }

    @Test
    void aPostQueryEnvelopeMustCarryTheSigningAttributes() {
        // Same required-attribute posture as the acknowledgement endpoint: thumbprint + signature time
        EnvelopeGetFilterObject noThumbprint = new EnvelopeGetFilterObject();
        noThumbprint.setEnvelopeSignatureTime(Instant.now());
        GetFilterObject withoutThumbprint = new GetFilterObject();
        withoutThumbprint.setEnvelope(noThumbprint);
        assertThrows(SecomValidationException.class, () -> resource.get(withoutThumbprint));

        EnvelopeGetFilterObject noTime = new EnvelopeGetFilterObject();
        noTime.setEnvelopeRootCertificateThumbprint("AB12CD");
        GetFilterObject withoutTime = new GetFilterObject();
        withoutTime.setEnvelope(noTime);
        assertThrows(SecomValidationException.class, () -> resource.get(withoutTime));
    }

    @Test
    void thePostQueryAppliesTheSameParameterValidationAsTheUrlForm() {
        // The URL form rejects UNLOCODE filtering as unsupported - the body form must too
        EnvelopeGetFilterObject envelope = signedEnvelope();
        envelope.setUnlocode("DKCPH");
        GetFilterObject request = new GetFilterObject();
        request.setEnvelope(envelope);

        assertThrows(SecomValidationException.class, () -> resource.get(request));
    }
}
