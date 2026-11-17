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

import org.niord.core.message.vo.MessageHistoryVo;
import org.niord.core.util.GzipUtils;
import org.springframework.stereotype.Component;

/**
 * Maps between the {@link MessageHistory} entity and {@link MessageHistoryVo}.
 *
 * <p>Replaces the {@code toVo()} method that was previously embedded in the entity.
 */
@Component
public class MessageHistoryMapper {

    /** Converts the entity to a value object. */
    public MessageHistoryVo toVo(MessageHistory history) {
        if (history == null) {
            return null;
        }
        MessageHistoryVo vo = new MessageHistoryVo();
        vo.setMessageId(history.getId());
        vo.setStatus(history.getStatus());
        if (history.getUser() != null) {
            vo.setUser(history.getUser().getName());
        }
        vo.setVersion(history.getVersion());
        vo.setCreated(history.getCreated());
        vo.setSnapshot(GzipUtils.decompressStringIgnoreError(history.getSnapshot()));

        return vo;
    }

    /** Creates a new {@link MessageHistory} entity from the given value object. */
    public MessageHistory toEntity(MessageHistoryVo vo) {
        if (vo == null) {
            return null;
        }
        MessageHistory history = new MessageHistory();
        updateEntity(history, vo);
        return history;
    }

    /** Updates the given {@link MessageHistory} entity from the value object. */
    public void updateEntity(MessageHistory history, MessageHistoryVo vo) {
        history.setStatus(vo.getStatus());
        history.setVersion(vo.getVersion());
        history.setCreated(vo.getCreated());
        history.compressSnapshot(vo.getSnapshot());
    }
}
