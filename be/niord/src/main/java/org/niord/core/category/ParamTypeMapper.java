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
package org.niord.core.category;

import org.niord.core.category.vo.CompositeParamTypeVo;
import org.niord.core.category.vo.ListParamTypeVo;
import org.niord.core.category.vo.ParamTypeVo;
import org.niord.core.category.vo.StandardParamTypeVo;
import org.niord.core.category.vo.TemplateParamDescVo;
import org.niord.core.category.vo.TemplateParamVo;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.model.DataFilter;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Maps between the {@link ParamType} aggregate (the abstract base plus its {@link StandardParamType},
 * {@link ListParamType} and {@link CompositeParamType} subclasses) and the {@link ParamTypeVo} hierarchy.
 *
 * <p>Replaces the polymorphic {@code toVo()} methods and the conversion constructors previously embedded in
 * the {@code ParamType} entities and the {@code ParamTypeVo.toEntity()} factory. Conversion lives here as a
 * stateless, dependency-injected bean so it can be unit-tested without a persistence context.
 *
 * <p>{@code toVo} dispatches on the concrete runtime subclass and {@code toEntity} dispatches on the concrete
 * VO subtype, reproducing each subtype's original conversion verbatim. The owned {@link TemplateParam} /
 * {@link TemplateParamDesc} children of a composite type are mapped inline (exactly as the old
 * hand-written-mapper style in {@code CategoryMapper}). The {@link DictionaryEntry} values of a list type are
 * mapped via the entity's own conversion (mirroring {@code ListParamType}'s original use of
 * {@code DictionaryEntry.toVo(filter)} / {@code new DictionaryEntry(vo)}), since the dictionary-entry
 * projection is not exposed on {@code DictionaryMapper}.
 */
@Component
public class ParamTypeMapper {

    /**
     * Converts the entity to a value object, honouring the data filter.
     *
     * <p>Dispatches on the concrete runtime subclass, producing the matching {@link StandardParamTypeVo},
     * {@link ListParamTypeVo} or {@link CompositeParamTypeVo}.
     */
    public ParamTypeVo toVo(ParamType paramType, DataFilter filter) {
        if (paramType == null) {
            return null;
        }
        if (paramType instanceof StandardParamType standard) {
            return toVo(standard, filter);
        } else if (paramType instanceof ListParamType list) {
            return toVo(list, filter);
        } else if (paramType instanceof CompositeParamType composite) {
            return toVo(composite, filter);
        }
        return null;
    }

    /** Converts a {@link StandardParamType} to its value object. */
    public StandardParamTypeVo toVo(StandardParamType paramType, DataFilter filter) {
        if (paramType == null) {
            return null;
        }
        StandardParamTypeVo vo = new StandardParamTypeVo();
        vo.setId(paramType.getId());
        vo.setName(paramType.getName());
        return vo;
    }

    /** Converts a {@link ListParamType} to its value object. */
    public ListParamTypeVo toVo(ListParamType paramType, DataFilter filter) {
        if (paramType == null) {
            return null;
        }
        ListParamTypeVo vo = new ListParamTypeVo();
        vo.setId(paramType.getId());
        vo.setName(paramType.getName());
        vo.setValues(paramType.getValues().stream()
                .map(v -> v.toVo(filter))
                .collect(Collectors.toList()));
        return vo;
    }

    /** Converts a {@link CompositeParamType} to its value object. */
    public CompositeParamTypeVo toVo(CompositeParamType paramType, DataFilter filter) {
        if (paramType == null) {
            return null;
        }
        CompositeParamTypeVo vo = new CompositeParamTypeVo();
        vo.setId(paramType.getId());
        vo.setName(paramType.getName());
        vo.setTemplateParams(paramType.getTemplateParams().stream()
                .map(v -> toTemplateParamVo(v, filter))
                .collect(Collectors.toList()));
        return vo;
    }

    /** Maps a single {@link TemplateParam} to its value object. */
    private TemplateParamVo toTemplateParamVo(TemplateParam param, DataFilter filter) {
        TemplateParamVo vo = new TemplateParamVo();
        vo.setParamId(param.getParamId());
        vo.setType(param.getType());
        vo.setMandatory(param.isMandatory());
        vo.setPositionList(param.isPositionList());
        if (!param.getDescs().isEmpty()) {
            vo.setDescs(param.getDescs(filter).stream()
                    .map(this::toTemplateParamDescVo)
                    .collect(Collectors.toList()));
        }
        return vo;
    }

    /** Maps a single {@link TemplateParamDesc} to its value object. */
    private TemplateParamDescVo toTemplateParamDescVo(TemplateParamDesc desc) {
        TemplateParamDescVo vo = new TemplateParamDescVo();
        vo.setLang(desc.getLang());
        vo.setName(desc.getName());
        return vo;
    }

    /**
     * Creates a new {@link ParamType} entity from the given value object.
     *
     * <p>Dispatches on the concrete VO subtype, building the matching {@link ListParamType},
     * {@link CompositeParamType} or {@link StandardParamType}.
     */
    public ParamType toEntity(ParamTypeVo vo) {
        if (vo == null) {
            return null;
        }
        ParamType paramType = null;
        if (vo instanceof ListParamTypeVo list) {
            paramType = toEntity(list);
        } else if (vo instanceof CompositeParamTypeVo composite) {
            paramType = toEntity(composite);
        } else if (vo instanceof StandardParamTypeVo standard) {
            paramType = toEntity(standard);
        }
        return paramType;
    }

    /** Creates a new {@link StandardParamType} entity from the given value object. */
    public StandardParamType toEntity(StandardParamTypeVo vo) {
        if (vo == null) {
            return null;
        }
        StandardParamType paramType = new StandardParamType();
        paramType.setId(vo.getId());
        paramType.setName(vo.getName());
        return paramType;
    }

    /** Creates a new {@link ListParamType} entity from the given value object. */
    public ListParamType toEntity(ListParamTypeVo vo) {
        if (vo == null) {
            return null;
        }
        ListParamType paramType = new ListParamType();
        paramType.setId(vo.getId());
        paramType.setName(vo.getName());
        vo.getValues().forEach(v -> paramType.getValues().add(new DictionaryEntry(v)));
        return paramType;
    }

    /** Creates a new {@link CompositeParamType} entity from the given value object. */
    public CompositeParamType toEntity(CompositeParamTypeVo vo) {
        if (vo == null) {
            return null;
        }
        CompositeParamType paramType = new CompositeParamType();
        paramType.setId(vo.getId());
        paramType.setName(vo.getName());
        vo.getTemplateParams().forEach(v -> paramType.getTemplateParams().add(toTemplateParamEntity(v)));
        return paramType;
    }

    /** Creates a new {@link TemplateParam} entity from the given value object. */
    private TemplateParam toTemplateParamEntity(TemplateParamVo vo) {
        TemplateParam param = new TemplateParam();
        param.setParamId(vo.getParamId());
        param.setType(vo.getType());
        param.setMandatory(vo.isMandatory());
        param.setPositionList(vo.isPositionList());
        if (vo.getDescs() != null) {
            vo.getDescs().forEach(desc -> param.createDesc(desc.getLang()).setName(desc.getName()));
        }
        return param;
    }
}
