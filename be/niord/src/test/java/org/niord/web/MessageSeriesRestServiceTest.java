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
package org.niord.web;

import org.junit.jupiter.api.Test;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.vo.SystemMessageSeriesVo;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.message.MainType;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The message series list with {@code messageNumbers=true} reads each series' next number in the series' own
 * current year ({@link MessageSeriesService#currentYear(MessageSeries)}, the year in its domain's zone), not in the
 * JVM's year. The service is a mock whose series is a year ahead of the JVM, as one east of UTC is in the first
 * hours of 1 January.
 */
class MessageSeriesRestServiceTest {

    private static MessageSeries yearlySeries(String seriesId) {
        MessageSeries series = new MessageSeries(seriesId);
        series.setMainType(MainType.NW);
        series.setNumberSequenceType(NumberSequenceType.YEARLY);
        return series;
    }

    @Test
    void theListReadsTheNextNumberOfTheSeriesCurrentYearNotTheJvmYear() {
        int jvmYear = LocalDate.now().getYear();
        int seriesYear = jvmYear + 1;
        MessageSeries series = yearlySeries("east-nw");

        MessageSeriesService service = mock(MessageSeriesService.class);
        when(service.getAllMessageSeries()).thenReturn(List.of(series));
        when(service.currentYear(series)).thenReturn(seriesYear);
        when(service.getNextMessageNumber(eq("east-nw"), anyInt())).thenReturn(57);
        when(service.getNextMessageNumber("east-nw", seriesYear)).thenReturn(1);

        List<SystemMessageSeriesVo> list = new MessageSeriesRestService(service).getAllMessageSeries(true);

        assertEquals(1, list.size());
        assertEquals("east-nw", list.get(0).getSeriesId());
        assertEquals(1, list.get(0).getNextMessageNumber(),
                "the next number of the series' year " + seriesYear + ", not of the JVM's year " + jvmYear);
    }

    @Test
    void eachSeriesReadsItsOwnCurrentYear() {
        int jvmYear = LocalDate.now().getYear();
        MessageSeries east = yearlySeries("east-nw");
        MessageSeries west = yearlySeries("west-nw");

        MessageSeriesService service = mock(MessageSeriesService.class);
        when(service.getAllMessageSeries()).thenReturn(List.of(east, west));
        when(service.currentYear(east)).thenReturn(jvmYear + 1);
        when(service.currentYear(west)).thenReturn(jvmYear);
        when(service.getNextMessageNumber("east-nw", jvmYear + 1)).thenReturn(1);
        when(service.getNextMessageNumber("west-nw", jvmYear)).thenReturn(312);

        List<SystemMessageSeriesVo> list = new MessageSeriesRestService(service).getAllMessageSeries(true);

        assertEquals(1, list.get(0).getNextMessageNumber());
        assertEquals(312, list.get(1).getNextMessageNumber());
    }

    @Test
    void withoutMessageNumbersNoYearAndNoNumberIsRead() {
        MessageSeries series = yearlySeries("east-nw");
        MessageSeriesService service = mock(MessageSeriesService.class);
        when(service.getAllMessageSeries()).thenReturn(List.of(series));

        List<SystemMessageSeriesVo> list = new MessageSeriesRestService(service).getAllMessageSeries(false);

        assertNull(list.get(0).getNextMessageNumber());
        verify(service, never()).currentYear(series);
        verify(service, never()).getNextMessageNumber(eq("east-nw"), anyInt());
    }
}
