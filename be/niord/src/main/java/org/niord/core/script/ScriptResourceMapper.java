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

package org.niord.core.script;

import org.niord.core.script.vo.ScriptResourceHistoryVo;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.core.util.GzipUtils;
import org.springframework.stereotype.Component;

/**
 * Dedicated mapper for converting {@link ScriptResource} (and its owned
 * {@link ScriptResourceHistory} child) entities to/from their value objects.
 *
 * <p>The conversion logic is copied verbatim from the entities' own
 * {@code toVo()} methods and conversion constructor.</p>
 */
@Component
public class ScriptResourceMapper {

    /** Converts the entity to a value object */
    public ScriptResourceVo toVo(ScriptResource scriptResource) {
        if (scriptResource == null) {
            return null;
        }
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setId(scriptResource.getId());
        vo.setType(scriptResource.getType());
        vo.setPath(scriptResource.getPath());
        vo.setContent(scriptResource.getContent());
        return vo;
    }

    /** Converts the value object to a new entity */
    public ScriptResource toEntity(ScriptResourceVo vo) {
        if (vo == null) {
            return null;
        }
        ScriptResource scriptResource = new ScriptResource();
        updateEntity(scriptResource, vo);
        return scriptResource;
    }

    /** Updates the given entity in-place from the value object */
    public void updateEntity(ScriptResource scriptResource, ScriptResourceVo vo) {
        scriptResource.setId(vo.getId());
        scriptResource.setType(vo.getType());
        scriptResource.setPath(vo.getPath());
        scriptResource.setContent(vo.getContent());
        scriptResource.updateType();
    }

    /** Converts the owned history entity to a value object */
    public ScriptResourceHistoryVo toVo(ScriptResourceHistory history) {
        if (history == null) {
            return null;
        }
        ScriptResourceHistoryVo vo = new ScriptResourceHistoryVo();
        vo.setTemplateId(history.getId());
        if (history.getUser() != null) {
            vo.setUser(history.getUser().getName());
        }
        vo.setVersion(history.getVersion());
        vo.setCreated(history.getCreated());
        vo.setSource(history.getSource());
        vo.setSnapshot(GzipUtils.decompressStringIgnoreError(history.getSnapshot()));
        return vo;
    }
}
