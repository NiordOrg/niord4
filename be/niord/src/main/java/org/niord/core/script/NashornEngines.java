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

import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import java.util.List;

/**
 * The one place Niord creates Nashorn script engines. Every engine comes from one of two profiles.
 * <ul>
 *     <li>{@link #sandboxed()} evaluates expressions whose text a caller can write or influence: mailing-list
 *     message filters, publication message-tag filters and field-template assignments. The script
 *     gets no Java at all. {@code --no-java} leaves out {@code Java}, {@code Packages}, {@code java} and the
 *     other package roots, and the deny-all class filter makes Nashorn refuse Java reflection on the objects
 *     a caller hands in. It also has no {@code load}, so it cannot read a script from a file or a URL.</li>
 *     <li>{@link #trusted()} runs the JavaScript resources a system administrator writes: template scripts and
 *     batch scripts. They look up services through {@code CdiUtils} and use {@code java.util}, so they keep
 *     Java access and {@code load} (which {@link JsResourceService} wraps for the {@code niord:} scheme).</li>
 * </ul>
 * Both profiles remove {@code exit} and {@code quit}, which end the JVM (Java 25 has no security manager to
 * stop them), and {@code loadWithNewGlobal}, which runs a script in a fresh global where the removed functions
 * are back. Both run with {@code --global-per-engine}: without it, a caller that replaces the engine-scope
 * bindings gets a new global with every built-in restored.
 * <p>
 * Put values into an engine with {@link ScriptEngine#put(String, Object)}. A Nashorn engine is not
 * thread-safe; share one only behind a lock.
 */
public final class NashornEngines {

    /** Globals removed from a sandboxed engine */
    static final List<String> SANDBOXED_REMOVED_GLOBALS = List.of("exit", "quit", "load", "loadWithNewGlobal");

    /** Globals removed from a trusted engine; {@code load} stays for the {@code niord:} scheme */
    static final List<String> TRUSTED_REMOVED_GLOBALS = List.of("exit", "quit", "loadWithNewGlobal");

    private static final NashornScriptEngineFactory FACTORY = new NashornScriptEngineFactory();

    private NashornEngines() {
    }

    /**
     * Returns a new engine with no Java access, for expressions whose text a caller can influence
     * @return a new sandboxed engine
     */
    public static ScriptEngine sandboxed() {
        ScriptEngine engine = FACTORY.getScriptEngine(
                new String[] { "--no-java", "--global-per-engine" },
                appClassLoader(),
                className -> false);
        return removeGlobals(engine, SANDBOXED_REMOVED_GLOBALS);
    }

    /**
     * Returns a new engine with Java access, for the script resources a system administrator writes
     * @return a new trusted engine
     */
    public static ScriptEngine trusted() {
        ScriptEngine engine = FACTORY.getScriptEngine(
                new String[] { "--global-per-engine" },
                appClassLoader());
        return removeGlobals(engine, TRUSTED_REMOVED_GLOBALS);
    }

    /** Deletes the given functions from the engine's global object */
    private static ScriptEngine removeGlobals(ScriptEngine engine, List<String> names) {
        Bindings global = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        names.forEach(global::remove);
        return engine;
    }

    /** The class loader the engine resolves Java classes with, as ScriptEngineManager chose it */
    private static ClassLoader appClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : NashornEngines.class.getClassLoader();
    }
}
