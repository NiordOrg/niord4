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
package org.niord.test;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-test smoke test proving the whole infrastructure works end to end:
 * <ul>
 *   <li>Failsafe discovers and runs {@code *ITTest} classes during {@code mvn verify},</li>
 *   <li>Testcontainers starts the Niord services on dynamic ports,</li>
 *   <li>the shared {@link ITTest} base class exposes their coordinates, and</li>
 *   <li>the services are actually reachable.</li>
 * </ul>
 * Per the test-infrastructure task this proves connectivity/configuration only — it asserts no
 * Niord business behaviour. It requires Docker and skips gracefully otherwise (see {@link ITTest}).
 */
class TestInfrastructureITTest extends ITTest {

    @Test
    void postgresContainerAcceptsJdbcConnections() throws Exception {
        PostgreSQLContainer<?> postgres = NiordTestContainers.postgres();
        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");

        try (Connection conn = postgres.createConnection("");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next(), "SELECT 1 should return a row");
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void postgisExtensionIsAvailable() throws Exception {
        PostgreSQLContainer<?> postgres = NiordTestContainers.postgres();
        try (Connection conn = postgres.createConnection("");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT PostGIS_Lib_Version()")) {
            assertTrue(rs.next(), "PostGIS_Lib_Version() should return a row");
            assertTrue(rs.getString(1) != null && !rs.getString(1).isBlank(),
                    "the PostGIS extension must be installed in the test database");
        }
    }

    @Test
    void artemisBrokerAmqpPortIsOpen() throws Exception {
        GenericContainer<?> artemis = NiordTestContainers.artemis();
        assertTrue(artemis.isRunning(), "Artemis container should be running");
        assertTrue(brokerAmqpUrl().startsWith("amqp://"), "broker URL should be an AMQP URL");
        assertSocketOpen(artemis.getHost(), artemis.getMappedPort(5672));
    }

    @Test
    void mailhogSmtpPortIsOpen() throws Exception {
        assertTrue(NiordTestContainers.mailhog().isRunning(), "Mailhog container should be running");
        assertSocketOpen(smtpHost(), smtpPort());
    }

    /** Opens a TCP socket to prove the service is listening on its (dynamic) mapped port. */
    private static void assertSocketOpen(String host, int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            assertTrue(socket.isConnected(), "expected an open socket to " + host + ":" + port);
        }
    }
}
