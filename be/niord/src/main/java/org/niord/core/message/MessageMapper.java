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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.area.AreaMapper;
import org.niord.core.category.CategoryMapper;
import org.niord.core.chart.ChartMapper;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.GeoJsonUtils;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.model.DescEntity;
import org.niord.core.promulgation.BaseMessagePromulgation;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.util.UidUtils;
import org.niord.model.DataFilter;
import org.niord.model.message.AreaVo;
import org.niord.model.message.AttachmentDescVo;
import org.niord.model.message.AttachmentVo;
import org.niord.model.message.CategoryVo;
import org.niord.model.message.ChartVo;
import org.niord.model.message.DateIntervalVo;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.MessageSeriesVo;
import org.niord.model.message.MessageVo;
import org.niord.model.message.ReferenceDescVo;
import org.niord.model.message.ReferenceVo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps between the {@link Message} aggregate and {@link MessageVo}/{@link SystemMessageVo}.
 *
 * <p>Replaces the {@code toVo()}/{@code updateMessage()} methods and the {@code Message(MessageVo)}
 * conversion constructor that were previously embedded in the {@link Message} entity. Conversion now
 * lives here as a stateless, dependency-injected bean. The behaviour is kept byte-for-byte identical to
 * the entity methods (which still exist): the two-tier {@code MessageVo}/{@code SystemMessageVo}
 * projection, the {@link DataFilter} semantics, the inbound collection-clear-then-re-add side effects,
 * the input-VO/entity-list mutations and the {@code repoPath}/{@code revision+1}/aggregate-date
 * recomputation are all preserved.
 *
 * <p>Nested first-class entities ({@code Area}, {@code Category}, {@code Chart}, {@code MessageSeries})
 * are delegated to their dedicated mappers. The owned children of the aggregate ({@code Reference},
 * {@code MessageDesc}, {@code MessagePart}, {@code DateInterval}, {@code Attachment}, and their
 * descriptions) are mapped inline as private helpers, copying each child entity's own conversion logic.
 */
@Component
public class MessageMapper {

    private final AreaMapper areaMapper;

    private final CategoryMapper categoryMapper;

    private final ChartMapper chartMapper;

    private final MessageSeriesMapper messageSeriesMapper;

    MessageMapper(AreaMapper areaMapper, CategoryMapper categoryMapper, ChartMapper chartMapper, MessageSeriesMapper messageSeriesMapper) {
        this.areaMapper = areaMapper;
        this.categoryMapper = categoryMapper;
        this.chartMapper = chartMapper;
        this.messageSeriesMapper = messageSeriesMapper;
    }

    /** Converts the entity to a value object, honouring the data filter. */
    public <M extends MessageVo> M toVo(Message message, Class<M> clz, DataFilter filter) {
        if (message == null) {
            return null;
        }

        MessageVo vo = SystemMessageVo.class.isAssignableFrom(clz) ? new SystemMessageVo() : new MessageVo();
        DataFilter compFilter = filter.forComponent(Message.class);

        vo.setId(message.getUid());
        vo.setNumber(message.getNumber());
        vo.setShortId(message.getShortId());
        vo.setStatus(message.getStatus());
        vo.setMainType(message.getMainType());
        vo.setType(message.getType());

        if (compFilter.includeDetails()) {
            vo.setCreated(message.getCreated());
            vo.setUpdated(message.getUpdated());
            if (message.getMessageSeries() != null) {
                vo.setMessageSeries(messageSeriesMapper.toVo(message.getMessageSeries(), MessageSeriesVo.class, filter));
            }
            message.getAreas().forEach(a -> vo.checkCreateAreas().add(areaMapper.toVo(a, AreaVo.class, filter)));
            message.getCategories().forEach(c -> vo.checkCreateCategories().add(categoryMapper.toVo(c, CategoryVo.class, filter)));
            message.getCharts().forEach(c -> vo.checkCreateCharts().add(chartMapper.toVo(c, ChartVo.class, filter)));
            vo.setHorizontalDatum(message.getHorizontalDatum());
            vo.setPublishDateFrom(message.getPublishDateFrom());
            vo.setPublishDateTo(message.getPublishDateTo());
            vo.setFollowUpDate(message.getFollowUpDate());
            message.getReferences().forEach(r -> vo.checkCreateReferences().add(toReferenceVo(r, filter)));
            vo.setOriginalInformation(message.getOriginalInformation());
            message.getAttachments().forEach(att -> vo.checkCreateAttachments().add(toAttachmentVo(att, filter)));
        }
        if (compFilter.includeDetails() || compFilter.includeGeometry()) {
            // indexNo is the part's place in the message (the list position), as in Message.toVo
            List<MessagePart> parts = message.getParts();
            for (int i = 0; i < parts.size(); i++) {
                MessagePartVo part = toMessagePartVo(parts.get(i), compFilter);
                part.setIndexNo(i);
                vo.checkCreateParts().add(part);
            }
        }
        if (compFilter.anyOfFields(DataFilter.DETAILS, "MessageDesc.title")) {
            message.getDescs(compFilter).forEach(desc -> vo.checkCreateDescs().add(toMessageDescVo(desc, compFilter)));
        }

        if (vo instanceof SystemMessageVo systemMessage) {
            systemMessage.setKind(message.getKind());
            systemMessage.setRevision(message.getRevision() + 1); // NB: Increase revision number
            systemMessage.setYear(message.getYear());
            systemMessage.setRepoPath(message.getRepoPath());
            systemMessage.setThumbnailPath(message.getThumbnailPath());
            systemMessage.setAutoTitle(message.isAutoTitle());
            systemMessage.setSeparatePage(message.getSeparatePage());

            // Check if we need to include promulgations
            // TODO: route via a PromulgationMapper once it exists
            if (!message.getPromulgations().isEmpty() && compFilter.includeField("promulgations")) {
                systemMessage.setPromulgations(message.getPromulgations().stream()
                    .map(BaseMessagePromulgation::toVo)
                    .collect(Collectors.toList()));
            }

            systemMessage.sort(filter.getLang());

            // TODO: route via a CommentMapper once unack-comment counting moves off the entity
            long cnt = message.getComments().stream()
                    .filter(c -> c.getAcknowledgeDate() == null)
                    .count();
            systemMessage.setUnackComments((int)cnt);
        }

        return clz.cast(vo);
    }

    /** Creates a new {@link Message} entity from the given value object. */
    public Message toEntity(MessageVo messageVo, DataFilter filter) {
        if (messageVo == null) {
            return null;
        }
        Message message = new Message();
        updateEntity(message, messageVo, filter);
        return message;
    }

    /** Updates an existing {@link Message} entity from the given value object, in place. */
    public void updateEntity(Message message, MessageVo messageVo, DataFilter filter) {

        DataFilter compFilter = filter.forComponent(Message.class);

        if (messageVo.getMessageSeries() != null) {
            message.setMessageSeries(messageSeriesMapper.toEntity(messageVo.getMessageSeries()));
        }
        message.setCreated(messageVo.getCreated());
        message.setUpdated(messageVo.getUpdated());
        message.setUid(messageVo.getId());
        message.setRepoPath(UidUtils.uidToHashedFolderPath(Message.MESSAGE_REPO_FOLDER, message.getUid()));
        message.setNumber(messageVo.getNumber());
        message.setShortId(messageVo.getShortId());
        message.setMainType(messageVo.getMainType());
        message.setType(messageVo.getType());
        message.setStatus(messageVo.getStatus());
        message.getAreas().clear();
        if (messageVo.getAreas() != null) {
            messageVo.getAreas().forEach(a -> message.getAreas().add(areaMapper.toEntity(a, compFilter)));
        }
        message.getCategories().clear();
        if (messageVo.getCategories() != null) {
            messageVo.getCategories().forEach(c -> message.getCategories().add(categoryMapper.toEntity(c, compFilter)));
        }
        message.getCharts().clear();
        if (messageVo.getCharts() != null) {
            messageVo.getCharts().forEach(c -> message.getCharts().add(chartMapper.toEntity(c)));
        }
        message.setHorizontalDatum(messageVo.getHorizontalDatum());
        message.setPublishDateFrom(messageVo.getPublishDateFrom());
        message.setPublishDateTo(messageVo.getPublishDateTo());
        message.setFollowUpDate(messageVo.getFollowUpDate());
        message.getReferences().clear();
        if (messageVo.getReferences() != null) {
            messageVo.getReferences().stream()
                .filter(r -> StringUtils.isNotBlank(r.getMessageId()))
                .forEach(r -> message.addReference(toReferenceEntity(r)));
        }
        message.setOriginalInformation(messageVo.getOriginalInformation());
        if (messageVo.getDescs() != null) {
            messageVo.getDescs().forEach(desc -> message.addDesc(toMessageDescEntity(desc)));
            messageVo.getDescs().removeIf(d -> !d.descDefined());
        }
        if (messageVo.getParts() != null) {
            messageVo.getParts().forEach(part -> message.addPart(toMessagePartEntity(part)));
            message.getParts().removeIf(part -> !part.partDefined());
        }
        if (messageVo.getAttachments() != null) {
            messageVo.getAttachments().forEach(att -> message.addAttachment(toAttachmentEntity(att)));
        }

        if (messageVo instanceof SystemMessageVo sysMessage) {
            message.setAutoTitle(sysMessage.isAutoTitle() != null && sysMessage.isAutoTitle());
            message.setRevision(sysMessage.getRevision());
            message.setThumbnailPath(sysMessage.getThumbnailPath());
            message.setSeparatePage(sysMessage.getSeparatePage());
            if (sysMessage.getPromulgations() != null) {
                // TODO: route via a PromulgationMapper once it exists
                sysMessage.getPromulgations().stream()
                    .filter(BaseMessagePromulgationVo::promulgationDataDefined)
                    .forEach(p -> message.addPromulgation(p.toEntity()));
            }
        }

        message.updateAggregateEventDateInterval();
    }

    /*************************/
    /** Reference mapping   **/
    /*************************/

    /** Maps a single {@link Reference} to its value object (copied from {@code Reference.toVo}). */
    private ReferenceVo toReferenceVo(Reference reference, DataFilter filter) {
        ReferenceVo vo = new ReferenceVo();
        vo.setMessageId(reference.getMessageId());
        vo.setType(reference.getType());
        if (!reference.getDescs().isEmpty()) {
            vo.setDescs(reference.getDescs(filter).stream()
                    .map(this::toReferenceDescVo)
                    .collect(Collectors.toList()));
        }
        return vo;
    }

    /** Maps a single {@link ReferenceDesc} to its value object (copied from {@code ReferenceDesc.toVo}). */
    private ReferenceDescVo toReferenceDescVo(ReferenceDesc desc) {
        ReferenceDescVo vo = new ReferenceDescVo();
        vo.setLang(desc.getLang());
        vo.setDescription(desc.getDescription());
        return vo;
    }

    /** Creates a {@link Reference} entity from its value object (copied from the {@code Reference(ReferenceVo)} ctor). */
    private Reference toReferenceEntity(ReferenceVo vo) {
        Reference reference = new Reference();
        reference.setMessageId(vo.getMessageId());
        reference.setType(vo.getType());
        if (vo.getDescs() != null) {
            vo.getDescs().stream()
                    .filter(ReferenceDescVo::descDefined)
                    .forEach(desc -> reference.createDesc(desc.getLang()).setDescription(desc.getDescription()));
        }
        return reference;
    }

    /*************************/
    /** MessageDesc mapping **/
    /*************************/

    /** Maps a single {@link MessageDesc} to its value object (copied from {@code MessageDesc.toVo}). */
    private MessageDescVo toMessageDescVo(MessageDesc desc, DataFilter filter) {
        DataFilter compFilter = filter.forComponent(MessageDesc.class);

        MessageDescVo vo = new MessageDescVo();
        vo.setLang(desc.getLang());
        vo.setTitle(desc.getTitle());
        if (!compFilter.includeField("title")) {
            vo.setVicinity(desc.getVicinity());
            vo.setPublication(desc.getPublication());
            vo.setSource(desc.getSource());
            if (filter.userInRole("user")) {
                vo.setInternalPublication(desc.getInternalPublication());
            }
        }
        return vo;
    }

    /** Creates a {@link MessageDesc} entity from its value object (copied from the {@code MessageDesc(MessageDescVo)} ctor). */
    private MessageDesc toMessageDescEntity(MessageDescVo vo) {
        // NB: The entity constructor uses DataFilter.get() (no "title" filter), i.e. it copies all fields.
        MessageDesc desc = new MessageDesc();
        desc.setLang(vo.getLang());
        desc.setTitle(DescEntity.trunc(vo.getTitle(), 1024));
        desc.setVicinity(DescEntity.trunc(vo.getVicinity(), 256));
        desc.setPublication(DescEntity.trunc(vo.getPublication(), 2048));
        desc.setInternalPublication(DescEntity.trunc(vo.getInternalPublication(), 2048));
        desc.setSource(DescEntity.trunc(vo.getSource(), 1024));
        return desc;
    }

    /*************************/
    /** MessagePart mapping **/
    /*************************/

    /** Maps a single {@link MessagePart} to its value object (copied from {@code MessagePart.toVo}). */
    private MessagePartVo toMessagePartVo(MessagePart part, DataFilter filter) {
        DataFilter compFilter = filter.forComponent(MessagePart.class);

        MessagePartVo vo = new MessagePartVo();
        vo.setType(part.getType());
        if (compFilter.includeDetails()) {
            part.getEventDates().forEach(d -> vo.checkCreateEventDates().add(toDateIntervalVo(d)));
            part.getDescs(compFilter).forEach(desc -> vo.checkCreateDescs().add(toMessagePartDescVo(desc, compFilter)));
        }
        if (compFilter.includeGeometry() && part.getGeometry() != null) {
            vo.setGeometry(part.getGeometry().toGeoJson());
            GeoJsonUtils.setLanguage(vo.getGeometry(), compFilter.getLang(), false);
        }
        vo.setHideSubject(part.getHideSubject());
        return vo;
    }

    /** Maps a single {@link MessagePartDesc} to its value object (copied from {@code MessagePartDesc.toVo}). */
    private MessagePartDescVo toMessagePartDescVo(MessagePartDesc desc, DataFilter filter) {
        MessagePartDescVo vo = new MessagePartDescVo();
        vo.setLang(desc.getLang());
        vo.setSubject(desc.getSubject());
        vo.setDetails(desc.getDetails());
        return vo;
    }

    /** Creates a {@link MessagePart} entity from its value object (copied from the {@code MessagePart(MessagePartVo)} ctor). */
    private MessagePart toMessagePartEntity(MessagePartVo part) {
        MessagePart entity = new MessagePart();
        // NB: indexNo automatically assigned
        entity.setType(part.getType());
        if (part.getEventDates() != null) {
            part.getEventDates().stream()
                    .map(this::toDateIntervalEntity)
                    .filter(DateInterval::dateIntervalDefined)
                    .sorted()
                    .forEach(entity::addEventDates);
        }
        if (part.getGeometry() != null) {
            entity.setGeometry(FeatureCollection.fromGeoJson(part.getGeometry()));
        }
        if (part.getDescs() != null) {
            part.getDescs().stream()
                    .filter(MessagePartDescVo::descDefined)
                    .forEach(desc -> {
                        MessagePartDesc d = entity.createDesc(desc.getLang());
                        d.setSubject(desc.getSubject());
                        d.setDetails(desc.getDetails());
                    });
        }
        entity.setHideSubject(part.getHideSubject());
        return entity;
    }

    /*************************/
    /** DateInterval mapping **/
    /*************************/

    /** Maps a single {@link DateInterval} to its value object (copied from {@code DateInterval.toVo}). */
    private DateIntervalVo toDateIntervalVo(DateInterval di) {
        DateIntervalVo vo = new DateIntervalVo();
        vo.setAllDay(di.getAllDay());
        vo.setFromDate(di.getFromDate());
        vo.setToDate(di.getToDate());
        return vo;
    }

    /** Creates a {@link DateInterval} entity from its value object (copied from the {@code DateInterval(DateIntervalVo)} ctor). */
    private DateInterval toDateIntervalEntity(DateIntervalVo vo) {
        return new DateInterval(vo.getAllDay(), vo.getFromDate(), vo.getToDate());
    }

    /*************************/
    /** Attachment mapping  **/
    /*************************/

    /** Maps a single {@link Attachment} to its value object (copied from {@code Attachment.toVo}). */
    private AttachmentVo toAttachmentVo(Attachment attachment, DataFilter filter) {
        AttachmentVo vo = new AttachmentVo();
        vo.setType(attachment.getType());
        vo.setPath(attachment.getPath());
        vo.setFileName(attachment.getFileName());
        vo.setFileSize(attachment.getFileSize());
        vo.setDisplay(attachment.getDisplay());
        vo.setWidth(attachment.getWidth());
        vo.setHeight(attachment.getHeight());

        if (!attachment.getDescs().isEmpty()) {
            vo.setDescs(attachment.getDescs(filter).stream()
                    .map(this::toAttachmentDescVo)
                    .collect(Collectors.toList()));
        }
        return vo;
    }

    /** Maps a single {@link AttachmentDesc} to its value object (copied from {@code AttachmentDesc.toVo}). */
    private AttachmentDescVo toAttachmentDescVo(AttachmentDesc desc) {
        AttachmentDescVo vo = new AttachmentDescVo();
        vo.setLang(desc.getLang());
        vo.setCaption(desc.getCaption());
        return vo;
    }

    /** Creates an {@link Attachment} entity from its value object (copied from the {@code Attachment(AttachmentVo)} ctor). */
    private Attachment toAttachmentEntity(AttachmentVo attachment) {
        Attachment entity = new Attachment();
        entity.setType(attachment.getType());
        entity.setPath(attachment.getPath());
        entity.setFileName(attachment.getFileName());
        entity.setFileSize(attachment.getFileSize() == null ? 0 : attachment.getFileSize());
        entity.setDisplay(attachment.getDisplay());
        entity.setWidth(attachment.getWidth());
        entity.setHeight(attachment.getHeight());

        if (attachment.getDescs() != null) {
            attachment.getDescs().stream()
                    .filter(AttachmentDescVo::descDefined)
                    .forEach(desc -> entity.createDesc(desc.getLang()).setCaption(desc.getCaption()));
        }
        return entity;
    }

}
