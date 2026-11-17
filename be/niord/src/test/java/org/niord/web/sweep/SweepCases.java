/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.web.sweep;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The cases a group registers, by name; an entry names one as {@code "<group class>#<name>"} */
public final class SweepCases {

    private final Map<String, SweepCase> cases = new LinkedHashMap<>();

    public SweepCases add(String name, SweepCase sweepCase) {
        if (cases.put(name, sweepCase) != null) {
            throw new IllegalArgumentException("The case " + name + " is registered twice");
        }
        return this;
    }

    public Map<String, SweepCase> all() {
        return Collections.unmodifiableMap(cases);
    }

    /** The cases of a group, registered */
    public static SweepCases of(SweepGroup group) {
        SweepCases cases = new SweepCases();
        group.cases(cases);
        return cases;
    }
}
