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

import org.niord.core.message.vo.SystemMessageSeriesVo;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.DataFilter;
import org.niord.model.message.MessageSeriesVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Maps between the {@link MessageSeries} entity and {@link MessageSeriesVo}/{@link SystemMessageSeriesVo}.
 *
 * <p>Replaces the {@code toVo()} method and the {@code MessageSeries(MessageSeriesVo)} conversion
 * constructor that were previously embedded in the entity.
 */
@Component
public class MessageSeriesMapper {

    /** Converts the entity to a value object, including all fields. */
    public <M extends MessageSeriesVo> M toVo(MessageSeries series, Class<M> clz) {
        return toVo(series, clz, DataFilter.get().fields(DataFilter.ALL));
    }

    /** Converts the entity to a value object, honouring the data filter. */
    public <M extends MessageSeriesVo> M toVo(MessageSeries series, Class<M> clz, DataFilter filter) {
        if (series == null) {
            return null;
        }

        MessageSeriesVo vo = SystemMessageSeriesVo.class.isAssignableFrom(clz)
                ? new SystemMessageSeriesVo() : new MessageSeriesVo();
        vo.setSeriesId(series.getSeriesId());

        DataFilter compFilter = filter.forComponent(MessageSeries.class);
        if (compFilter.includeDetails()) {
            vo.setMainType(series.getMainType());

            if (vo instanceof SystemMessageSeriesVo sysSeries) {
                if (!series.getTypes().isEmpty()) {
                    sysSeries.setTypes(new HashSet<>(series.getTypes()));
                }
                sysSeries.setShortFormat(series.getShortFormat());
                sysSeries.setNavtexFormat(series.getNavtexFormat());
                sysSeries.setNumberSequenceType(series.getNumberSequenceType());
                if (!series.getEditorFields().isEmpty()) {
                    sysSeries.setEditorFields(new ArrayList<>(series.getEditorFields()));
                }
                sysSeries.setExcludeFromMessageIndex(series.getExcludeFromMessageIndex());
            }
        }
        return clz.cast(vo);
    }

    /** Creates a new {@link MessageSeries} entity from the given value object. */
    public MessageSeries toEntity(MessageSeriesVo vo) {
        if (vo == null) {
            return null;
        }
        MessageSeries series = new MessageSeries();
        series.setSeriesId(vo.getSeriesId());
        series.setMainType(vo.getMainType());

        if (vo instanceof SystemMessageSeriesVo sysSeries) {
            if (sysSeries.getTypes() != null) {
                series.getTypes().addAll(sysSeries.getTypes());
            }
            series.setShortFormat(sysSeries.getShortFormat());
            series.setNavtexFormat(sysSeries.getNavtexFormat());
            series.setNumberSequenceType(sysSeries.getNumberSequenceType() != null
                    ? sysSeries.getNumberSequenceType()
                    : NumberSequenceType.YEARLY);
            if (sysSeries.getEditorFields() != null) {
                series.getEditorFields().addAll(sysSeries.getEditorFields());
            }
            series.setExcludeFromMessageIndex(sysSeries.getExcludeFromMessageIndex());
        }
        return series;
    }
}
