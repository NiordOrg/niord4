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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.niord.core.script.vo.ScriptResourceVo;

/**
 * Framework-neutral Tier-1 unit tests for the {@link ScriptResource} entity's pure logic.
 * <p>
 * Pins down the behavior that survives a future Quarkus-to-Spring migration:
 * <ul>
 *   <li>{@link ScriptResource#path2type(String)} — case-insensitive, trimming extension detection
 *       that maps {@code .js} to {@link ScriptResource.Type#JS}, {@code .ftl} to
 *       {@link ScriptResource.Type#FM}, and everything else (including missing/blank/null paths) to
 *       {@code null}.</li>
 *   <li>{@link ScriptResource#updateType()} — re-derives the {@code type} from the current {@code path}.</li>
 *   <li>The {@link ScriptResource#ScriptResource(ScriptResourceVo)} constructor — copies id/path/content
 *       and then forces the type to be derived from the path (path wins over the VO's declared type).</li>
 *   <li>{@link ScriptResource#toVo()} — round-trips id/type/path/content into a {@link ScriptResourceVo}.</li>
 * </ul>
 * These tests use no Quarkus, no database and no Freemarker — they exercise plain object logic only.
 */
public class ScriptResourceEntityTest {

    @Test
    public void path2type_jsLowerExtension_returnsJs() {
        // A plain ".js" path is classified as the JS resource type.
        Assertions.assertEquals(ScriptResource.Type.JS, ScriptResource.path2type("x.js"));
    }

    @Test
    public void path2type_jsUpperExtension_isCaseInsensitive() {
        // The extension match is case-insensitive, so ".JS" still yields JS.
        Assertions.assertEquals(ScriptResource.Type.JS, ScriptResource.path2type("X.JS"));
    }

    @Test
    public void path2type_jsPathWithSurroundingWhitespace_isTrimmed() {
        // Leading/trailing whitespace is trimmed before the extension check, so a padded ".js" yields JS.
        Assertions.assertEquals(ScriptResource.Type.JS, ScriptResource.path2type("  a/b/c.js  "));
    }

    @Test
    public void path2type_ftlLowerExtension_returnsFm() {
        // A plain ".ftl" path is classified as the FM (Freemarker) resource type.
        Assertions.assertEquals(ScriptResource.Type.FM, ScriptResource.path2type("x.ftl"));
    }

    @Test
    public void path2type_ftlUpperExtension_isCaseInsensitive() {
        // The extension match is case-insensitive, so ".FTL" still yields FM.
        Assertions.assertEquals(ScriptResource.Type.FM, ScriptResource.path2type("X.FTL"));
    }

    @Test
    public void path2type_ftlWithDirectoryPrefix_returnsFm() {
        // A directory-qualified ".ftl" path is still classified as FM (only the suffix matters).
        Assertions.assertEquals(ScriptResource.Type.FM, ScriptResource.path2type("templates/foo.ftl"));
    }

    @Test
    public void path2type_unknownExtension_returnsNull() {
        // An extension other than .js/.ftl (here ".txt") is not recognised and yields null.
        Assertions.assertNull(ScriptResource.path2type("x.txt"));
    }

    @Test
    public void path2type_noExtension_returnsNull() {
        // A path with no extension at all does not match either suffix and yields null.
        Assertions.assertNull(ScriptResource.path2type("x"));
    }

    @Test
    public void path2type_jsNotAtEnd_returnsNull() {
        // The recognised extension must be the suffix; ".js.bak" does not end with .js/.ftl, so null.
        Assertions.assertNull(ScriptResource.path2type("x.js.bak"));
    }

    @Test
    public void path2type_emptyString_returnsNull() {
        // A blank path is rejected by the isNotBlank guard and yields null.
        Assertions.assertNull(ScriptResource.path2type(""));
    }

    @Test
    public void path2type_null_returnsNull() {
        // A null path is rejected by the isNotBlank guard and yields null.
        Assertions.assertNull(ScriptResource.path2type((String) null));
    }

    @Test
    public void updateType_reDerivesTypeFromCurrentPath() {
        // updateType() recomputes type from the path: ".js" -> JS, and after switching path to ".ftl" -> FM.
        ScriptResource res = new ScriptResource();
        res.setPath("x.js");
        res.updateType();
        Assertions.assertEquals(ScriptResource.Type.JS, res.getType());

        res.setPath("y.ftl");
        res.updateType();
        Assertions.assertEquals(ScriptResource.Type.FM, res.getType());
    }

    @Test
    public void constructor_pathDerivedTypeOverridesVoType() {
        // The VO constructor derives type from the path, overriding the VO's declared type (path wins).
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setId(7);
        vo.setPath("a.ftl");
        vo.setType(ScriptResource.Type.JS);
        vo.setContent("hello");

        ScriptResource res = new ScriptResource(vo);

        Assertions.assertEquals(ScriptResource.Type.FM, res.getType());
    }

    @Test
    public void constructor_copiesIdPathAndContentFromVo() {
        // The VO constructor copies id, path and content verbatim from the value object.
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setId(42);
        vo.setPath("scripts/foo.js");
        vo.setType(ScriptResource.Type.FM);
        vo.setContent("var a = 1;");

        ScriptResource res = new ScriptResource(vo);

        Assertions.assertEquals(Integer.valueOf(42), res.getId());
        Assertions.assertEquals("scripts/foo.js", res.getPath());
        Assertions.assertEquals("var a = 1;", res.getContent());
    }

    @Test
    public void constructor_nullPathYieldsNullType() {
        // With a null path the constructor's updateType() derives a null type.
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setId(1);
        vo.setPath(null);
        vo.setType(ScriptResource.Type.JS);
        vo.setContent("ignored");

        ScriptResource res = new ScriptResource(vo);

        Assertions.assertNull(res.getType());
    }

    @Test
    public void toVo_roundTripsAllFourFields() {
        // toVo() copies id, type, path and content from the entity onto a fresh value object.
        ScriptResource res = new ScriptResource();
        res.setId(99);
        res.setType(ScriptResource.Type.FM);
        res.setPath("templates/report.ftl");
        res.setContent("<#-- body -->");

        ScriptResourceVo vo = res.toVo();

        Assertions.assertEquals(Integer.valueOf(99), vo.getId());
        Assertions.assertEquals(ScriptResource.Type.FM, vo.getType());
        Assertions.assertEquals("templates/report.ftl", vo.getPath());
        Assertions.assertEquals("<#-- body -->", vo.getContent());
    }
}
