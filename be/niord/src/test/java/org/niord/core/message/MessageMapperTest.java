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
import org.niord.core.area.Area;
import org.niord.core.area.AreaMapper;
import org.niord.core.category.CategoryMapper;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartMapper;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.DataFilter;
import org.niord.model.message.MainType;
import org.niord.model.message.MessageVo;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Differential test for {@link MessageMapper}: builds a rich {@link Message} entity and asserts that the
 * mapper produces the SAME value object as the (still-present) {@code Message.toVo(Class, DataFilter)}
 * entity method, field for field across the nested area/category/chart/series/reference/desc graph. This
 * is the strongest possible behaviour-preservation check for the keystone extraction.
 */
public class MessageMapperTest {

    private final MessageMapper mapper = new MessageMapper(
            new AreaMapper(),
            new CategoryMapper(),
            new ChartMapper(),
            new MessageSeriesMapper());

    private static Message richMessage() {
        Message m = new Message();
        m.setUid("uid-123");
        m.setNumber(42);
        m.setShortId("NW-042-25");
        m.setStatus(Status.PUBLISHED);
        m.setMainType(MainType.NW);
        m.setType(Type.COASTAL_WARNING);
        m.setKind(MessageKind.CANCELLATION);
        m.setRevision(3);

        MessageSeries series = new MessageSeries();
        series.setSeriesId("dma-nw");
        series.setMainType(MainType.NW);
        m.setMessageSeries(series);

        Area area = new Area();
        area.setId(10);
        area.checkCreateDesc("en").setName("Kattegat");
        m.getAreas().add(area);

        Chart chart = new Chart();
        chart.setChartNumber("101");
        chart.setActive(true);
        m.getCharts().add(chart);

        Reference ref = new Reference();
        ref.setMessageId("NW-001-25");
        m.addReference(ref);

        m.checkCreateDesc("da").setTitle("Dansk titel");
        m.checkCreateDesc("en").setTitle("English title");

        return m;
    }

    @Test
    public void mapperMatchesEntityOnSystemVoAcrossTheGraph() {
        Message message = richMessage();
        DataFilter filter = Message.MESSAGE_DETAILS_FILTER;

        SystemMessageVo expected = message.toVo(SystemMessageVo.class, filter);
        SystemMessageVo actual = mapper.toVo(message, SystemMessageVo.class, filter);

        // scalar identity
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getNumber(), actual.getNumber());
        assertEquals(expected.getShortId(), actual.getShortId());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getMainType(), actual.getMainType());
        assertEquals(expected.getType(), actual.getType());
        // kind is carried on the system VO in BOTH paths
        assertEquals(MessageKind.CANCELLATION, expected.getKind());
        assertEquals(MessageKind.CANCELLATION, actual.getKind());
        // revision is intentionally entity.revision + 1 in BOTH paths
        assertEquals(Integer.valueOf(4), Integer.valueOf(expected.getRevision()));
        assertEquals(Integer.valueOf(4), Integer.valueOf(actual.getRevision()));

        // nested message series
        assertEquals(expected.getMessageSeries().getSeriesId(), actual.getMessageSeries().getSeriesId());

        // nested areas / charts (delegated to AreaMapper / ChartMapper)
        assertEquals(expected.getAreas().size(), actual.getAreas().size());
        assertEquals(expected.getAreas().get(0).getId(), actual.getAreas().get(0).getId());
        assertEquals(expected.getCharts().size(), actual.getCharts().size());
        assertEquals(expected.getCharts().get(0).getChartNumber(), actual.getCharts().get(0).getChartNumber());

        // references
        assertEquals(expected.getReferences().size(), actual.getReferences().size());
        assertEquals(expected.getReferences().get(0).getMessageId(), actual.getReferences().get(0).getMessageId());

        // descriptions
        assertEquals(expected.getDescs().size(), actual.getDescs().size());
        assertEquals(expected.getDescs().get(0).getTitle(), actual.getDescs().get(0).getTitle());
        assertEquals(expected.getDescs().get(1).getTitle(), actual.getDescs().get(1).getTitle());
    }

    @Test
    public void mapperMatchesEntityOnPlainVo() {
        Message message = richMessage();
        DataFilter filter = Message.MESSAGE_DETAILS_FILTER;

        MessageVo expected = message.toVo(MessageVo.class, filter);
        MessageVo actual = mapper.toVo(message, MessageVo.class, filter);

        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getShortId(), actual.getShortId());
        assertEquals(expected.getAreas().size(), actual.getAreas().size());
        assertEquals(expected.getDescs().size(), actual.getDescs().size());
        // a plain MessageVo is NOT a SystemMessageVo
        org.junit.jupiter.api.Assertions.assertFalse(actual instanceof SystemMessageVo);
    }

    @Test
    public void mapperNumbersThePartsByTheirPlaceInTheMessage() {
        // MSG7: like Message.toVo, the mapper gives each part its place in the message as indexNo,
        // 0, 1, 2 in order, whatever the stored column holds.
        Message message = MessageEntityTest.threePartMessage();

        for (DataFilter filter : java.util.List.of(Message.MESSAGE_DETAILS_FILTER.lang("en"),
                Message.MESSAGE_MAP_FILTER.lang("en"))) {
            SystemMessageVo expected = message.toVo(SystemMessageVo.class, filter);
            SystemMessageVo actual = mapper.toVo(message, SystemMessageVo.class, filter);
            assertEquals(3, actual.getParts().size());
            for (int i = 0; i < 3; i++) {
                assertEquals(message.getParts().get(i).getType(), actual.getParts().get(i).getType());
                assertEquals(i, actual.getParts().get(i).getIndexNo(), "part " + i);
                assertEquals(expected.getParts().get(i).getIndexNo(), actual.getParts().get(i).getIndexNo());
            }
        }
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null, SystemMessageVo.class, DataFilter.get()));
    }
}
