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
package org.niord.model.geojson;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the coordinates of a GeoJSON geometry, where a coordinate given as JSON {@code null} is read as
 * {@link Double#NaN}, not as the 0 Jackson reads into a {@code double}. A geometry that is stored is then refused as
 * a position that is not a number, as any other ({@code JtsConverter.toJtsOnGlobe}), where a null coordinate was
 * stored as latitude or longitude 0 (CG10). Every other value is read as Jackson reads a {@code double}; a whole
 * position or ring given as {@code null} stays {@code null}, as before.
 *
 * @param <T> the coordinate array: {@code double[]} for a position, one dimension more for each level of nesting
 */
public abstract class CoordinatesDeserializer<T> extends ValueDeserializer<T> {

    private final Class<T> type;

    protected CoordinatesDeserializer(Class<T> type) {
        this.type = type;
    }

    /** {@inheritDoc} */
    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) {
        return type.cast(read(p, ctxt, type));
    }

    /** Reads an array of the given type, the parser on its start */
    private static Object read(JsonParser p, DeserializationContext ctxt, Class<?> arrayType) {
        if (!p.isExpectedStartArrayToken()) {
            return ctxt.handleUnexpectedToken(arrayType, p);
        }
        Class<?> component = arrayType.getComponentType();
        List<Object> values = new ArrayList<>();
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
            if (component == double.class) {
                values.add(t == JsonToken.VALUE_NULL ? Double.NaN : ctxt.readValue(p, Double.TYPE));
            } else {
                values.add(t == JsonToken.VALUE_NULL ? null : read(p, ctxt, component));
            }
        }
        Object array = Array.newInstance(component, values.size());
        for (int i = 0; i < values.size(); i++) {
            Array.set(array, i, values.get(i));
        }
        return array;
    }

    /** A position: {@code [lon, lat]} */
    public static class Position extends CoordinatesDeserializer<double[]> {
        public Position() {
            super(double[].class);
        }
    }

    /** A list of positions: a line string's or a multi point's */
    public static class Positions extends CoordinatesDeserializer<double[][]> {
        public Positions() {
            super(double[][].class);
        }
    }

    /** A list of lists of positions: a polygon's rings or a multi line string's lines */
    public static class PositionLists extends CoordinatesDeserializer<double[][][]> {
        public PositionLists() {
            super(double[][][].class);
        }
    }

    /** A multi polygon's polygons */
    public static class PolygonLists extends CoordinatesDeserializer<double[][][][]> {
        public PolygonLists() {
            super(double[][][][].class);
        }
    }
}
