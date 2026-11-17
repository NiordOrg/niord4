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
package org.niord.core.message;

/**
 * Discriminates ordinary editorial messages from system-managed messages such as the
 * auto-created S-124 cancellation warnings.
 * <p>
 * The kind is orthogonal to {@link org.niord.model.message.Type}: a cancellation warning keeps
 * the type (and thereby the scope - local, coastal, sub-area, NAVAREA) of the warning it
 * cancels, and the S-124 {@code warningType} derivation is unaffected. The kind is deliberately
 * not part of the public {@code org.niord.model} API - it is exposed via
 * {@link org.niord.core.message.vo.SystemMessageVo} only.
 * <p>
 * See {@code docs/s124-cancellation-plan.md}.
 */
public enum MessageKind {

    /** An ordinary editorial message - the default for all messages */
    WARNING,

    /**
     * An auto-created cancellation warning (S-124 Ed. 2.0.0 section 9.3, method 2): a published
     * no-parts message whose single CANCELLATION reference points at the warning it cancels.
     * Read-only after creation, and never itself the source of another cancellation warning.
     */
    CANCELLATION,

    /** Reserved for in-force bulletins - not implemented yet */
    BULLETIN
}
