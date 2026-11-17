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

import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;

import java.util.Enumeration;

/**
 * A framework-neutral {@link jakarta.jms.Message} test double whose every method throws
 * {@link UnsupportedOperationException}. It exists so JMS-consuming code (the Niord status-update
 * listeners) can be unit-tested without a live broker:
 * <ul>
 *   <li>{@link FakeMapMessage} extends it and implements only the handful of {@code MapMessage}
 *       accessors the listeners actually read, and</li>
 *   <li>an anonymous {@code new UnsupportedJmsMessage(){}} is a {@link Message} that is <em>not</em>
 *       a {@link jakarta.jms.MapMessage}, used to exercise the listeners' {@code (MapMessage) message}
 *       cast-failure path.</li>
 * </ul>
 *
 * <p>This is a hand-written test double (the project uses no mocking framework); it is deliberately
 * abstract so it is never mistaken for a usable message. Plain {@code jakarta.jms} only — it carries
 * no Quarkus coupling and survives the planned Spring migration unchanged.
 */
public abstract class UnsupportedJmsMessage implements Message {

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not implemented by the JMS message test double");
    }

    @Override public String getJMSMessageID() { throw unsupported(); }
    @Override public void setJMSMessageID(String id) { throw unsupported(); }
    @Override public long getJMSTimestamp() { throw unsupported(); }
    @Override public void setJMSTimestamp(long timestamp) { throw unsupported(); }
    @Override public byte[] getJMSCorrelationIDAsBytes() { throw unsupported(); }
    @Override public void setJMSCorrelationIDAsBytes(byte[] correlationID) { throw unsupported(); }
    @Override public void setJMSCorrelationID(String correlationID) { throw unsupported(); }
    @Override public String getJMSCorrelationID() { throw unsupported(); }
    @Override public Destination getJMSReplyTo() { throw unsupported(); }
    @Override public void setJMSReplyTo(Destination replyTo) { throw unsupported(); }
    @Override public Destination getJMSDestination() { throw unsupported(); }
    @Override public void setJMSDestination(Destination destination) { throw unsupported(); }
    @Override public int getJMSDeliveryMode() { throw unsupported(); }
    @Override public void setJMSDeliveryMode(int deliveryMode) { throw unsupported(); }
    @Override public boolean getJMSRedelivered() { throw unsupported(); }
    @Override public void setJMSRedelivered(boolean redelivered) { throw unsupported(); }
    @Override public String getJMSType() { throw unsupported(); }
    @Override public void setJMSType(String type) { throw unsupported(); }
    @Override public long getJMSExpiration() { throw unsupported(); }
    @Override public void setJMSExpiration(long expiration) { throw unsupported(); }
    @Override public long getJMSDeliveryTime() { throw unsupported(); }
    @Override public void setJMSDeliveryTime(long deliveryTime) { throw unsupported(); }
    @Override public int getJMSPriority() { throw unsupported(); }
    @Override public void setJMSPriority(int priority) { throw unsupported(); }
    @Override public void clearProperties() { throw unsupported(); }
    @Override public boolean propertyExists(String name) { throw unsupported(); }
    @Override public boolean getBooleanProperty(String name) { throw unsupported(); }
    @Override public byte getByteProperty(String name) { throw unsupported(); }
    @Override public short getShortProperty(String name) { throw unsupported(); }
    @Override public int getIntProperty(String name) { throw unsupported(); }
    @Override public long getLongProperty(String name) { throw unsupported(); }
    @Override public float getFloatProperty(String name) { throw unsupported(); }
    @Override public double getDoubleProperty(String name) { throw unsupported(); }
    @Override public String getStringProperty(String name) { throw unsupported(); }
    @Override public Object getObjectProperty(String name) { throw unsupported(); }
    @Override public Enumeration<?> getPropertyNames() { throw unsupported(); }
    @Override public void setBooleanProperty(String name, boolean value) { throw unsupported(); }
    @Override public void setByteProperty(String name, byte value) { throw unsupported(); }
    @Override public void setShortProperty(String name, short value) { throw unsupported(); }
    @Override public void setIntProperty(String name, int value) { throw unsupported(); }
    @Override public void setLongProperty(String name, long value) { throw unsupported(); }
    @Override public void setFloatProperty(String name, float value) { throw unsupported(); }
    @Override public void setDoubleProperty(String name, double value) { throw unsupported(); }
    @Override public void setStringProperty(String name, String value) { throw unsupported(); }
    @Override public void setObjectProperty(String name, Object value) { throw unsupported(); }
    @Override public void acknowledge() { throw unsupported(); }
    @Override public void clearBody() { throw unsupported(); }
    @Override public <T> T getBody(Class<T> c) { throw unsupported(); }
    @Override public boolean isBodyAssignableTo(Class c) { throw unsupported(); }
}
