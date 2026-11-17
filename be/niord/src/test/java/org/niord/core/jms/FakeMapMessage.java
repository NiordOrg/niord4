/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.core.jms;

import jakarta.jms.MapMessage;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal in-memory {@link jakarta.jms.MapMessage} test double backed by a {@link Map}.
 *
 * <p>It implements only the accessors the Niord JMS status-update listeners use — {@link #setObject},
 * {@link #setString}, {@link #setInt}, {@link #getString}, {@link #getObject}, {@link #itemExists} and
 * {@link #getMapNames}; all other {@code MapMessage}/{@code Message} methods inherit the
 * {@link UnsupportedJmsMessage} "unsupported" behaviour. {@link #getString(String)} mirrors the real
 * contract for the values Niord publishes: {@code null} for an absent key, otherwise the value's string
 * form (so an {@code Integer} {@code ID} reads back as its decimal string, exactly as a broker-delivered
 * {@code MapMessage} would).
 *
 * <p>Use {@link #statusUpdate(Integer, String, String, String)} to build a message whose body matches
 * {@code MessageService.sendStatusUpdate} ({@code ID}/{@code UID}/{@code STATUS}/{@code PREV_STATUS}),
 * which is the exact contract both listeners decode.
 */
public class FakeMapMessage extends UnsupportedJmsMessage implements MapMessage {

    private final Map<String, Object> body = new LinkedHashMap<>();

    /**
     * Builds a status-update map message identical in shape to the one broadcast by
     * {@code MessageService.sendStatusUpdate(...)}. Any field may be {@code null} to model a malformed
     * broadcast (e.g. a missing {@code STATUS}).
     */
    public static FakeMapMessage statusUpdate(Integer id, String uid, String status, String prevStatus) {
        FakeMapMessage msg = new FakeMapMessage();
        msg.body.put("ID", id);
        msg.body.put("UID", uid);
        msg.body.put("STATUS", status);
        msg.body.put("PREV_STATUS", prevStatus);
        return msg;
    }

    @Override
    public void setObject(String name, Object value) {
        body.put(name, value);
    }

    @Override
    public void setString(String name, String value) {
        body.put(name, value);
    }

    @Override
    public void setInt(String name, int value) {
        body.put(name, value);
    }

    @Override
    public String getString(String name) {
        Object value = body.get(name);
        return value == null ? null : value.toString();
    }

    @Override
    public Object getObject(String name) {
        return body.get(name);
    }

    @Override
    public boolean itemExists(String name) {
        return body.containsKey(name);
    }

    @Override
    public Enumeration<String> getMapNames() {
        return Collections.enumeration(body.keySet());
    }

    // --- Remaining MapMessage accessors the Niord listeners never use ---------------------------- //

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not implemented by the MapMessage test double");
    }

    @Override public boolean getBoolean(String name) { throw unsupported(); }
    @Override public byte getByte(String name) { throw unsupported(); }
    @Override public short getShort(String name) { throw unsupported(); }
    @Override public char getChar(String name) { throw unsupported(); }
    @Override public int getInt(String name) { throw unsupported(); }
    @Override public long getLong(String name) { throw unsupported(); }
    @Override public float getFloat(String name) { throw unsupported(); }
    @Override public double getDouble(String name) { throw unsupported(); }
    @Override public byte[] getBytes(String name) { throw unsupported(); }
    @Override public void setBoolean(String name, boolean value) { throw unsupported(); }
    @Override public void setByte(String name, byte value) { throw unsupported(); }
    @Override public void setShort(String name, short value) { throw unsupported(); }
    @Override public void setChar(String name, char value) { throw unsupported(); }
    @Override public void setLong(String name, long value) { throw unsupported(); }
    @Override public void setFloat(String name, float value) { throw unsupported(); }
    @Override public void setDouble(String name, double value) { throw unsupported(); }
    @Override public void setBytes(String name, byte[] value) { throw unsupported(); }
    @Override public void setBytes(String name, byte[] value, int offset, int length) { throw unsupported(); }
}
