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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageSeriesVo;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.DataFilter;
import org.niord.model.message.MainType;
import org.niord.model.message.MessageSeriesVo;
import org.niord.model.message.Type;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MessageSeriesMapper}: pins the projection previously embedded in
 * {@code MessageSeries.toVo(...)} and the {@code MessageSeries(MessageSeriesVo)} conversion constructor.
 */
public class MessageSeriesMapperTest {

    private final MessageSeriesMapper mapper = new MessageSeriesMapper();

    private static MessageSeries series() {
        MessageSeries s = new MessageSeries();
        s.setSeriesId("dma-nw");
        s.setMainType(MainType.NW);
        s.getTypes().add(Type.COASTAL_WARNING);
        s.setShortFormat("NW-{number}");
        s.setNavtexFormat("navtex");
        s.setNumberSequenceType(NumberSequenceType.CONTINUOUS);
        s.setExcludeFromMessageIndex(Boolean.TRUE);
        return s;
    }

    @Test
    public void plainVoOnlyCarriesSeriesId() {
        MessageSeriesVo vo = mapper.toVo(series(), MessageSeriesVo.class,
                DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals("dma-nw", vo.getSeriesId());
        assertEquals(MainType.NW, vo.getMainType());
    }

    @Test
    public void seriesIdAlwaysCopiedEvenWithoutDetails() {
        MessageSeriesVo vo = mapper.toVo(series(), MessageSeriesVo.class, DataFilter.get());

        assertEquals("dma-nw", vo.getSeriesId());
        assertNull(vo.getMainType(), "mainType is a detail field");
    }

    @Test
    public void systemVoCopiesAllFieldsWithDetails() {
        SystemMessageSeriesVo vo = mapper.toVo(series(), SystemMessageSeriesVo.class);

        assertEquals("dma-nw", vo.getSeriesId());
        assertEquals(MainType.NW, vo.getMainType());
        assertTrue(vo.getTypes().contains(Type.COASTAL_WARNING));
        assertEquals("NW-{number}", vo.getShortFormat());
        assertEquals("navtex", vo.getNavtexFormat());
        assertEquals(NumberSequenceType.CONTINUOUS, vo.getNumberSequenceType());
        assertEquals(Boolean.TRUE, vo.getExcludeFromMessageIndex());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null, SystemMessageSeriesVo.class));
    }

    @Test
    public void toEntityCopiesSystemFields() {
        SystemMessageSeriesVo vo = new SystemMessageSeriesVo();
        vo.setSeriesId("dma-nm");
        vo.setMainType(MainType.NM);
        vo.setTypes(new HashSet<>(Arrays.asList(Type.LOCAL_WARNING)));
        vo.setShortFormat("NM-{number}");
        vo.setNumberSequenceType(NumberSequenceType.YEARLY);
        vo.setExcludeFromMessageIndex(Boolean.FALSE);

        MessageSeries series = mapper.toEntity(vo);

        assertEquals("dma-nm", series.getSeriesId());
        assertEquals(MainType.NM, series.getMainType());
        assertTrue(series.getTypes().contains(Type.LOCAL_WARNING));
        assertEquals("NM-{number}", series.getShortFormat());
        assertEquals(NumberSequenceType.YEARLY, series.getNumberSequenceType());
        assertEquals(Boolean.FALSE, series.getExcludeFromMessageIndex());
    }

    @Test
    public void toEntityDefaultsNumberSequenceTypeToYearly() {
        SystemMessageSeriesVo vo = new SystemMessageSeriesVo();
        vo.setSeriesId("dma-x");
        // numberSequenceType deliberately left null

        MessageSeries series = mapper.toEntity(vo);

        assertEquals(NumberSequenceType.YEARLY, series.getNumberSequenceType());
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }
}
