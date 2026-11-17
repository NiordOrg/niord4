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

package org.niord.core.aton;

import org.niord.core.aton.vo.AtonNodeVo;

import javax.script.ScriptException;
import java.util.List;
import java.util.function.Predicate;

/**
 * Can be used to evaluate an AtoN filter such as "aton.kv('seamark:type', 'light.*')"
 * <p>
 * The filter text reaches this class from request bodies as well as from category and dictionary data. It is
 * parsed by {@link AtonFilterGrammar} (terms {@code aton.k/v/kv} with string literals, {@code !}, {@code &&},
 * {@code ||} and parentheses) into a Java predicate; it is never run as script. Parsing is one pass over a
 * short text, so a filter is parsed per call rather than cached, and a match shares nothing with any other.
 */
public class AtonFilter {

    private final Predicate<AtonNodeVo> filter;

    /** Private access constructor **/
    private AtonFilter(Predicate<AtonNodeVo> filter) {
        this.filter = filter;
    }

    /**
     * Creates a new AtoN filter for the given filter expression.
     * Example: "aton.kv('seamark:type', 'light.*')"
     * @param atonFilter the AtoN filter expression
     * @return the instantiated AtoN filter
     * @throws ScriptException if the expression is not a supported AtoN filter (the exception type the
     *                         callers have always treated as "matches nothing")
     */
    public static AtonFilter getInstance(String atonFilter) throws ScriptException {
        return new AtonFilter(AtonFilterGrammar.parse(atonFilter));
    }

    /**
     * Checks if the AtoN matches the AtoN filter
     * @param aton the AtoN to check
     * @return if the AtoN matches the AtoN filter
     */
    public boolean matches(AtonNodeVo aton) {
        try {
            return filter.test(aton);
        } catch (RuntimeException | StackOverflowError e) {
            // An invalid regular expression, a tag without key or value, or a pattern that recurses too deep
            // on a long tag: the filter matches nothing, as when it ran as script
            return false;
        }
    }

    /**
     * Checks if all AtoNs match the AtoN filter
     * @param atons the AtoNs to check
     * @return if all AtoNs match the AtoN filter
     */
    public boolean matches(List<AtonNodeVo> atons) {
        return atons.stream()
                .allMatch(this::matches);
    }
}
