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

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring replacement for {@code io.quarkus.narayana.jta.Tx} in the integration
 * tests: opens a fresh ({@code REQUIRES_NEW}) committed transaction so data written by the test
 * setup is visible to subsequent HTTP requests / service calls. Same fluent shape the ITs used —
 * {@code Tx.requiringNew().run(() -> …)} / {@code .call(() -> …)} — so the port is a class-name swap.
 *
 * <p>The transaction template is bound once per IT context by {@link ITTest}.
 */
public final class Tx {

    private static volatile PlatformTransactionManager txManager;

    static void init(PlatformTransactionManager transactionManager) {
        txManager = transactionManager;
    }

    public static Tx requiringNew() {
        return new Tx();
    }

    private static TransactionTemplate template() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /** The work may throw a checked exception (as the Quarkus variant allowed); it is rethrown unchecked. */
    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }

    @FunctionalInterface
    public interface ThrowingSupplier<T> { T get() throws Exception; }

    public void run(ThrowingRunnable work) {
        template().executeWithoutResult(status -> invoke(() -> { work.run(); return null; }));
    }

    public <T> T call(ThrowingSupplier<T> work) {
        return template().execute(status -> invoke(work));
    }

    private static <T> T invoke(ThrowingSupplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
