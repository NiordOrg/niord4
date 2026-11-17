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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One entry of the endpoint table: one handler at one verb, with every path it is mapped at.
 *
 * <p>The generator ({@link EndpointTableGenerator}) writes and refreshes the facts: {@code key}, {@code handler},
 * {@code guard}, {@code params}, {@code codeCheck} and {@code codeCheckEvidence}, and adds to {@code alsoAt}. A
 * person writes the decisions: {@code kind}, {@code rules}, {@code realmRole}, {@code answers}, {@code case},
 * {@code coveredBy}, {@code expect}, {@code expectedLog}, {@code knownFault}, {@code exempt} and {@code reviewed}.
 * The fields are those of the design's table format; {@code context}, {@code codeCheckEvidence}, {@code note},
 * {@code emptyByDesign}, {@code derived} and {@code settle} are explained on their fields.
 */
@JsonPropertyOrder({"key", "handler", "alsoAt", "kind", "context", "guard", "codeCheck", "codeCheckEvidence", "params",
        "rules", "realmRole", "answers", "case", "coveredBy", "expect", "emptyByDesign", "derived", "settle",
        "expectedLog", "knownFault", "exempt", "note", "reviewed"})
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.PUBLIC_ONLY,
        getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class EndpointEntry {

    /** The kinds of {@link #kind} */
    public static final Set<String> KINDS =
            Set.of("read", "compute", "write", "writeByGet", "upload", "global", "globalIdempotent");

    /** The rules of {@link #rules} */
    public static final Set<String> RULES = Set.of("owner", "domain", "ticket", "setting");

    /** The values of {@link #codeCheck} */
    public static final Set<String> CODE_CHECKS = Set.of("found", "none-found");

    /**
     * The value of {@link #caseName} of an entry whose case is still to be written: the generator's skeleton of a new
     * handler, which the format test and the sweep fail until a person decides it
     */
    public static final String PENDING = "pending";

    /** {@code "VERB /path"}: the verb and the first of the handler's path patterns, as Spring reports them */
    public String key;

    /** {@code "Controller.method"} (several handlers may share a name: the key is what identifies an entry) */
    public String handler;

    /** The handler's other path patterns; the generator adds to it and never removes from it */
    public List<String> alsoAt = new ArrayList<>();

    /** read, compute, write, writeByGet, upload, global or globalIdempotent (see {@link #KINDS}) */
    public String kind;

    /**
     * Where the entry is swept: null for the main sweep ({@code EndpointSweepITTest}), {@code "secom"} for a handler
     * of the SECOM administration interface, which only a context with the module switched on maps.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String context;

    /** The JSR-250 annotation that applies: {@code PermitAll}, {@code RolesAllowed(ADMIN)}, ... */
    public String guard;

    /** {@code found} or {@code none-found}: a suggestion of the generator's scan, which a person confirms */
    public String codeCheck;

    /** What the generator's scan found and where, and the classes it did not follow */
    public List<String> codeCheckEvidence = new ArrayList<>();

    /**
     * The request parameters and path variables the handler declares, or the string {@code "*"} for a handler that
     * reads the whole parameter map.
     */
    public Object params = new ArrayList<String>();

    /** owner, domain, ticket, setting (see {@link #RULES}) */
    public List<String> rules = new ArrayList<>();

    /** The Keycloak role that decides a call the handler forwards to Keycloak's admin interface */
    public String realmRole;

    /** Cell name ({@code identity} or {@code identity@row}) to its expected answer */
    public Map<String, Cell> answers = new LinkedHashMap<>();

    /** {@code "<SweepGroup class>#<case name>"}, {@code "pending"}, or null when the entry is covered or exempt */
    @JsonProperty("case")
    public String caseName;

    /**
     * {@code "<test class>#<method>"} of an existing test method that owns the handler's round trip: the sweep then
     * skips its own sweep step (and does not require that it reached the handler), and keeps the cells, the probes and
     * the log rule, for which the entry still needs a case (the request a cell sends) when it has cells to run.
     */
    public String coveredBy;

    /** What a read's answer must hold */
    public Expect expect;

    /** Why a read answers nothing worth asserting, in place of {@link #expect} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String emptyByDesign;

    /** A field the read-back does not hold as sent, to the reason */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, String> derived = new LinkedHashMap<>();

    /** The write queues mail or changes a message status: its background work is awaited at the settle point */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean settle;

    /** Log events the handler is expected to write (substrings of "LEVEL logger: message | exception") */
    public List<String> expectedLog = new ArrayList<>();

    /** Open faults the sweep meets, so that it stays green until they are fixed */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<KnownFault> knownFault = new ArrayList<>();

    /** Why the entry is not swept in full, and what is still asserted */
    public Exempt exempt;

    /** A note on the treatment (from the inventory), for the person who reviews the entry */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String note;

    /** A person decided the entry's cells */
    public boolean reviewed;

    /** The verb of the key */
    public String verb() {
        return key.substring(0, key.indexOf(' '));
    }

    /** The path pattern of the key */
    public String path() {
        return key.substring(key.indexOf(' ') + 1);
    }

    /** Every path the entry lists: the key's, then {@link #alsoAt} */
    public List<String> paths() {
        List<String> paths = new ArrayList<>();
        paths.add(path());
        paths.addAll(alsoAt);
        return paths;
    }

    /** The controller's simple name */
    public String controller() {
        return handler.substring(0, handler.indexOf('.'));
    }

    /** Whether the handler reads the whole parameter map */
    public boolean allParams() {
        return "*".equals(params);
    }

    /** The declared parameter names, empty for a handler that reads the whole map */
    @SuppressWarnings("unchecked")
    public List<String> paramNames() {
        return params instanceof List<?> list ? (List<String>) list : List.of();
    }

    public boolean isPending() {
        return PENDING.equals(caseName);
    }

    public boolean isRead() {
        return "read".equals(kind) || "compute".equals(kind);
    }

    public boolean isGlobal() {
        return "global".equals(kind) || "globalIdempotent".equals(kind);
    }

    @Override
    public String toString() {
        return key + " (" + handler + ")";
    }

    /** What a read's answer must hold: named fixture rows, a minimum size, a content type */
    @JsonPropertyOrder({"rows", "at", "minBytes", "contentType"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Expect {
        /** Rows the group's fixture declares, each of which the answer (or the value at {@link #at}) must hold */
        public List<String> rows = new ArrayList<>();
        /** A JSON path into the answer (RestAssured's syntax), or null for the whole answer */
        public String at;
        /** The answer is at least this long */
        public Integer minBytes;
        /** The answer's content type starts with this */
        public String contentType;
    }

    /**
     * An open fault, so that the sweep stays green while it shows and fails once it no longer does: the calls it
     * shows in, and what it gives today (an answer, a log line, or a case's check that does not hold).
     */
    @JsonPropertyOrder({"code", "cell", "answer", "log", "check"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnownFault {
        /**
         * The open record: the code of an entry of {@code be/docs/potential-errors.md}, or a phrase of an entry of
         * {@code fe/docs/backlog.md} (the format test holds it to {@code EndpointTableFormatTest.codeExists})
         */
        public String code;
        /**
         * The calls to the entry's own handler it shows in: a table cell's name ({@code "editor"},
         * {@code "admin@otherOwnerRow"}); the identity a case's call is sent as ({@code "admin"}: the sweep of an
         * entry swept as admin); a step of the case ({@code "sweep"}, {@code "allowed"}, {@code "probes"},
         * {@code "variants"}); or {@code "*"} for every call. For a {@code check} fault, {@code "*"}.
         */
        public String cell;
        /** The status it answers today instead of the expected one */
        public Integer answer;
        /** A substring of the log event it writes today */
        public String log;
        /** A substring of the text of a case's check that does not hold today (a field the backend drops) */
        public String check;

        /** What it gives today, for the failure text */
        public String describe() {
            List<String> parts = new ArrayList<>();
            parts.add("cell " + cell);
            if (answer != null) {
                parts.add("answer " + answer);
            }
            if (log != null) {
                parts.add("log \"" + log + "\"");
            }
            if (check != null) {
                parts.add("check \"" + check + "\"");
            }
            return String.join(", ", parts);
        }
    }

    /** Why an entry is not swept in full */
    @JsonPropertyOrder({"kind", "reason", "asserted"})
    public static class Exempt {
        /** The kinds of {@link #kind} */
        public static final Set<String> KINDS = Set.of("partner", "framework", "external", "refusalOnly");
        public String kind;
        public String reason;
        /** What is still asserted */
        public String asserted;
    }
}
