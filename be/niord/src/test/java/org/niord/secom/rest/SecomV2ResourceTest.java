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

import org.grad.secomv2.core.models.GetResponseObject;
import org.grad.secomv2.core.models.GetSummaryResponseObject;
import org.grad.secomv2.core.models.enums.ContainerTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.S124ExchangeSetService;
import org.niord.secom.service.SecomOutboxService;
import org.niord.secom.service.SecomSubscriptionService;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the paging of Get and GetSummary ({@code /v2/object}, {@code /v2/object/summary}
 * and their POST forms, which delegate to these methods): every item on a page costs a signed
 * exchange-set build, so a requested pageSize is capped at the SECOM default of 100 - and the
 * pagination metadata advertises the size actually applied - and a page's exchange sets are
 * built in one batch rather than one call per item. The HTTP level is covered by
 * {@code SecomProtocolITTest.getPaginatesOneBased}.
 */
class SecomV2ResourceTest {

    private static final int MATCHES = 150;

    private S124DatasetService datasetService;
    private S124ExchangeSetService exchangeSetService;
    private SecomV2Resource resource;
    private List<S124Dataset> matches;

    @BeforeEach
    void setup() {
        datasetService = mock(S124DatasetService.class);
        exchangeSetService = mock(S124ExchangeSetService.class);
        resource = new SecomV2Resource(datasetService, exchangeSetService,
                mock(SecomSubscriptionService.class), mock(SecomOutboxService.class));

        matches = IntStream.range(0, MATCHES).mapToObj(SecomV2ResourceTest::dataset).toList();
        when(datasetService.search(any(), any(), any(), any())).thenReturn(matches);
        // One 42-byte "exchange set" per dataset asked for
        when(exchangeSetService.buildExchangeSets(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).stream().map(dataset -> new byte[42]).toList());
    }

    /** An active dataset row as the query returns it (no published version needed for a summary) */
    private static S124Dataset dataset(int i) {
        S124Dataset dataset = new S124Dataset();
        dataset.setMrn("urn:mrn:iho:s124:dk:nw:" + i + ":26");
        dataset.setUuid(UUID.randomUUID());
        dataset.setStatus(S124Dataset.Status.ACTIVE);
        dataset.setUpdated(new Date());
        return dataset;
    }

    @Test
    void getSummaryCapsAnOversizedPageAndBuildsItInOneBatch() {
        GetSummaryResponseObject response = resource.getSummary(
                null, null, null, null, null, null, null, null, 100_000);

        assertEquals(100, response.getSummaryObject().size(), "the page is capped at the SECOM default");
        assertEquals(100, response.getPagination().getMaxItemsPerPage(), "and advertises the size it applied");
        assertEquals(MATCHES, response.getPagination().getTotalItems(), "so a caller can tell it was truncated");
        assertEquals(matches.get(0).getUuid(), response.getSummaryObject().get(0).getDataReference());
        assertEquals(42L, response.getSummaryObject().get(99).getInfo_size(), "info_size is the built payload's length");

        verifyOneBatchOf(matches.subList(0, 100));
    }

    @Test
    void getCapsAnOversizedPageAndBuildsItInOneBatch() {
        GetResponseObject response = resource.get(null, null, null, null, null, null, null, null, null, 100_000);

        assertEquals(100, response.getDataResponseObject().size());
        assertEquals(100, response.getPagination().getMaxItemsPerPage());
        assertEquals(MATCHES, response.getPagination().getTotalItems());

        verifyOneBatchOf(matches.subList(0, 100));
    }

    @Test
    void theNextCappedPageHoldsTheRest() {
        GetSummaryResponseObject response = resource.getSummary(
                null, null, null, null, null, null, null, 2, 1_000);

        assertEquals(MATCHES - 100, response.getSummaryObject().size());
        assertEquals(100, response.getPagination().getMaxItemsPerPage());
        assertEquals(matches.get(100).getUuid(), response.getSummaryObject().get(0).getDataReference());
        verifyOneBatchOf(matches.subList(100, MATCHES));
    }

    @Test
    void aPageSizeBelowTheCapIsAppliedAsRequested() {
        GetSummaryResponseObject response = resource.getSummary(
                null, null, null, null, null, null, null, null, 10);

        assertEquals(10, response.getSummaryObject().size());
        assertEquals(10, response.getPagination().getMaxItemsPerPage());
        verifyOneBatchOf(matches.subList(0, 10));
    }

    @Test
    void bareDatasetPagesAreCappedTooAndBuildNoExchangeSet() {
        when(exchangeSetService.datasetFile(any())).thenReturn(new byte[7]);

        GetSummaryResponseObject response = resource.getSummary(
                ContainerTypeEnum.S100_DataSet, null, null, null, null, null, null, null, 100_000);

        assertEquals(100, response.getSummaryObject().size());
        assertEquals(7L, response.getSummaryObject().get(0).getInfo_size());
        verify(exchangeSetService, times(100)).datasetFile(any());
        verify(exchangeSetService, never()).buildExchangeSets(anyList());
    }

    /** Verifies the page's exchange sets came from exactly one batch call with exactly the given slice */
    @SuppressWarnings("unchecked")
    private void verifyOneBatchOf(List<S124Dataset> expectedSlice) {
        ArgumentCaptor<List<S124Dataset>> page = ArgumentCaptor.forClass(List.class);
        verify(exchangeSetService, times(1)).buildExchangeSets(page.capture());
        assertEquals(expectedSlice, page.getValue());
        verify(exchangeSetService, never()).buildExchangeSet(any(S124Dataset.class));
    }
}
