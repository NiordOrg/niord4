/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.integration;

import org.niord.core.integration.vo.MessageSeriesMappingVo;
import org.niord.core.integration.vo.NiordIntegrationVo;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Maps {@link NiordIntegration} aggregate roots (and their owned {@link MessageSeriesMapping}
 * children) to and from their value objects.
 * <p>
 * The conversion logic is copied verbatim from the entity's former {@code toVo()} /
 * conversion constructor so that the extraction is behaviour-preserving.
 */
@Component
public class NiordIntegrationMapper {

    /** Converts the entity to a value object */
    public NiordIntegrationVo toVo(NiordIntegration entity) {
        if (entity == null) {
            return null;
        }
        NiordIntegrationVo integration = new NiordIntegrationVo();
        integration.setId(entity.getId());
        integration.setUrl(entity.getUrl());
        integration.setActive(entity.isActive());
        integration.setAssignNewUids(entity.isAssignNewUids());
        integration.setCreateBaseData(entity.isCreateBaseData());
        integration.setMessageSeriesMappings(
                entity.getMessageSeriesMappings().stream()
                        .map(this::toVo)
                        .collect(Collectors.toList()));
        integration.setNextScheduledExecution(entity.getNextScheduledExecution());
        return integration;
    }

    /** Converts the value object to a new entity */
    public NiordIntegration toEntity(NiordIntegrationVo vo) {
        if (vo == null) {
            return null;
        }
        NiordIntegration entity = new NiordIntegration();
        entity.setId(vo.getId());
        entity.setUrl(vo.getUrl());
        entity.setActive(vo.isActive());
        entity.setAssignNewUids(vo.isAssignNewUids());
        entity.setCreateBaseData(vo.isCreateBaseData());
        vo.getMessageSeriesMappings().stream()
                .filter(MessageSeriesMappingVo::mappingDefined)
                .forEach(m -> entity.addMessageSeriesMapping(toEntity(m)));
        entity.setNextScheduledExecution(vo.getNextScheduledExecution());
        return entity;
    }

    /** Converts an owned {@link MessageSeriesMapping} child to its value object */
    private MessageSeriesMappingVo toVo(MessageSeriesMapping entity) {
        MessageSeriesMappingVo mapping = new MessageSeriesMappingVo();
        mapping.setSourceSeriesId(entity.getSourceSeriesId());
        mapping.setTargetSeriesId(entity.getTargetSeriesId());

        return mapping;
    }

    /** Converts an owned {@link MessageSeriesMapping} value object to a new entity */
    private MessageSeriesMapping toEntity(MessageSeriesMappingVo vo) {
        MessageSeriesMapping mapping = new MessageSeriesMapping();
        mapping.setSourceSeriesId(vo.getSourceSeriesId());
        mapping.setTargetSeriesId(vo.getTargetSeriesId());
        return mapping;
    }
}
