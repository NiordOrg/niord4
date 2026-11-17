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

import jdk.dynalink.beans.StaticClass;
import org.niord.core.script.NashornEngines;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import javax.script.Invocable;
import javax.script.ScriptEngine;

/**
 * Utility function used to determine if a message is included in a message filter.
 * <p>
 * Message filter example:
 * "(msg.type == Type.TEMPORARY_NOTICE || msg.type == Type.PRELIMINARY_NOTICE) && msg.status == Status.PUBLISHED"
 * <p>
 * The filter is a publication's message-tag filter, text entered in the publication editor, so it runs on a
 * sandboxed engine: no Java class lookup or reflection, no {@code exit}/{@code quit}/{@code load}. The enums a
 * filter compares with are handed in as bindings instead of being looked up with {@code Java.type}.
 */
@SuppressWarnings("unused")
public class MessageScriptFilterEvaluator {

    private static Class<?>[] FILTER_ENUMS = { MainType.class, Type.class, Status.class };

    /** Exclude all messages **/
    public static MessageScriptFilterEvaluator EXCLUDE_ALL = new MessageScriptFilterEvaluator() {
        @Override
        public boolean includeMessage(Message message, Object data) {
            return false;
        }
    };

    private final String filter;
    private Invocable filterFunction = null;

    /** Non-public constructor **/
    private MessageScriptFilterEvaluator() {
        filter = null;
    }

    /**
     * Constructor
     * @param filter the message filter
     */
    public MessageScriptFilterEvaluator(String filter) throws Exception {
        this.filter = filter;

        // Instantiate the filter Javascript engine
        if (filter != null && filter.trim().length() > 0) {
            try {
                // In some JVMs, it actually works to use enum/string comparison, e.g.
                // "msg.status == 'PUBLISHED'". But on others this will fail.
                // See https://bugs.openjdk.java.net/browse/JDK-8072426
                // So, we play it safe and hand the Enums to the script as the same class objects
                // Java.type would return. The sandboxed engine has no Java.type.
                ScriptEngine jsEngine = NashornEngines.sandboxed();
                for (Class<?> type : FILTER_ENUMS) {
                    jsEngine.put(type.getSimpleName(), StaticClass.forClass(type));
                }

                String jsFilter = "function includeMessage(msg, data) { return " + filter + "; }";

                // Considerations: Various documentation suggests that the ScriptEngine is indeed threadsafe.
                // However, shared state is not isolated, so, setting the parameters (msg) as
                // script engine state and evaluating the filter directly would not work correctly.
                // Instead, we wrap the filter in a function and call that function.
                jsEngine.eval(jsFilter);
                filterFunction = (Invocable)jsEngine;
            } catch (Exception e) {
                // Chain the cause rather than logging: this constructor throws, so the caller owns
                // the reporting — logging here as well would report the same failure twice.
                throw new Exception("Invalid message script: " + filter, e);
            }
        }
    }

    /**
     * Check if the message is included in the filter or not
     * @param message the message to check
     * @param data optionally, a data object can be passed on to the filter function
     * @return if the message is included in the filter or not
     */
    public boolean includeMessage(Message message, Object data) {
        // Check if a message filter has been defined
        if (filterFunction != null) {
            try {
                return  (Boolean)filterFunction.invokeFunction("includeMessage", message, data);
            } catch (Exception ignored) {
                // Do not include
            }
        }
        return false;
    }

    public String getFilter() {
        return filter;
    }
}
