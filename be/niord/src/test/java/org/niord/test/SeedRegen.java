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
package org.niord.test;

import org.niord.core.bootstrap.BootstrapArchive;
import org.niord.core.bootstrap.BootstrapRefusedException;
import org.niord.core.bootstrap.BootstrapRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the test application on a new database, bootstraps it with the archives and exits with the outcome:
 * 0 when the bootstrap was applied, 1 otherwise. The one caller is {@code niord/scripts/regen-test-seed-postgres.sh},
 * which gives it one archive, the base product's, then loads the sample with the sample's own loader
 * ({@code niord/scripts/load-sample.sh}) and dumps the database, the integration suite's fixture
 * (docs/code-data-config-plan.md §7.2, §7.4).
 *
 * <p>It runs the bootstrap runner in-process, with no token, through the same JBeret jobs and waits as the
 * bootstrap command. Nothing in an application context starts it, so a deployment has no property-triggered start
 * path (D1).
 *
 * <p>Usage: {@code SeedRegen <archive.zip>... [--spring.property=value ...]}: the archives in the order they are
 * applied, and the Spring arguments, which start with {@code --}.
 */
public final class SeedRegen {

    private SeedRegen() {
    }

    public static void main(String[] args) {
        List<Path> archives = new ArrayList<>();
        List<String> springArgs = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                springArgs.add(arg);
            } else {
                archives.add(Path.of(arg));
            }
        }
        if (archives.isEmpty()) {
            System.err.println("SeedRegen <archive.zip>... [--spring.property=value ...]: no archive given");
            System.exit(2);
        }

        ConfigurableApplicationContext context =
                SpringApplication.run(NiordTestApplication.class, springArgs.toArray(String[]::new));
        int code;
        try {
            List<BootstrapArchive> read = new ArrayList<>();
            for (Path archive : archives) {
                read.add(BootstrapArchive.read(archive));
            }
            BootstrapRunner.Outcome outcome = context.getBean(BootstrapRunner.class).run(read);
            System.out.println("SeedRegen: bootstrap " + outcome.state()
                    + (outcome.error() != null ? ": " + outcome.error() : ""));
            code = outcome.applied() ? 0 : 1;
        } catch (BootstrapRefusedException e) {
            System.err.println("SeedRegen: bootstrap refused: " + e.getMessage());
            code = 1;
        } catch (Exception e) {
            System.err.println("SeedRegen: " + e);
            e.printStackTrace();
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
