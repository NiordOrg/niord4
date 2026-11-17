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

package org.niord.core.category;

import java.util.Set;

/**
 * The only shape the {@code field} attribute of a field template may have. The attribute becomes the left-hand
 * side of a script assignment, and the text around it is template output, which can carry values that the
 * caller of the template execution supplied. So it is checked before it is run:
 * <pre>
 *     path      := root ( '.' name call? )+       the last segment has no call
 *     root      := one of the context data names (message, part, params, promulgation, ...)
 *     call      := '(' ( literal ( ',' literal )* )? ')'
 *     literal   := a single-quoted string with no quote, backslash or line break inside | digits
 *     name      := [A-Za-z_][A-Za-z0-9_]*, not one of the names that reach the function constructor,
 *                  a Java class or another function's invocation (see DENIED_NAMES), and not starting with "__"
 * </pre>
 * Examples: {@code part.getDesc('en').details}, {@code message.promulgation('navtex').text},
 * {@code promulgation.text}.
 */
final class FieldTemplatePath {

    /** Member names a field path may not use */
    static final Set<String> DENIED_NAMES = Set.of(
            "constructor", "prototype", "class", "getClass", "getDeclaringClass",
            "call", "apply", "bind", "eval");

    /** Longer than any real field path by a wide margin */
    static final int MAX_LENGTH = 200;

    private final String text;
    private int pos;

    private FieldTemplatePath(String text) {
        this.text = text;
    }

    /**
     * Returns if the field is a property path of the supported shape, starting at one of the given roots
     * @param field the field attribute of a field template
     * @param roots the names the script engine has bound
     * @return if the field may be assigned
     */
    static boolean isValid(String field, Set<String> roots) {
        if (field == null || field.isEmpty() || field.length() > MAX_LENGTH) {
            return false;
        }
        return new FieldTemplatePath(field).path(roots);
    }

    private boolean path(Set<String> roots) {
        String root = name();
        if (root == null || !roots.contains(root)) {
            return false;
        }
        int segments = 0;
        boolean endsWithCall = false;
        while (pos < text.length()) {
            if (text.charAt(pos++) != '.') {
                return false;
            }
            String name = name();
            if (name == null || DENIED_NAMES.contains(name) || name.startsWith("__")) {
                return false;
            }
            segments++;
            endsWithCall = pos < text.length() && text.charAt(pos) == '(';
            if (endsWithCall && !call()) {
                return false;
            }
        }
        return segments > 0 && !endsWithCall;
    }

    private String name() {
        int start = pos;
        if (pos < text.length() && isNameStart(text.charAt(pos))) {
            pos++;
            while (pos < text.length() && isNamePart(text.charAt(pos))) {
                pos++;
            }
        }
        return pos > start ? text.substring(start, pos) : null;
    }

    private boolean call() {
        pos++; // '('
        if (pos < text.length() && text.charAt(pos) == ')') {
            pos++;
            return true;
        }
        while (true) {
            skipSpaces();
            if (!literal()) {
                return false;
            }
            skipSpaces();
            if (pos >= text.length()) {
                return false;
            }
            char c = text.charAt(pos++);
            if (c == ')') {
                return true;
            }
            if (c != ',') {
                return false;
            }
        }
    }

    private boolean literal() {
        if (pos >= text.length()) {
            return false;
        }
        if (text.charAt(pos) == '\'') {
            pos++;
            while (pos < text.length() && text.charAt(pos) != '\'') {
                char c = text.charAt(pos++);
                if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == ' ' || c == ' ') {
                    return false;
                }
            }
            if (pos >= text.length()) {
                return false;
            }
            pos++;
            return true;
        }
        int start = pos;
        while (pos < text.length() && pos - start < 9 && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
            pos++;
        }
        return pos > start;
    }

    private void skipSpaces() {
        while (pos < text.length() && text.charAt(pos) == ' ') {
            pos++;
        }
    }

    private static boolean isNameStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isNamePart(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9');
    }
}
