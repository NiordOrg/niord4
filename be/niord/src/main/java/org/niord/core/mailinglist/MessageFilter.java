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

package org.niord.core.mailinglist;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.message.Message;
import org.niord.core.script.NashornEngines;
import org.openjdk.nashorn.api.scripting.NashornException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.regex.Pattern;

/**
 * Can be used to evaluate a message filter such as
 * <pre>
 *     msg.promulgation('navtex').promulgate && msg.promulgation('navtex').useTransmitter('Baltico')
 * </pre>
 * The filter is text an administrator enters on a mailing-list trigger, so it runs on a sandboxed engine:
 * no Java class lookup or reflection, no {@code exit}/{@code quit}/{@code load}. The sandbox limits what
 * the filter can look up, not what it can call on the objects it is handed: it still reaches the public
 * methods of the message entity and of everything that entity returns.
 */
public class MessageFilter {

    private static final Logger log = LoggerFactory.getLogger(MessageFilter.class);

    /**
     * Nashorn's TypeError for a property read or a function call on null, e.g.
     * {@code Cannot get property "promulgate" of null} and {@code null has no such function "useTransmitter"}.
     * A read on undefined (a misspelt property, an index past the end of a script array) is not one of them.
     */
    private static final Pattern NULL_ACCESS = Pattern.compile(
            "TypeError: (Cannot (get|read) property \"[^\"]*\" (of|from) null|null has no such function \"[^\"]*\")");

    private ScriptEngine jsEngine = NashornEngines.sandboxed();

    /** The filter expression, for the error a failing filter gives **/
    private String filter;

    /** Private access constructor **/
    private MessageFilter() {
    }

    /**
     * Creates a new message filter for the given filter expression.
     * @param messageFilter the message filter expression
     * @return the instantiated message filter
     */
    public static MessageFilter getInstance(String messageFilter) throws ScriptException {

        // Considerations: Various documentation suggests that the ScriptEngine is indeed threadsafe.
        // However, shared state is not isolated, so, setting the parameters (msg) as
        // script engine state and evaluating the filter directly would not work correctly.
        // Instead, we wrap the filter in a function and call that function.

        String jsFilter = "function matchesMessage(msg) { return " + messageFilter + "; }";
        MessageFilter filter = new MessageFilter();
        filter.filter = messageFilter;
        filter.jsEngine.eval(jsFilter);
        return filter;
    }

    /**
     * Checks if the message matches the message filter.
     * <p>
     * Only a result of {@code true} is a match; any other result ({@code false}, null, undefined, a string) is no
     * match. A filter that reads a property of, or calls a function on, a value that is null (a message with no
     * such promulgation, for {@code msg.promulgation('navtex').promulgate}) is no match either: a Java getter
     * answers null for "the message has none", and the filter shape the class comment shows depends on it. Any
     * other failure of the script (a Java exception from a called method, a misspelt name or property, a function
     * that does not exist) is an error, not a decision not to mail.
     *
     * @param message the message to check
     * @return if the message matches the message filter
     * @throws IllegalArgumentException if the filter fails for the message, naming the filter and the cause
     */
    public boolean matches(Message message) {
        try {
            Invocable filterFunction = (Invocable)jsEngine;
            return Boolean.TRUE.equals(filterFunction.invokeFunction("matchesMessage", message));
        } catch (Exception e) {
            if (isNullAccess(e)) {
                log.debug("Message filter '{}' reads a value that is null for message {}: no match ({})",
                        filter, uid(message), e.getMessage());
                return false;
            }
            throw new IllegalArgumentException("The message filter '" + filter + "' failed for message "
                    + uid(message) + ": " + describe(e), e);
        }
    }

    /** Returns if the failure is a property read or a function call on null, and nothing else **/
    private static boolean isNullAccess(Exception e) {
        Throwable cause = e instanceof ScriptException && e.getCause() != null ? e.getCause() : e;
        return cause instanceof NashornException
                && cause.getMessage() != null
                && NULL_ACCESS.matcher(cause.getMessage()).matches();
    }

    /** The uid of the message, for the messages of this class **/
    private static String uid(Message message) {
        return message == null ? "(none)" : message.getUid();
    }

    /** The failure as a short text: its message, or its class when it has none **/
    private static String describe(Exception e) {
        Throwable cause = e instanceof ScriptException && e.getCause() != null ? e.getCause() : e;
        return StringUtils.isNotBlank(cause.getMessage()) ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
