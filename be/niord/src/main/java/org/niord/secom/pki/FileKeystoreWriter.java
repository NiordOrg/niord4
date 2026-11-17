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
package org.niord.secom.pki;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@link KeystoreWriter} for writable {@code file:} (or bare absolute path) store locations -
 * VM installs and the k8s {@code /niord-home} pattern. The write is atomic: the bytes go to a
 * temp file in the target directory (created with private permissions) and are moved over the
 * target in one rename, so a concurrent reload can never observe a torn store. There is
 * deliberately no non-atomic fallback - failing loudly beats a corrupted keystore. Always
 * writes binary PKCS12; {@code McpSecurityService} auto-detects binary vs base64 on load.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
public class FileKeystoreWriter implements KeystoreWriter {

    /** {@inheritDoc} */
    @Override
    public boolean supports(String location) {
        return McpSecurityService.isFileBacked(location);
    }

    /** {@inheritDoc} */
    @Override
    public void write(String location, byte[] keystoreBytes) throws Exception {
        Path target = toPath(location);
        Path temp = Files.createTempFile(target.getParent(), ".renewed-keystore-", ".tmp");
        try {
            Files.write(temp, keystoreBytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static Path toPath(String location) {
        return Path.of(location.startsWith("file:") ? location.substring("file:".length()) : location);
    }
}
