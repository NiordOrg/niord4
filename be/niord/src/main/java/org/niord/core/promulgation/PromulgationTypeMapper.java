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
package org.niord.core.promulgation;

import org.niord.core.domain.DomainMapper;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.DataFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * Maps between the {@link PromulgationType} entity and {@link PromulgationTypeVo}.
 *
 * <p>Replaces the {@code toVo()} method and the {@code PromulgationType(PromulgationTypeVo)} conversion
 * constructor that were previously embedded in the {@link PromulgationType} entity. The nested
 * {@code Domain} list is delegated to {@link DomainMapper}, mirroring the entity's old reliance on
 * {@code Domain::toVo}/{@code Domain::new}. The {@link DataFilter} {@code includeDetails()} projection
 * and the "only set non-empty collections" guards are preserved verbatim.
 *
 * <p>This mapper covers the self-contained {@code PromulgationType} aggregate only. The polymorphic
 * {@link BaseMessagePromulgation} graph (NAVTEX/SafetyNet/Twitter message promulgations) is dispatched
 * through the per-type promulgation-service registry and is intentionally left to those services.
 */
@Component
public class PromulgationTypeMapper {

    private final DomainMapper domainMapper;

    PromulgationTypeMapper(DomainMapper domainMapper) {
        this.domainMapper = domainMapper;
    }

    /** Returns the value object representation of the entity, including all detail fields. */
    public PromulgationTypeVo toVo(PromulgationType type) {
        return toVo(type, DataFilter.get().fields(DataFilter.DETAILS));
    }

    /** Returns the value object representation of the entity, honouring the data filter. */
    public PromulgationTypeVo toVo(PromulgationType type, DataFilter filter) {
        if (type == null) {
            return null;
        }

        DataFilter compFilter = filter.forComponent(PromulgationType.class);

        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(type.getTypeId());
        typeVo.setServiceId(type.getServiceId());
        typeVo.setName(type.getName());
        typeVo.setPriority(type.getPriority());
        typeVo.setRequirement(type.getRequirement());
        if (compFilter.includeDetails()) {
            typeVo.setActive(type.isActive());
            typeVo.setLanguage(type.getLanguage());
            typeVo.setDomains(type.getDomains().stream()
                    .map(domainMapper::toVo)
                    .collect(Collectors.toList()));
            if (!type.getMessageTypes().isEmpty()) {
                typeVo.setMessageTypes(new HashSet<>(type.getMessageTypes()));
            }
            if (!type.getScriptResourcePaths().isEmpty()) {
                typeVo.setScriptResourcePaths(new ArrayList<>(type.getScriptResourcePaths()));
            }
        }
        return typeVo;
    }

    /** Creates a new {@link PromulgationType} entity from the given value object. */
    public PromulgationType toEntity(PromulgationTypeVo typeVo) {
        if (typeVo == null) {
            return null;
        }
        PromulgationType type = new PromulgationType();
        updateEntity(type, typeVo);
        return type;
    }

    /** Updates an existing {@link PromulgationType} entity from the given value object. */
    public void updateEntity(PromulgationType type, PromulgationTypeVo typeVo) {
        type.setTypeId(typeVo.getTypeId());
        type.setServiceId(typeVo.getServiceId());
        type.setName(typeVo.getName());
        type.setPriority(typeVo.getPriority());
        type.setActive(typeVo.isActive() != null && typeVo.isActive());
        type.setRequirement(typeVo.getRequirement());
        type.setLanguage(typeVo.getLanguage());
        if (typeVo.getDomains() != null) {
            type.setDomains(typeVo.getDomains().stream()
                    .map(domainMapper::toEntity)
                    .collect(Collectors.toList()));
        }
        if (typeVo.getMessageTypes() != null) {
            type.getMessageTypes().addAll(typeVo.getMessageTypes());
        }
        if (typeVo.getScriptResourcePaths() != null) {
            type.getScriptResourcePaths().addAll(typeVo.getScriptResourcePaths());
        }
    }
}
