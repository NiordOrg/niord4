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

import org.niord.core.message.vo.CommentVo;
import org.niord.core.user.User;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Maps {@link Comment} entities to/from {@link CommentVo} value objects.
 *
 * The conversion logic is copied verbatim from the entity's toVo(...) methods
 * and its conversion constructor.
 */
@Component
public class CommentMapper {

    /** Converts this entity to a value object */
    public CommentVo toVo(Comment comment) {
        if (comment == null) {
            return null;
        }
        CommentVo vo = new CommentVo();
        vo.setId(comment.getId());
        vo.setUser(comment.getUser().getName());
        vo.setCreated(comment.getCreated());
        vo.setAcknowledgedBy(comment.getAcknowledgedBy() != null ? comment.getAcknowledgedBy().getName() : null);
        vo.setAcknowledgeDate(comment.getAcknowledgeDate());
        vo.setComment(comment.getComment());
        if (!comment.getEmailAddresses().isEmpty()) {
            vo.setEmailAddresses(new ArrayList<>(comment.getEmailAddresses()));
        }
        return vo;
    }

    /** Converts this entity to a value object. Also flags if the user is the author of the comment */
    public CommentVo toVo(Comment comment, User user) {
        if (comment == null) {
            return null;
        }
        CommentVo vo = toVo(comment);
        vo.setOwnComment(user != null && Objects.equals(user.getId(), comment.getUser().getId()));
        return vo;
    }

    /**
     * Creates a new {@link Comment} entity from a value object.
     * Mirrors the entity's {@code Comment(CommentVo, Message, User)} constructor.
     */
    public Comment toEntity(CommentVo vo, Message message, User user) {
        if (vo == null) {
            return null;
        }
        Comment comment = new Comment();
        updateEntity(comment, vo, message, user);
        return comment;
    }

    /**
     * Updates an existing {@link Comment} entity from a value object.
     * Mirrors the entity's {@code Comment(CommentVo, Message, User)} constructor.
     */
    public void updateEntity(Comment comment, CommentVo vo, Message message, User user) {
        comment.setId(vo.getId());
        comment.setMessage(message);
        comment.setUser(user);
        comment.setComment(vo.getComment());
        if (vo.getEmailAddresses() != null) {
            comment.getEmailAddresses().addAll(vo.getEmailAddresses());
        }
    }
}
