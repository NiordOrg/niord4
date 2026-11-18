/*
 * Copyright (c) 2008 Kasper Nielsen.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.core.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

import jakarta.mail.Session;

/**
 * The mail session every mail is sent through ({@code Transport.send} in {@link MailService}).
 * <p>
 * Besides the SMTP server's host and port, the session bounds each exchange with the server: a connect, one read
 * and one write may each take at most the given number of milliseconds ({@code niord.smtp.connection-timeout-ms},
 * {@code niord.smtp.timeout-ms}, {@code niord.smtp.write-timeout-ms}). Jakarta Mail's own default for all three is
 * no limit, so an SMTP server that accepts the connection and then stops answering would otherwise hold
 * {@code Transport.send}, and with it the mail queue, for good.
 */
// PORT: was a CDI @Component with a @Produces method; per §6 the producer moves to
// an @Configuration class exposing the Session via a @Bean method.
@Configuration
public class MailConfig {

    @Value("${niord.smtp.host}")
    String host;

    @Value("${niord.smtp.port}")
    int port;

    @Value("${niord.smtp.connection-timeout-ms:30000}")
    int connectionTimeoutMs = 30000;

    @Value("${niord.smtp.timeout-ms:60000}")
    int readTimeoutMs = 60000;

    @Value("${niord.smtp.write-timeout-ms:60000}")
    int writeTimeoutMs = 60000;

    @Bean
    public Session createMailSession() {
        return Session.getInstance(sessionProperties());
    }

    /** The properties of the mail session, from the fields above. */
    Properties sessionProperties() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.connectiontimeout", String.valueOf(connectionTimeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(readTimeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(writeTimeoutMs));
        return props;
    }
}
