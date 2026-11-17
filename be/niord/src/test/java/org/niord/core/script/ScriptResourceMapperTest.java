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

import org.junit.jupiter.api.Test;
import org.niord.core.script.vo.ScriptResourceHistoryVo;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.core.user.User;
import org.niord.core.util.GzipUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ScriptResourceMapper}.
 */
public class ScriptResourceMapperTest {

    private final ScriptResourceMapper mapper = new ScriptResourceMapper();

    /** A fully populated ScriptResource maps every field to the VO. */
    @Test
    public void toVoCopiesAllFields() {
        ScriptResource resource = new ScriptResource();
        resource.setId(42);
        resource.setPath("templates/standard.ftl");
        resource.setContent("<#-- body -->");
        resource.setType(ScriptResource.Type.FM);

        ScriptResourceVo vo = mapper.toVo(resource);

        assertEquals(Integer.valueOf(42), vo.getId());
        assertEquals("templates/standard.ftl", vo.getPath());
        assertEquals("<#-- body -->", vo.getContent());
        assertEquals(ScriptResource.Type.FM, vo.getType());
    }

    /** A null entity yields a null VO. */
    @Test
    public void toVoNullEntityReturnsNull() {
        assertNull(mapper.toVo((ScriptResource) null));
    }

    /**
     * toEntity must derive the type from the path file extension via updateType(),
     * overriding whatever type the VO carries (mirrors the entity ctor's updateType() call).
     */
    @Test
    public void toEntityDerivesTypeFromPathExtension() {
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setId(7);
        vo.setPath("scripts/helper.js");
        vo.setContent("var x = 1;");
        // VO claims FM, but the .js path must win after updateType()
        vo.setType(ScriptResource.Type.FM);

        ScriptResource entity = mapper.toEntity(vo);

        assertEquals(Integer.valueOf(7), entity.getId());
        assertEquals("scripts/helper.js", entity.getPath());
        assertEquals("var x = 1;", entity.getContent());
        assertEquals(ScriptResource.Type.JS, entity.getType());
    }

    /** A path without a known extension resets the type to null in updateType(). */
    @Test
    public void toEntityUnknownExtensionResetsTypeToNull() {
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setPath("notes/readme.txt");
        vo.setType(ScriptResource.Type.FM);

        ScriptResource entity = mapper.toEntity(vo);

        assertEquals("notes/readme.txt", entity.getPath());
        assertNull(entity.getType());
    }

    /** A null VO yields a null entity. */
    @Test
    public void toEntityNullVoReturnsNull() {
        assertNull(mapper.toEntity(null));
    }

    /** A VO -> entity -> VO round-trip preserves all fields for a consistent (.ftl) path. */
    @Test
    public void roundTripPreservesFields() {
        ScriptResourceVo source = new ScriptResourceVo();
        source.setId(99);
        source.setPath("templates/mail.ftl");
        source.setContent("Hello, ${name}");
        source.setType(ScriptResource.Type.FM);

        ScriptResourceVo result = mapper.toVo(mapper.toEntity(source));

        assertEquals(source.getId(), result.getId());
        assertEquals(source.getPath(), result.getPath());
        assertEquals(source.getContent(), result.getContent());
        assertEquals(ScriptResource.Type.FM, result.getType());
    }

    /** updateEntity overwrites an existing entity in-place. */
    @Test
    public void updateEntityOverwritesInPlace() {
        ScriptResource entity = new ScriptResource();
        entity.setId(1);
        entity.setPath("old/path.ftl");
        entity.setContent("old");
        entity.setType(ScriptResource.Type.FM);

        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setId(2);
        vo.setPath("new/path.js");
        vo.setContent("new");

        mapper.updateEntity(entity, vo);

        assertEquals(Integer.valueOf(2), entity.getId());
        assertEquals("new/path.js", entity.getPath());
        assertEquals("new", entity.getContent());
        assertEquals(ScriptResource.Type.JS, entity.getType());
    }

    /** The owned history child maps the user's name, version, created and decompressed snapshot. */
    @Test
    public void historyToVoCopiesAllFieldsAndDecompressesSnapshot() {
        User user = new User();
        user.setFirstName("Jens");
        user.setLastName("Hansen");

        Date created = new Date(1_500_000_000_000L);

        ScriptResourceHistory history = new ScriptResourceHistory();
        history.setId(11);
        history.setUser(user);
        history.setVersion(3);
        history.setCreated(created);
        history.setSnapshot(GzipUtils.compressStringIgnoreError("{\"path\":\"a.ftl\"}"));

        ScriptResourceHistoryVo vo = mapper.toVo(history);

        assertEquals(Integer.valueOf(11), vo.getTemplateId());
        assertEquals("Jens Hansen", vo.getUser());
        assertEquals(3, vo.getVersion());
        assertEquals(created, vo.getCreated());
        assertEquals("{\"path\":\"a.ftl\"}", vo.getSnapshot());
    }

    /** A history without a user leaves the VO user field null (null guard). */
    @Test
    public void historyToVoNullUserLeavesUserNull() {
        ScriptResourceHistory history = new ScriptResourceHistory();
        history.setId(5);
        history.setVersion(1);
        history.setCreated(new Date());
        // no user, no snapshot

        ScriptResourceHistoryVo vo = mapper.toVo(history);

        assertEquals(Integer.valueOf(5), vo.getTemplateId());
        assertNull(vo.getUser());
        assertEquals(1, vo.getVersion());
        assertNull(vo.getSnapshot());
    }

    /** A null history entity yields a null VO. */
    @Test
    public void historyToVoNullEntityReturnsNull() {
        assertNull(mapper.toVo((ScriptResourceHistory) null));
    }
}
