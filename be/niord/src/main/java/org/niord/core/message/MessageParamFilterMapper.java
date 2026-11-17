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

import org.niord.core.message.vo.MessageParamFilterVo;
import org.springframework.stereotype.Component;

/**
 * Maps {@link MessageParamFilter} entities to/from {@link MessageParamFilterVo} value objects.
 * <p>
 * The conversion logic is copied verbatim from the entity's {@code toVo()} method and its
 * {@code MessageParamFilter(MessageParamFilterVo)} conversion constructor.
 */
@Component
public class MessageParamFilterMapper {

    /** Converts the entity to a value object */
    public MessageParamFilterVo toVo(MessageParamFilter filter) {
        if (filter == null) {
            return null;
        }
        MessageParamFilterVo vo = new MessageParamFilterVo();
        vo.setId(filter.getId());
        vo.setName(filter.getName());
        vo.setParameters(filter.getParameters());
        return vo;
    }

    /** Converts the value object to a new entity */
    public MessageParamFilter toEntity(MessageParamFilterVo vo) {
        if (vo == null) {
            return null;
        }
        MessageParamFilter filter = new MessageParamFilter();
        updateEntity(filter, vo);
        return filter;
    }

    /** Updates the entity in-place from the value object */
    public void updateEntity(MessageParamFilter filter, MessageParamFilterVo vo) {
        filter.setId(vo.getId());
        filter.setName(vo.getName());
        filter.setParameters(vo.getParameters());
    }
}
