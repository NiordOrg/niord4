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
package org.niord.core.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mail session every mail is sent through ({@link MailConfig}): it bounds each exchange with the SMTP server, so
 * a server that accepts the connection and then goes silent fails the send instead of holding it, and with it the
 * mail queue, for good.
 */
class MailConfigTest {

    /** A config as Spring would build it from the defaults, for the given host and port */
    static MailConfig config(String host, int port) {
        MailConfig config = new MailConfig();
        config.host = host;
        config.port = port;
        return config;
    }

    @Test
    void theSessionBoundsEveryExchangeWithTheServer() {
        MailConfig config = config("smtp.example.com", 25);
        config.connectionTimeoutMs = 1111;
        config.readTimeoutMs = 2222;
        config.writeTimeoutMs = 3333;

        Properties props = config.createMailSession().getProperties();

        assertEquals("smtp.example.com", props.get("mail.smtp.host"));
        assertEquals("1111", props.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("2222", props.getProperty("mail.smtp.timeout"));
        assertEquals("3333", props.getProperty("mail.smtp.writetimeout"));
    }

    @Test
    void withoutSettingsTheSessionHasItsDefaultLimits() {
        Properties props = config("localhost", 1025).sessionProperties();

        assertEquals("30000", props.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("60000", props.getProperty("mail.smtp.timeout"));
        assertEquals("60000", props.getProperty("mail.smtp.writetimeout"));
    }

    // ------------------------------------------------------------------------------------------- //
    // The login and TLS, for a relay that asks for them
    // ------------------------------------------------------------------------------------------- //

    @Test
    void withoutALoginTheSessionAsksForNone() {
        Session session = config("localhost", 1025).createMailSession();
        Properties props = session.getProperties();

        assertNull(props.get("mail.smtp.auth"));
        assertNull(props.get("mail.smtp.user"));
        assertNull(props.get("mail.smtp.starttls.enable"));
        assertNull(props.get("mail.smtp.ssl.enable"));
        assertNull(session.requestPasswordAuthentication(null, 1025, "smtp", null, null),
                "the session has no authenticator");
    }

    @Test
    void aLoginTurnsAuthenticationOnAndTheAuthenticatorAnswersWithIt() {
        MailConfig config = config("relay.example.com", 587);
        config.username = "niord-relay";
        config.password = "it-not-a-real-password";

        Session session = config.createMailSession();
        Properties props = session.getProperties();

        assertEquals("true", props.getProperty("mail.smtp.auth"));
        assertEquals("niord-relay", props.getProperty("mail.smtp.user"));
        PasswordAuthentication login = session.requestPasswordAuthentication(null, 587, "smtp", null, "niord-relay");
        assertNotNull(login, "the session answers the relay's request for a login");
        assertEquals("niord-relay", login.getUserName());
        assertEquals("it-not-a-real-password", login.getPassword());
    }

    @Test
    void starttlsIsRequiredNotOptional() {
        MailConfig config = config("relay.example.com", 587);
        config.tls = "STARTTLS";

        Properties props = config.sessionProperties();

        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", props.getProperty("mail.smtp.starttls.required"),
                "a relay that stops offering STARTTLS fails the send instead of getting the mail in clear text");
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNull(props.get("mail.smtp.ssl.enable"));
    }

    @Test
    void sslEnablesImplicitTls() {
        MailConfig config = config("relay.example.com", 465);
        config.tls = "ssl";

        Properties props = config.sessionProperties();

        assertEquals("true", props.getProperty("mail.smtp.ssl.enable"));
        assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNull(props.get("mail.smtp.starttls.enable"));
    }

    @Test
    void aLoginWithoutTlsIsWarnedAbout_andTheStartGoesOn() {
        MailConfig config = config("relay.example.com", 25);
        config.username = "niord-relay";
        config.password = "it-not-a-real-password";

        List<String> warnings = config.configurationWarnings();

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("without TLS"), warnings.get(0));
        assertFalse(warnings.get(0).contains("it-not-a-real-password"), "the password is never named");
        assertNotNull(config.createMailSession(), "the start goes on");
    }

    @Test
    void aLoginWithABlankPasswordIsWarnedAbout() {
        MailConfig config = config("relay.example.com", 587);
        config.username = "niord-relay";
        config.password = " ";
        config.tls = "starttls";

        List<String> warnings = config.configurationWarnings();

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("niord.smtp.password is blank"), warnings.get(0));
    }

    @Test
    void aLoginOverTls_orNoLoginAtAll_isNotWarnedAbout() {
        MailConfig withTls = config("relay.example.com", 465);
        withTls.username = "niord-relay";
        withTls.password = "it-not-a-real-password";
        withTls.tls = "SSL";

        assertEquals(List.of(), withTls.configurationWarnings());
        assertEquals(List.of(), config("localhost", 1025).configurationWarnings(),
                "a local relay without a login and without TLS is the default, and no fault");
    }

    @Test
    void anUnknownTlsModeStopsTheStart() {
        MailConfig config = config("relay.example.com", 587);
        config.tls = "yes";

        IllegalStateException e = assertThrows(IllegalStateException.class, config::createMailSession);
        assertTrue(e.getMessage().contains("none, starttls or ssl"), e.getMessage());
    }

    /**
     * A server that accepts the connection and never sends its greeting: without a read limit on the session the
     * connect waits for good, and the preemptive timeout fails the test.
     */
    @Test
    void aServerThatAcceptsAndStaysSilentFailsTheSendInsteadOfHoldingIt() throws Exception {
        List<Socket> accepted = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket socket = server.accept();
                        synchronized (accepted) {
                            accepted.add(socket); // kept open, never written to
                        }
                    }
                } catch (IOException closed) {
                    // the server socket was closed at the end of the test
                }
            }, "silent-smtp-server");
            acceptor.setDaemon(true);
            acceptor.start();

            MailConfig config = config(InetAddress.getLoopbackAddress().getHostAddress(), server.getLocalPort());
            config.readTimeoutMs = 500;
            Session session = config.createMailSession();

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                try (Transport transport = session.getTransport("smtp")) {
                    assertThrows(MessagingException.class, transport::connect,
                            "a server that stays silent fails the connect within the read limit");
                }
            });
        } finally {
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    socket.close();
                }
            }
        }
    }
}
