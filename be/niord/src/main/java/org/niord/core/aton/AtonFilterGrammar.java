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

package org.niord.core.aton;

import org.niord.core.aton.vo.AtonNodeVo;

import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The only expressions an AtoN filter may be, and the parser that turns one into a predicate. The filter
 * text comes from category and dictionary data and from request bodies:
 * <pre>
 *     expr    := and ( '||' and )*
 *     and     := unary ( '&amp;&amp;' unary )*
 *     unary   := '!'* primary
 *     primary := '(' expr ')' | term
 *     term    := 'aton' '.' ( 'k' | 'v' ) '(' string ')' | 'aton' '.' 'kv' '(' string ',' string ')'
 *     string  := a single- or double-quoted literal with no quote, backslash or line break inside
 * </pre>
 * Whitespace is allowed between tokens. Anything else is refused.
 * <p>
 * The filters are written as JavaScript expressions, and each one that parses means here what it meant as
 * JavaScript: {@code &&} binds tighter than {@code ||}, both short-circuit from the left, {@code !} negates,
 * and a term calls {@link AtonNodeVo#k}, {@link AtonNodeVo#v} or {@link AtonNodeVo#kv} with the literal
 * text between the quotes. No script engine is involved.
 * <p>
 * A single left-to-right pass with a bounded nesting depth; no regular expression.
 */
final class AtonFilterGrammar {

    /** Longer than any real filter by a wide margin */
    static final int MAX_LENGTH = 2000;

    /** Parentheses nested deeper than this are refused */
    static final int MAX_DEPTH = 20;

    private final String text;
    private int pos;
    private int depth;

    private AtonFilterGrammar(String text) {
        this.text = text;
    }

    /**
     * Parses the AtoN filter into a predicate
     * @param atonFilter the AtoN filter
     * @return the predicate the filter stands for
     * @throws ScriptException if the filter is not an expression of the grammar above
     */
    static Predicate<AtonNodeVo> parse(String atonFilter) throws ScriptException {
        if (atonFilter == null || atonFilter.isBlank()) {
            throw new ScriptException("Empty AtoN filter");
        }
        if (atonFilter.length() > MAX_LENGTH) {
            throw new ScriptException("AtoN filter longer than " + MAX_LENGTH + " characters");
        }
        AtonFilterGrammar grammar = new AtonFilterGrammar(atonFilter);
        Predicate<AtonNodeVo> filter = grammar.expression();
        grammar.skipWhitespace();
        if (grammar.pos != atonFilter.length()) {
            throw grammar.refused();
        }
        return filter;
    }

    private Predicate<AtonNodeVo> expression() throws ScriptException {
        List<Predicate<AtonNodeVo>> alternatives = new ArrayList<>();
        alternatives.add(conjunction());
        while (consume("||")) {
            alternatives.add(conjunction());
        }
        return alternatives.size() == 1
                ? alternatives.getFirst()
                : aton -> alternatives.stream().anyMatch(p -> p.test(aton));
    }

    private Predicate<AtonNodeVo> conjunction() throws ScriptException {
        List<Predicate<AtonNodeVo>> operands = new ArrayList<>();
        operands.add(unary());
        while (consume("&&")) {
            operands.add(unary());
        }
        return operands.size() == 1
                ? operands.getFirst()
                : aton -> operands.stream().allMatch(p -> p.test(aton));
    }

    private Predicate<AtonNodeVo> unary() throws ScriptException {
        boolean negated = false;
        while (consume("!")) {
            // Negations only repeat; they do not nest
            negated = !negated;
        }
        Predicate<AtonNodeVo> operand = primary();
        return negated ? operand.negate() : operand;
    }

    private Predicate<AtonNodeVo> primary() throws ScriptException {
        if (consume("(")) {
            if (++depth > MAX_DEPTH) {
                throw refused();
            }
            Predicate<AtonNodeVo> inner = expression();
            expect(")");
            depth--;
            return inner;
        }
        return term();
    }

    private Predicate<AtonNodeVo> term() throws ScriptException {
        expect("aton");
        expect(".");
        skipWhitespace();
        if (text.startsWith("kv", pos)) {
            pos += 2;
            expect("(");
            String keyPattern = string();
            expect(",");
            String valuePattern = string();
            expect(")");
            return aton -> aton.kv(keyPattern, valuePattern);
        }
        boolean key = text.startsWith("k", pos);
        if (!key && !text.startsWith("v", pos)) {
            throw refused();
        }
        pos += 1;
        expect("(");
        String pattern = string();
        expect(")");
        return key ? aton -> aton.k(pattern) : aton -> aton.v(pattern);
    }

    /** Consumes a string literal and returns the text between its quotes */
    private String string() throws ScriptException {
        skipWhitespace();
        if (pos >= text.length() || (text.charAt(pos) != '\'' && text.charAt(pos) != '"')) {
            throw refused();
        }
        char quote = text.charAt(pos++);
        int start = pos;
        while (pos < text.length() && text.charAt(pos) != quote) {
            if (!allowedInString(text.charAt(pos))) {
                throw refused();
            }
            pos++;
        }
        if (pos >= text.length()) {
            throw refused();
        }
        return text.substring(start, pos++);
    }

    /** No quote or escape, and none of the characters that end a JavaScript line */
    private static boolean allowedInString(char c) {
        return c != '\'' && c != '"' && c != '\\'
                && c != '\n' && c != '\r' && c != ' ' && c != ' ';
    }

    /** Skips whitespace and consumes the token if it comes next */
    private boolean consume(String token) {
        skipWhitespace();
        if (text.startsWith(token, pos)) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private void expect(String token) throws ScriptException {
        if (!consume(token)) {
            throw refused();
        }
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private ScriptException refused() {
        return new ScriptException("Not a supported AtoN filter expression (position " + pos + ")");
    }
}
