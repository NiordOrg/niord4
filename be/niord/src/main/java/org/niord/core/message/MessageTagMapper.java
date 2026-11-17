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

import org.niord.core.message.vo.MessageTagVo;
import org.springframework.stereotype.Component;

/**
 * Converts {@link MessageTag} entities to and from {@link MessageTagVo} value objects.
 * <p>
 * The conversion logic is copied verbatim from the entity's {@code toVo()} method and its
 * {@code MessageTag(MessageTagVo)} conversion constructor.
 */
@Component
public class MessageTagMapper {

    /** Converts the entity to a value object */
    public MessageTagVo toVo(MessageTag messageTag) {
        if (messageTag == null) {
            return null;
        }
        MessageTagVo tag = new MessageTagVo();
        tag.setTagId(messageTag.getTagId());
        tag.setName(messageTag.getName());
        tag.setCreated(messageTag.getCreated());
        tag.setExpiryDate(messageTag.getExpiryDate());
        tag.setType(messageTag.getType());
        tag.setLocked(messageTag.isLocked());
        tag.setMessageCount(messageTag.getMessageCount());
        return tag;
    }

    /** Converts the value object to a new entity */
    public MessageTag toEntity(MessageTagVo vo) {
        if (vo == null) {
            return null;
        }
        MessageTag messageTag = new MessageTag();
        updateEntity(messageTag, vo);
        return messageTag;
    }

    /** Updates the entity in-place from the value object */
    public void updateEntity(MessageTag messageTag, MessageTagVo vo) {
        if (messageTag == null || vo == null) {
            return;
        }
        messageTag.setTagId(vo.getTagId());
        messageTag.setName(vo.getName());
        messageTag.setType(vo.getType());
        messageTag.setExpiryDate(vo.getExpiryDate());
        messageTag.setLocked(vo.isLocked());
    }
}
