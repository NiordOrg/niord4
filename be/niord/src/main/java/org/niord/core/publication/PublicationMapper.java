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
package org.niord.core.publication;

import org.niord.core.domain.DomainMapper;
import org.niord.core.message.MessageTagMapper;
import org.niord.core.publication.vo.SystemPublicationVo;
import org.niord.model.DataFilter;
import org.niord.model.publication.PublicationCategoryDescVo;
import org.niord.model.publication.PublicationCategoryVo;
import org.niord.model.publication.PublicationDescVo;
import org.niord.model.publication.PublicationVo;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Maps between the {@link Publication} aggregate and {@link PublicationVo}/{@link SystemPublicationVo}.
 *
 * <p>Replaces the {@code toVo()}/{@code updatePublication()} methods and the
 * {@code Publication(PublicationVo)} conversion constructor that were previously embedded in the
 * {@link Publication} entity. Conversion now lives here as a stateless, dependency-injected bean. The
 * behaviour is kept identical to the entity methods (which still exist): the two-tier
 * {@code PublicationVo}/{@code SystemPublicationVo} projection, the {@link DataFilter} semantics, the
 * {@code revision+1} bump on the System projection, the map clear-then-putAll side effects and the
 * desc-by-language merge are all preserved.
 *
 * <p>The nested first-class entities ({@code Domain}, {@code MessageTag}) are delegated to their
 * dedicated mappers. The self-referential {@code template} {@link Publication} is converted by
 * recursing into this mapper. The owned {@code PublicationCategory} and its descriptions, plus the
 * {@code PublicationDesc} children, are mapped inline as private helpers, copying each child entity's
 * own conversion logic.
 */
@Component
public class PublicationMapper {

    private final DomainMapper domainMapper;

    private final MessageTagMapper messageTagMapper;

    PublicationMapper(DomainMapper domainMapper, MessageTagMapper messageTagMapper) {
        this.domainMapper = domainMapper;
        this.messageTagMapper = messageTagMapper;
    }

    /** Converts the entity to a value object, honouring the data filter. */
    public <P extends PublicationVo> P toVo(Publication publication, Class<P> clz, DataFilter dataFilter) {
        if (publication == null) {
            return null;
        }

        DataFilter compFilter = dataFilter.forComponent(Publication.class);

        PublicationVo vo = SystemPublicationVo.class.isAssignableFrom(clz) ? new SystemPublicationVo() : new PublicationVo();
        vo.setPublicationId(publication.getPublicationId());
        vo.setCreated(publication.getCreated());
        vo.setUpdated(publication.getUpdated());
        vo.setType(publication.getType());
        vo.setCategory(publication.getCategory() != null ? toCategoryVo(publication.getCategory(), dataFilter) : null);
        vo.setPublishDateFrom(publication.getPublishDateFrom());
        vo.setPublishDateTo(publication.getPublishDateTo());

        if (!publication.getDescs().isEmpty()) {
            vo.setDescs(publication.getDescs(compFilter).stream()
                    .map(this::toDescVo)
                    .filter(PublicationDescVo::descDefined)
                    .collect(Collectors.toList()));
        }

        if (vo instanceof SystemPublicationVo sysPub) {
            sysPub.setRevision(publication.getRevision() + 1); // NB: Increase revision number
            sysPub.setStatus(publication.getStatus());
            sysPub.setMainType(publication.getMainType());
            sysPub.setTemplate(publication.getTemplate() != null
                    ? toVo(publication.getTemplate(), SystemPublicationVo.class, dataFilter) : null);
            sysPub.setDomain(publication.getDomain() != null ? domainMapper.toVo(publication.getDomain()) : null);
            sysPub.setEdition(publication.getEdition());
            sysPub.setMessageTagFormat(publication.getMessageTagFormat());
            sysPub.setMessageTagFilter(publication.getMessageTagFilter());
            sysPub.setMessageTag(publication.getMessageTag() != null ? messageTagMapper.toVo(publication.getMessageTag()) : null);
            sysPub.setPeriodicalType(publication.getPeriodicalType());
            sysPub.setMessagePublication(publication.getMessagePublication());
            sysPub.setLanguageSpecific(publication.isLanguageSpecific());
            sysPub.setRepoPath(publication.getRepoPath());

            if (!publication.getPrintSettings().isEmpty()) {
                sysPub.setPrintSettings(new HashMap<>(publication.getPrintSettings()));
            }
            if (!publication.getReportParams().isEmpty()) {
                sysPub.setReportParams(new HashMap<>(publication.getReportParams()));
            }
        }

        return clz.cast(vo);
    }

    /** Maps a single {@link PublicationDesc} to its value object (copied from {@code PublicationDesc.toVo}). */
    private PublicationDescVo toDescVo(PublicationDesc desc) {
        PublicationDescVo vo = new PublicationDescVo();
        vo.setLang(desc.getLang());
        vo.setTitle(desc.getTitle());
        vo.setTitleFormat(desc.getTitleFormat());
        vo.setLink(desc.getLink());
        vo.setFileName(desc.getFileName());
        vo.setMessagePublicationFormat(desc.getMessagePublicationFormat());
        return vo;
    }

    /** Maps a single {@link PublicationCategory} to its value object (copied from {@code PublicationCategory.toVo}). */
    private PublicationCategoryVo toCategoryVo(PublicationCategory category, DataFilter dataFilter) {
        PublicationCategoryVo vo = new PublicationCategoryVo();
        vo.setCategoryId(category.getCategoryId());
        vo.setPriority(category.getPriority());
        vo.setPublish(category.isPublish());

        if (!category.getDescs().isEmpty()) {
            vo.setDescs(category.getDescs(dataFilter).stream()
                    .map(this::toCategoryDescVo)
                    .collect(Collectors.toList()));
        }
        return vo;
    }

    /** Maps a single {@link PublicationCategoryDesc} to its value object (copied from {@code PublicationCategoryDesc.toVo}). */
    private PublicationCategoryDescVo toCategoryDescVo(PublicationCategoryDesc desc) {
        PublicationCategoryDescVo vo = new PublicationCategoryDescVo();
        vo.setLang(desc.getLang());
        vo.setName(desc.getName());
        vo.setDescription(desc.getDescription());
        return vo;
    }

    /** Creates a new {@link Publication} entity from the given value object (copied from the {@code Publication(PublicationVo)} ctor). */
    public Publication toEntity(PublicationVo publication) {
        if (publication == null) {
            return null;
        }
        Publication entity = new Publication();
        entity.setPublicationId(publication.getPublicationId());
        entity.setCreated(publication.getCreated());
        entity.setUpdated(publication.getUpdated());
        entity.setType(publication.getType());
        entity.setCategory(publication.getCategory() != null ? toCategoryEntity(publication.getCategory()) : null);
        entity.setPublishDateFrom(publication.getPublishDateFrom());
        entity.setPublishDateTo(publication.getPublishDateTo());

        if (publication.getDescs() != null) {
            publication.getDescs().stream()
                    .filter(PublicationDescVo::descDefined)
                    .forEach(desc -> entity.checkCreateDesc(desc.getLang()).update(desc));
        }

        if (publication instanceof SystemPublicationVo sysPub) {
            entity.setRevision(sysPub.getRevision());
            entity.setStatus(sysPub.getStatus());
            entity.setMainType(sysPub.getMainType());
            entity.setTemplate(sysPub.getTemplate() != null ? toEntity(sysPub.getTemplate()) : null);
            entity.setDomain(sysPub.getDomain() != null ? domainMapper.toEntity(sysPub.getDomain()) : null);
            entity.setEdition(sysPub.getEdition());
            entity.setMessageTagFormat(sysPub.getMessageTagFormat());
            entity.setMessageTagFilter(sysPub.getMessageTagFilter());
            entity.setMessageTag(sysPub.getMessageTag() != null ? messageTagMapper.toEntity(sysPub.getMessageTag()) : null);
            entity.setPeriodicalType(sysPub.getPeriodicalType());
            entity.setMessagePublication(sysPub.getMessagePublication());
            entity.setLanguageSpecific(sysPub.isLanguageSpecific());
            if (sysPub.getPrintSettings() != null) {
                entity.getPrintSettings().putAll(sysPub.getPrintSettings());
            }
            if (sysPub.getReportParams() != null) {
                entity.getReportParams().putAll(sysPub.getReportParams());
            }
        }
        return entity;
    }

    /** Updates an existing {@link Publication} entity from another publication entity (entity-to-entity copy, not a VO update; from {@code Publication.updatePublication}). */
    public void updateFrom(Publication entity, Publication publication) {
        entity.setPublicationId(publication.getPublicationId());
        entity.setCreated(publication.getCreated());
        entity.setUpdated(publication.getUpdated());
        entity.setRevision(publication.getRevision());
        entity.setMainType(publication.getMainType());
        entity.setStatus(publication.getStatus());
        entity.setTemplate(publication.getTemplate());
        entity.setType(publication.getType());
        entity.setCategory(publication.getCategory());
        entity.setDomain(publication.getDomain());
        entity.setEdition(publication.getEdition());
        entity.setMessageTagFormat(publication.getMessageTagFormat());
        entity.setMessageTagFilter(publication.getMessageTagFilter());
        entity.setMessageTag(publication.getMessageTag());
        entity.setPeriodicalType(publication.getPeriodicalType());
        entity.setPublishDateFrom(publication.getPublishDateFrom());
        entity.setPublishDateTo(publication.getPublishDateTo());
        entity.setMessagePublication(publication.getMessagePublication());
        entity.setLanguageSpecific(publication.isLanguageSpecific());
        entity.getPrintSettings().clear();
        entity.getPrintSettings().putAll(publication.getPrintSettings());
        entity.getReportParams().clear();
        entity.getReportParams().putAll(publication.getReportParams());
        // Merged in place, not cleared and re-added: see Publication.mergeDescsFrom
        entity.mergeDescsFrom(publication.getDescs());
    }

    /** Creates a new {@link PublicationCategory} entity from its value object (copied from the {@code PublicationCategory(PublicationCategoryVo)} ctor). */
    private PublicationCategory toCategoryEntity(PublicationCategoryVo category) {
        PublicationCategory entity = new PublicationCategory();
        entity.setCategoryId(category.getCategoryId());
        entity.setPriority(category.getPriority() != null ? category.getPriority() : 0);
        entity.setPublish(category.getPublish() != null ? category.getPublish() : false);

        if (category.getDescs() != null) {
            category.getDescs().stream()
                    .filter(PublicationCategoryDescVo::descDefined)
                    .forEach(desc -> entity.addDesc(toCategoryDescEntity(desc)));
        }
        return entity;
    }

    /** Creates a new {@link PublicationCategoryDesc} entity from its value object (copied from the {@code PublicationCategoryDesc(PublicationCategoryDescVo)} ctor). */
    private PublicationCategoryDesc toCategoryDescEntity(PublicationCategoryDescVo desc) {
        PublicationCategoryDesc entity = new PublicationCategoryDesc();
        entity.setLang(desc.getLang());
        entity.setName(desc.getName());
        entity.setDescription(desc.getDescription());
        return entity;
    }
}
