/*
 * Copyright 2026 Danish Emergency Management Agency.
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
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.message.MessagePartVo;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link NashornEngines}, the one factory for Nashorn engines.
 * <p>
 * The globals that end the JVM are checked with {@code typeof}, never called: if the factory regressed, a
 * call would end the Surefire fork instead of failing the test.
 */
class NashornEnginesTest {

    // ------------------------------------------------------------------------------------------- //
    // Sandboxed
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sandboxedEngineHasNoJavaAndNoProcessOrLoadFunctions() throws ScriptException {
        ScriptEngine engine = NashornEngines.sandboxed();

        for (String global : List.of("exit", "quit", "load", "loadWithNewGlobal",
                "Java", "Packages", "JavaImporter", "java", "javax", "org", "com")) {
            assertEquals("undefined", engine.eval("typeof " + global), global);
        }
    }

    @Test
    void sandboxedEngineKeepsItsGlobalWhenTheEngineScopeIsReplaced() throws ScriptException {
        // Without --global-per-engine, new engine-scope bindings get a new global with every built-in back.
        ScriptEngine engine = NashornEngines.sandboxed();
        Bindings bindings = new SimpleBindings();
        bindings.put("answer", 42);
        engine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);

        assertEquals("undefined", engine.eval("typeof quit"));
        assertEquals("undefined", engine.eval("typeof Java"));
        assertEquals(42, ((Number) engine.eval("answer")).intValue(), "the replaced bindings are still read");
    }

    @Test
    void sandboxedEngineRefusesJavaReflectionOnAHandedInObject() {
        ScriptEngine engine = NashornEngines.sandboxed();
        engine.put("message", new SystemMessageVo());

        assertThrows(ScriptException.class,
                () -> engine.eval("message.getClass().forName('java.lang.Runtime')"));
    }

    @Test
    void sandboxedEngineCallsMethodsAndAssignsPropertiesOnAHandedInObject() throws ScriptException {
        // What the field templates and the filters need: calls and assignments on the objects handed in.
        MessagePartVo part = new MessagePartVo();
        part.checkCreateDesc("en");
        ScriptEngine engine = NashornEngines.sandboxed();
        engine.put("part", part);
        engine.put("content", "Light unlit");

        engine.eval("part.getDesc('en').subject = content;");

        assertEquals("Light unlit", part.getDesc("en").getSubject());
    }

    // ------------------------------------------------------------------------------------------- //
    // Trusted
    // ------------------------------------------------------------------------------------------- //

    @Test
    void trustedEngineKeepsJavaAndLoadButHasNoProcessFunctions() throws ScriptException {
        ScriptEngine engine = NashornEngines.trusted();

        for (String global : List.of("exit", "quit", "loadWithNewGlobal")) {
            assertEquals("undefined", engine.eval("typeof " + global), global);
        }
        assertEquals("function", engine.eval("typeof load"), "the niord: script loading wraps load");
        assertEquals(0, ((Number) engine.eval("new java.util.ArrayList().size()")).intValue(),
                "script resources use java.util and CdiUtils");
    }

    @Test
    void trustedEngineKeepsItsGlobalWhenTheEngineScopeIsReplaced() throws ScriptException {
        ScriptEngine engine = NashornEngines.trusted();
        engine.setBindings(new SimpleBindings(), ScriptContext.ENGINE_SCOPE);

        assertEquals("undefined", engine.eval("typeof quit"));
        assertEquals("undefined", engine.eval("typeof exit"));
    }
}
