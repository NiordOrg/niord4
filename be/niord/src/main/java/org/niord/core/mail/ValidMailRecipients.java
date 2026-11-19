/*
 * Copyright 2016 Danish Maritime Authority.
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

import jakarta.mail.Address;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Instantiated with a comma-separated list of valid recipients, this class can
 * be used to filter an array of mail recipients into valid recipients.
 * <p>
 *     If the special token "ALL" is used as the {@code validMailRecipients}
 *     parameter, then allValid recipients are valid. Use this in production.
 * </p>
 * <p>
 *     If the special token "LOG" is used as the {@code validMailRecipients}
 *     parameter, then mails will only be logged, not actually sent.
 * </p>
 * <p>
 *     Otherwise, if an invalid recipient is encountered, the first mail address
 *     in the {@code validMailRecipients} list is used instead.
 * </p>
 */
class ValidMailRecipients {

    public static final String ALL_RECIPIENTS_VALID_TOKEN = "ALL";
    public static final String LOG_VALID_TOKEN = "LOG";

    private Set<String> validAddresses = new LinkedHashSet<>();
    private boolean allValid;
    private boolean simulate;

    /**
     * Constructor
     * @param validMailRecipients either "ALL" or comma-separated list of valid recipients
     */
    public ValidMailRecipients(String validMailRecipients) {
        if (ALL_RECIPIENTS_VALID_TOKEN.equalsIgnoreCase(validMailRecipients)) {
            allValid = true;
        } else if (LOG_VALID_TOKEN.equalsIgnoreCase(validMailRecipients)) {
            allValid = true;
            simulate = true;
        } else {
            for (String r : validMailRecipients.split(",")) {
                validAddresses.add(r.trim().toLowerCase());
            }
        }
    }

    /**
     * Returns if sending emails should only be simulated
     * @return if sending emails should only be simulated
     */
    public boolean simulate() {
        return simulate;
    }

    /**
     * Filters the address and ensures that it is a valid address
     * @param addr the address to filter
     * @return the valid address
     */
    public Address filter(String addr) throws AddressException {

        // Check that it can be parsed as a single email address
        InternetAddress addrs[] = InternetAddress.parse(addr);
        if (addrs.length != 1) {
            throw new AddressException("Invalid recipient: " + addr);
        }

        return filter(addrs[0]);
    }

    /**
     * Filters the address and ensures that it is a valid address
     * @param addr the address to filter
     * @return the valid address
     */
    public Address filter(InternetAddress addr) throws AddressException {

        if (addr == null) {
            throw new AddressException("Undefined recipient");
        }

        addr.validate();

        if (reachesItself(addr)) {
            return addr;

        } else {
            // Return the first valid email address
            try {
                return new InternetAddress(validAddresses.iterator().next(), "(" + addr + ")");
            } catch (UnsupportedEncodingException e) {
                throw new AddressException("Invalid recipient with no substitute address: " + addr);
            }
        }
    }

    /**
     * Whether {@link #filter(InternetAddress)} redirects the address to the first listed address: a valid address that
     * a list of addresses does not name. Under ALL and LOG no address is redirected, and an address that is not valid
     * is not redirected either: the filter refuses it. The filter decides by the same rule.
     *
     * @param addr the address to check
     * @return whether the filter redirects the address
     */
    public boolean redirects(InternetAddress addr) {
        if (addr == null) {
            return false;
        }
        try {
            addr.validate();
        } catch (AddressException e) {
            return false;
        }
        return !reachesItself(addr);
    }

    /** Whether a valid address is let through as it is: always under ALL and LOG, and under a list when it is listed */
    private boolean reachesItself(InternetAddress addr) {
        return allValid || validAddresses.contains(addr.getAddress().toLowerCase());
    }
}
