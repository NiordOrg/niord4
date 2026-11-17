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
package org.niord.core.category;

import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.category.vo.TemplateParamDescVo;
import org.niord.core.category.vo.TemplateParamVo;
import org.niord.core.domain.DomainMapper;
import org.niord.core.script.ScriptResource;
import org.niord.model.DataFilter;
import org.niord.model.message.CategoryDescVo;
import org.niord.model.message.CategoryVo;
import org.springframework.stereotype.Component;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Maps between the {@link Category} entity and {@link CategoryVo}/{@link SystemCategoryVo}.
 *
 * <p>Replaces the {@code toVo()}/{@code updateCategory()} methods previously embedded in the entity.
 * Self-recursive parent/children traversal moves onto the mapper; the {@code Category <-> Domain}
 * wiring cycle is resolved by field injection. {@code TemplateParam}s and descriptions are mapped
 * inline (they are owned children, exactly as in the old hand-written-mapper style).
 */
@Component
public class CategoryMapper {

    @Inject
    DomainMapper domainMapper;

    /** Converts the entity to a value object, honouring the data filter. */
    public <C extends CategoryVo> C toVo(Category category, Class<C> clz, DataFilter filter) {
        if (category == null) {
            return null;
        }

        DataFilter compFilter = filter.forComponent(Category.class);

        CategoryVo vo = SystemCategoryVo.class.isAssignableFrom(clz) ? new SystemCategoryVo() : new CategoryVo();
        vo.setId(category.getId());
        vo.setMrn(category.getMrn());
        vo.setActive(category.isActive());

        if (compFilter.includeParent() && category.getParent() != null) {
            vo.setParent(toVo(category.getParent(), clz, compFilter));
        } else if (compFilter.includeParentId() && category.getParent() != null) {
            CategoryVo parent = new CategoryVo();
            parent.setId(category.getParent().getId());
            vo.setParent(parent);
        }

        if (!category.getDescs().isEmpty()) {
            vo.setDescs(category.getDescs(filter).stream()
                    .map(this::toDescVo)
                    .collect(Collectors.toList()));
        }

        if (vo instanceof SystemCategoryVo sysCategory) {

            sysCategory.setType(category.getType());
            sysCategory.setSiblingSortOrder(category.getSiblingSortOrder());

            if (compFilter.includeChildren()) {
                category.getChildren().forEach(child ->
                        sysCategory.checkCreateChildren().add(toVo(child, SystemCategoryVo.class, compFilter)));
            }

            if (!category.getEditorFields().isEmpty()) {
                sysCategory.setEditorFields(new ArrayList<>(category.getEditorFields()));
            }

            sysCategory.setAtonFilter(category.getAtonFilter());
            sysCategory.setDomains(category.getDomains().stream()
                    .map(domainMapper::toVo)
                    .collect(Collectors.toList()));
            sysCategory.getStdTemplateFields().addAll(category.getStdTemplateFields());
            if (!category.getTemplateParams().isEmpty()) {
                sysCategory.setTemplateParams(category.getTemplateParams().stream()
                        .map(p -> toTemplateParamVo(p, compFilter))
                        .collect(Collectors.toList()));
            }
            sysCategory.getScriptResourcePaths().addAll(category.getScriptResourcePaths());
            sysCategory.setMessageId(category.getMessageId());
        }

        return clz.cast(vo);
    }

    /** Maps a single {@link CategoryDesc} to its value object. */
    private CategoryDescVo toDescVo(CategoryDesc desc) {
        CategoryDescVo vo = new CategoryDescVo();
        vo.setLang(desc.getLang());
        vo.setName(desc.getName());
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

    /** Creates a new {@link Category} entity from the given value object. */
    public Category toEntity(CategoryVo vo, DataFilter filter) {
        if (vo == null) {
            return null;
        }
        Category category = new Category();
        updateEntity(category, vo, filter);
        return category;
    }

    /** Updates an existing {@link Category} entity from the given value object. */
    public void updateEntity(Category category, CategoryVo vo, DataFilter filter) {

        DataFilter compFilter = filter.forComponent(Category.class);

        category.setId(vo.getId());
        category.setMrn(vo.getMrn());
        category.setActive(vo.isActive());

        if (compFilter.includeParent() && vo.getParent() != null) {
            category.setParent(toEntity(vo.getParent(), filter));
        }

        if (vo.getDescs() != null) {
            vo.getDescs().forEach(desc -> category.createDesc(desc.getLang()).setName(desc.getName()));
        }

        if (vo instanceof SystemCategoryVo sysCategory) {

            category.setType(sysCategory.getType());
            category.setSiblingSortOrder(sysCategory.getSiblingSortOrder() == null ? 0 : sysCategory.getSiblingSortOrder());

            if (compFilter.includeChildren() && sysCategory.getChildren() != null) {
                sysCategory.getChildren().stream()
                        .map(c -> toEntity(c, filter))
                        .forEach(category::addChild);
            }
            if (sysCategory.getEditorFields() != null) {
                category.getEditorFields().addAll(sysCategory.getEditorFields());
            }
            category.setAtonFilter(sysCategory.getAtonFilter());
            sysCategory.getScriptResourcePaths().stream()
                    .filter(p -> ScriptResource.path2type(p) != null)
                    .forEach(p -> category.getScriptResourcePaths().add(p));
            category.setMessageId(sysCategory.getMessageId());
            if (!sysCategory.getDomains().isEmpty()) {
                category.setDomains(sysCategory.getDomains().stream()
                        .map(domainMapper::toEntity)
                        .collect(Collectors.toList()));
            }
            if (sysCategory.getStdTemplateFields() != null) {
                category.getStdTemplateFields().addAll(sysCategory.getStdTemplateFields());
            }
            if (sysCategory.getTemplateParams() != null) {
                category.setTemplateParams(sysCategory.getTemplateParams().stream()
                        .map(this::toTemplateParamEntity)
                        .collect(Collectors.toList()));
            }
        }
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
