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
package org.niord.core.user;

import org.niord.core.user.vo.UserVo;
import org.springframework.stereotype.Component;

/**
 * Maps between the {@link User} entity and {@link UserVo}.
 *
 * <p>Replaces the {@code toVo()} method and the {@code copyUser(UserVo)} update logic previously
 * embedded in the entity. {@code UserVo} is single-tier (no System* subclass, no DataFilter).
 */
@Component
public class UserMapper {

    /** Converts the entity to a value object. */
    public UserVo toVo(User user) {
        if (user == null) {
            return null;
        }
        UserVo vo = new UserVo();
        vo.setUsername(user.getUsername());
        vo.setEmail(user.getEmail());
        vo.setFirstName(user.getFirstName());
        vo.setLastName(user.getLastName());
        vo.setLanguage(user.getLanguage());
        return vo;
    }

    /** Creates a new {@link User} entity from the given value object. */
    public User toEntity(UserVo vo) {
        if (vo == null) {
            return null;
        }
        User user = new User();
        updateEntity(user, vo);
        return user;
    }

    /** Updates an existing {@link User} entity from the given value object (mirrors {@code copyUser}). */
    public void updateEntity(User user, UserVo vo) {
        user.setUsername(vo.getUsername());
        user.setEmail(vo.getEmail());
        user.setFirstName(vo.getFirstName());
        user.setLastName(vo.getLastName());
        user.setLanguage(vo.getLanguage());
    }
}
