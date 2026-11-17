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
 *
 */
// PORT: was a CDI @Component with a @Produces method; per §6 the producer moves to
// an @Configuration class exposing the Session via a @Bean method.
@Configuration
public class MailConfig {

    @Value("${niord.smtp.host}")
    String host;

    @Value("${niord.smtp.port}")
    int port;

    @Bean
    public Session createMailSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        return Session.getInstance(props);
    }
}
