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

package org.niord.core.mail;

import java.io.IOException;
import java.util.stream.Collectors;

import org.niord.core.mail.vo.ScheduledMailRecipientVo;
import org.niord.core.mail.vo.ScheduledMailVo;
import org.niord.model.DataFilter;
import org.springframework.stereotype.Component;

/**
 * Maps {@link ScheduledMail} entities to/from their {@link ScheduledMailVo} value objects.
 *
 * The conversion logic is copied verbatim from the entity's {@code toVo(...)} method and the
 * {@link ScheduledMailRecipient#toVo()} method, preserving the same field order and the same
 * {@link DataFilter} checks.
 */
@Component
public class ScheduledMailMapper {

    /** Converts the entity to a value object using a fully-open data filter */
    public ScheduledMailVo toVo(ScheduledMail mail) {
        return toVo(mail, DataFilter.get().fields(DataFilter.ALL));
    }

    /** Converts the entity to a value object */
    public ScheduledMailVo toVo(ScheduledMail mailEntity, DataFilter filter) {
        if (mailEntity == null) {
            return null;
        }

        DataFilter compFilter = filter.forComponent(ScheduledMail.class);

        ScheduledMailVo mail = new ScheduledMailVo();
        mail.setId(mailEntity.getId());
        mail.setCreated(mailEntity.getCreated());
        mail.setSendDate(mailEntity.getSendDate());
        mail.setStatus(mailEntity.getStatus());
        mail.setSender(mailEntity.getSender());
        mail.setSubject(mailEntity.getSubject());
        mail.setAttempts(mailEntity.getAttempts());
        mail.setLastError(mailEntity.getLastError());
        if (!mailEntity.getRecipients().isEmpty()) {
            mail.setRecipients(mailEntity.getRecipients().stream()
                .map(this::toVo)
                .collect(Collectors.toList()));
        }
        if (compFilter.includeDetails()) {
            try {
                mail.setContents(mailEntity.getHtmlContents());
            } catch (IOException ignored) {
            }
        }
        return mail;
    }

    /** Converts an owned recipient child to a value object */
    private ScheduledMailRecipientVo toVo(ScheduledMailRecipient recipientEntity) {
        ScheduledMailRecipientVo recipient = new ScheduledMailRecipientVo();
        recipient.setAddress(recipientEntity.getAddress());
        recipient.setRecipientType(recipientEntity.getRecipientType());
        return recipient;
    }

    /** Creates a new entity from the value object */
    public ScheduledMail toEntity(ScheduledMailVo vo) {
        if (vo == null) {
            return null;
        }
        ScheduledMail mail = new ScheduledMail();
        updateEntity(mail, vo);
        return mail;
    }

    /** Updates the given entity in-place from the value object */
    public void updateEntity(ScheduledMail mail, ScheduledMailVo vo) {
        if (mail == null || vo == null) {
            return;
        }
        mail.setId(vo.getId());
        mail.setCreated(vo.getCreated());
        mail.setSendDate(vo.getSendDate());
        mail.setStatus(vo.getStatus());
        mail.setSender(vo.getSender());
        mail.setSubject(vo.getSubject());
        mail.setAttempts(vo.getAttempts());
        mail.setLastError(vo.getLastError());
        if (vo.getRecipients() != null) {
            mail.getRecipients().clear();
            vo.getRecipients().forEach(r -> mail.addRecipient(toEntity(r)));
        }
        if (vo.getContents() != null) {
            try {
                mail.setHtmlContents(vo.getContents());
            } catch (IOException ignored) {
            }
        }
    }

    /** Creates a new owned recipient child entity from the value object */
    private ScheduledMailRecipient toEntity(ScheduledMailRecipientVo vo) {
        return new ScheduledMailRecipient(vo.getRecipientType(), vo.getAddress());
    }
}
