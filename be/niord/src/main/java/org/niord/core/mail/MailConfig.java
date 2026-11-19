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

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;

/**
 * The mail session every mail is sent through ({@code Transport.send} in {@link MailService}).
 * <p>
 * Besides the SMTP server's host and port, the session bounds each exchange with the server: a connect, one read
 * and one write may each take at most the given number of milliseconds ({@code niord.smtp.connection-timeout-ms},
 * {@code niord.smtp.timeout-ms}, {@code niord.smtp.write-timeout-ms}). Jakarta Mail's own default for all three is
 * no limit, so an SMTP server that accepts the connection and then stops answering would otherwise hold
 * {@code Transport.send}, and with it the mail queue, for good.
 * <p>
 * For a relay that asks for them, the session also takes a login and TLS, both off when unset:
 * <ul>
 *     <li>{@code niord.smtp.username} and {@code niord.smtp.password}: with a user name, the session authenticates
 *     with these (the password comes from the environment, {@code NIORD_SMTP_PASSWORD}, and is never logged);</li>
 *     <li>{@code niord.smtp.tls}: {@code none} (the default), {@code starttls} (STARTTLS required before the login
 *     and the mail, usually port 587) or {@code ssl} (implicit TLS, usually port 465), in any case. Another value
 *     stops the start, rather than sending in clear text. In both TLS modes the relay's certificate is checked,
 *     against the JVM's trust store, and its name against the host.</li>
 * </ul>
 * A login with the TLS mode {@code none}, which sends it in clear text, and a user name with a blank password are
 * each logged as a WARN at the start, which goes on: a relay on a trusted network may be meant.
 */
// PORT: was a CDI @Component with a @Produces method; per §6 the producer moves to
// an @Configuration class exposing the Session via a @Bean method.
@Configuration
public class MailConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MailConfig.class);

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

    @Value("${niord.smtp.username:}")
    String username = "";

    @Value("${niord.smtp.password:}")
    String password = "";

    @Value("${niord.smtp.tls:none}")
    String tls = "none";

    @Bean
    public Session createMailSession() {
        Properties props = sessionProperties();
        configurationWarnings().forEach(log::warn);
        if (StringUtils.isBlank(username)) {
            return Session.getInstance(props);
        }
        String user = username;
        String pass = StringUtils.defaultString(password);
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });
    }

    /**
     * The properties of the mail session, from the fields above.
     *
     * @throws IllegalStateException when {@code niord.smtp.tls} is not none, starttls or ssl
     */
    Properties sessionProperties() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.connectiontimeout", String.valueOf(connectionTimeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(readTimeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(writeTimeoutMs));

        String mode = tlsMode();
        switch (mode) {
            case "none" -> { }
            case "starttls" -> {
                // Required, so a relay that stops offering STARTTLS fails the send instead of getting the login and
                // the mail in clear text
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case "ssl" -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            default -> throw new IllegalStateException(
                    "niord.smtp.tls is '" + tls + "': none, starttls or ssl");
        }

        if (StringUtils.isNotBlank(username)) {
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.user", username);
        }
        return props;
    }

    /** The TLS mode as configured, trimmed and in lower case; {@code none} when unset */
    private String tlsMode() {
        return StringUtils.defaultIfBlank(tls, "none").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * What the start logs as a WARN about the login, without stopping it: a login sent to the relay without TLS
     * (a relay on a trusted network may be meant), and a user name with a blank password. The password itself is
     * never named.
     */
    List<String> configurationWarnings() {
        List<String> warnings = new ArrayList<>();
        if (StringUtils.isNotBlank(username)) {
            if ("none".equals(tlsMode())) {
                warnings.add("The mail login (niord.smtp.username) is sent to the relay without TLS: set "
                        + "niord.smtp.tls to starttls or ssl, unless the relay is on a trusted network");
            }
            if (StringUtils.isBlank(password)) {
                warnings.add("niord.smtp.username is set and niord.smtp.password is blank: the mail login is sent "
                        + "with an empty password");
            }
        }
        return warnings;
    }
}
