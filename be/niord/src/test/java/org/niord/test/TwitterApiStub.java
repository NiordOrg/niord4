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

import org.niord.test.http.HttpStub;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A JVM-wide, started-once HTTP stub that masquerades as the Twitter REST API and redirects the
 * twitter4j client at it, so the success path of {@code TwitterPromulgationService.promulgateMessage}
 * can be driven and asserted from an integration test.
 *
 * <p><b>The Spring replacement for {@code TwitterApiStubResource}.</b> The Quarkus original was a
 * {@code QuarkusTestResourceLifecycleManager} annotated with {@code restrictToAnnotatedClass=true}:
 * Quarkus stood the stub up — and set the {@code twitter4j.restBaseURL} system property — <em>before</em>
 * booting a fresh, classloader-isolated application for the single Twitter HTTP test class, then tore it
 * down afterwards. Under Spring there is no per-class application nor per-class classloader: every
 * integration test shares one cached {@code @SpringBootTest} context in one JVM, and the
 * {@code TwitterPromulgationService} bean (with its {@code new TwitterFactory()} field) is created once
 * when that context first refreshes. That single difference dictates this design — see below.
 *
 * <p><b>Why the property must be set this early (the freeze).</b> twitter4j 4.0.6 reads
 * {@code twitter4j.restBaseURL} exactly once, in the static initializer of
 * {@code twitter4j.conf.PropertyConfiguration} (its {@code ROOT_CONFIGURATION} singleton), and that value
 * is frozen for the rest of the JVM: {@code new TwitterFactory()} → {@code ConfigurationContext.getInstance()}
 * → that frozen root config. So the property has to be in place <em>before twitter4j class-loads</em>,
 * which happens the first time any context creates the {@code TwitterPromulgationService} bean — i.e.
 * potentially during the very first integration test, whichever class that is. A per-class JUnit
 * {@code @BeforeAll} (which runs after Spring has already built the context) would be too late, and the
 * ephemeral stub port is only known at runtime, so a fixed property value will not do either.
 *
 * <p><b>How this guarantees the ordering.</b> {@link #bootstrap()} is invoked from the static initializer
 * of {@link ITTest}, the base class of <em>every</em> integration test. A class's static initializer runs
 * when the class is first loaded, and JUnit loads a test class before the {@code SpringExtension} builds
 * its context — so this stub is started, and the property set, strictly before any Spring context (and
 * therefore before any {@code TwitterFactory}/twitter4j) comes into existence in the JVM. The stub binds an
 * ephemeral port and its URL is read into the property immediately, all still ahead of twitter4j's load.
 *
 * <p><b>Lifetime and isolation.</b> The stub is a started-once singleton kept for the whole test run and
 * closed by a JVM shutdown hook (mirroring the Testcontainers singletons in {@link NiordTestContainers}).
 * Pointing the global, frozen twitter4j base URL at a stub for the entire run is harmless: every other
 * promulgation test stops at a degraded branch that never reaches {@code twitter.updateStatus(...)}, so the
 * stub records nothing for them; only the Twitter ITs publish a fully-valid, active promulgation that
 * actually posts. Each Twitter IT brackets its assertions with the stub's {@code requestCount()} baseline,
 * so they remain independent of one another and of run order.
 *
 * <p>This is a framework-coupled test fixture — see docs/framework-coupled-tests.md. The fixture itself
 * ({@link HttpStub}) is framework-neutral; only the "set the system property before the framework's bean
 * graph is built" requirement is coupled to how the host framework boots, and is handled here rather than
 * leaking into the tests.
 */
public final class TwitterApiStub {

    /** twitter4j 4.0.6 composes request URLs as {@code <restBaseURL>statuses/update.json}. */
    static final String TWITTER_BASE_URL_PROPERTY = "twitter4j.restBaseURL";

    private static volatile HttpStub stub;

    private TwitterApiStub() {
    }

    /**
     * Starts the stub and binds the twitter4j base URL system property to it, exactly once per JVM.
     * Idempotent and safe to call from multiple threads; subsequent calls are no-ops.
     */
    static synchronized void bootstrap() {
        if (stub != null) {
            return;
        }
        HttpStub started;
        try {
            started = HttpStub.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the Twitter API stub", e);
        }
        // Reply to every request with a minimal, valid status-update document so the twitter4j client
        // treats the call as a successful tweet rather than an error.
        started.respond(req -> HttpStub.StubResponse.json("{\"id\":1,\"id_str\":\"1\",\"text\":\"ok\"}"));

        // twitter4j composes request URLs as <restBaseURL>statuses/update.json, so the base URL must end
        // with a slash. The /1.1/ segment mirrors the real Twitter REST API versioning.
        System.setProperty(TWITTER_BASE_URL_PROPERTY, started.baseUrl() + "/1.1/");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.clearProperty(TWITTER_BASE_URL_PROPERTY);
            started.close();
        }, "twitter-api-stub-shutdown"));

        stub = started;
    }

    /**
     * The running stub the Twitter ITs assert against. Bootstraps it on demand if it has not been started
     * yet (it normally has, via {@link ITTest}'s static initializer), so the accessor never returns null.
     */
    public static HttpStub stub() {
        if (stub == null) {
            bootstrap();
        }
        return stub;
    }
}
