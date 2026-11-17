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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The answer a cell of the role table expects: {@code "ok"} (any 2xx), or a status with the detail sentence of the
 * problem body, matched as a substring. {@code "detail": null} written out means the answer carries no detail;
 * a detail left out is accepted only on an entry that is not reviewed yet (the format test says so).
 *
 * <p>In the table a cell is written {@code "ok"} or {@code {"status": 403, "detail": "does not own filter"}}.
 */
public final class Cell {

    /** The expected answer of a cell that is allowed */
    public static final Cell OK = new Cell(true, 0, null, false);

    private final boolean ok;
    private final int status;
    private final String detail;
    private final boolean detailGiven;

    private Cell(boolean ok, int status, String detail, boolean detailGiven) {
        this.ok = ok;
        this.status = status;
        this.detail = detail;
        this.detailGiven = detailGiven;
    }

    /**
     * A refusal or another exact answer with its detail ({@code null}: the answer has no detail). A blank detail is
     * refused: as a substring it would match any detail, Spring's own 404 included.
     */
    public static Cell status(int status, String detail) {
        if (detail != null && detail.isBlank()) {
            throw new IllegalArgumentException("A cell's detail is a sentence of the answer, or null for an answer "
                    + "without one; not \"" + detail + "\" (status " + status + ")");
        }
        return new Cell(false, status, detail, true);
    }

    /** An exact answer whose detail is not decided yet */
    public static Cell statusOnly(int status) {
        return new Cell(false, status, null, false);
    }

    /** Reads a cell as the table writes it */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Cell of(JsonNode node) {
        if (node.isString()) {
            if ("ok".equals(node.asString())) {
                return OK;
            }
            throw new IllegalArgumentException("A cell is \"ok\" or {\"status\": ..., \"detail\": ...}, not " + node);
        }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                if (!name.equals("status") && !name.equals("detail")) {
                    throw new IllegalArgumentException("Unknown field '" + name + "' in the cell " + node);
                }
            }
            JsonNode status = node.get("status");
            if (status == null || !status.isInt()) {
                throw new IllegalArgumentException("A cell object needs an integer status: " + node);
            }
            if (!node.has("detail")) {
                return statusOnly(status.asInt());
            }
            JsonNode detail = node.get("detail");
            return status(status.asInt(), detail.isNull() ? null : detail.asString());
        }
        throw new IllegalArgumentException("A cell is \"ok\" or {\"status\": ..., \"detail\": ...}, not " + node);
    }

    /** Writes a cell as the table holds it */
    @JsonValue
    public Object json() {
        if (ok) {
            return "ok";
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status);
        if (detailGiven) {
            value.put("detail", detail);
        }
        return value;
    }

    public boolean isOk() {
        return ok;
    }

    public int status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public boolean detailGiven() {
        return detailGiven;
    }

    /** Whether the cell expects a refusal (a 4xx), as opposed to an allowed answer or a redirect */
    public boolean isRefusal() {
        return !ok && status >= 400 && status < 500;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cell c && ok == c.ok && status == c.status && detailGiven == c.detailGiven
                && Objects.equals(detail, c.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ok, status, detail, detailGiven);
    }

    @Override
    public String toString() {
        if (ok) {
            return "ok (2xx)";
        }
        return status + (detailGiven ? (detail == null ? " without a detail" : " \"" + detail + "\"") : "");
    }
}
