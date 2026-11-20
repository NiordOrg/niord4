/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.integration;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.protocol.HttpContext;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Validates the URL of a Niord integration point, so that the message importer cannot be pointed at
 * an arbitrary endpoint inside the network the server itself runs in.
 * <p>
 * The validation is split in two. {@link #validateUrl(String)} is a pure syntax check, used when an
 * integration point is created or updated, and again before a fetch. It never resolves DNS, so an
 * integration point can be defined before the remote Niord server exists. The address check instead
 * lives in the {@link #dnsResolver(boolean)} handed to the HTTP client: the addresses it approves are
 * the very ones the client connects to, so there is no window between check and connect (DNS
 * rebinding), no disagreement between how the check and the connect read an address literal, and any
 * redirect the client follows is checked exactly like the original request. The client follows no redirect from
 * https to http ({@link #redirectStrategy()}).
 */
@SuppressWarnings("unused")
public class NiordIntegrationUrlValidator {

    private NiordIntegrationUrlValidator() {
    }

    /**
     * Validates the syntax of the URL of a Niord integration point.
     * <p>
     * Only absolute http and https URLs with a host name and without user-info are accepted. User-info
     * would be passed to the remote server as credentials and be logged along with the URL.
     * <p>
     * A query or fragment is rejected too, because the caller appends the endpoint path to this URL as a
     * raw string: {@code "https://peer/x?a=b"} composes to a URL whose path is {@code /x} and whose query
     * swallows {@code /rest/public/v1/messages}, and {@code "https://peer#f"} composes to one with an
     * EMPTY path — the fetch silently hits the peer's root and parses whatever it returns as messages.
     * Neither is meaningful on the base URL of a Niord server, so rejecting is better than guessing.
     *
     * @param url the Niord integration point URL to validate
     */
    public static void validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(StringUtils.trimToEmpty(url));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Niord integration URL: " + withoutUserInfo(url));
        }

        // First, and without the URL: the message is returned to the caller and logged by RestExceptionStatusAdvice,
        // and a URL with user info carries a credential, whatever else is wrong with it
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Niord integration URL must not contain user info");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Niord integration URL must be http or https: " + url);
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("Niord integration URL must contain a valid host name: "
                    + withoutUserInfo(url));
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Niord integration URL must not contain a query or fragment: " + url);
        }
    }

    /** User info before a host ({@code user:password@}) as it stands in a URL */
    private static final Pattern USER_INFO = Pattern.compile("(?<=//)[^/?#]*@");

    /**
     * The URL without the user info in front of its host, for a message that names a URL the parser could not read
     * (which says nothing of its user info) or that has no host (where a {@code user:password@} may stand)
     */
    static String withoutUserInfo(String url) {
        return url == null ? null : USER_INFO.matcher(url).replaceFirst("");
    }

    /**
     * Returns the redirect strategy used when fetching messages: the default one (a GET follows 301, 302, 303 and 307),
     * except that a redirect from https to another scheme is refused, so a fetch that starts on TLS never ends
     * without it. A redirect from http to https is followed, as a server that sends its plain address on to TLS
     * does.
     *
     * @return the redirect strategy to use when fetching messages
     */
    public static RedirectStrategy redirectStrategy() {
        return new DefaultRedirectStrategy() {
            @Override
            public URI getLocationURI(HttpRequest request, HttpResponse response, HttpContext context)
                    throws ProtocolException {
                URI location = super.getLocationURI(request, response, context);
                if ("https".equalsIgnoreCase(schemeOf(request, context))
                        && !"https".equalsIgnoreCase(location.getScheme())) {
                    throw new ProtocolException("Niord integration: a redirect from https to "
                            + location.getScheme() + " is not followed");
                }
                return location;
            }
        };
    }

    /** The scheme of the request being redirected: its own, if its URI is absolute, else its target host's */
    private static String schemeOf(HttpRequest request, HttpContext context) {
        try {
            URI uri = new URI(request.getRequestLine().getUri());
            if (uri.getScheme() != null) {
                return uri.getScheme();
            }
        } catch (URISyntaxException ignored) {
            // Read from the target host below
        }
        HttpHost target = HttpClientContext.adapt(context).getTargetHost();
        return target == null ? null : target.getSchemeName();
    }

    /**
     * Returns the DNS resolver used when fetching messages, that is, the system resolver with every
     * resolved address checked by {@link #isNonPublicAddress(InetAddress)}.
     *
     * @param allowPrivateHosts whether the importer may fetch from non-public addresses
     * @return the DNS resolver to use when fetching messages
     */
    public static DnsResolver dnsResolver(boolean allowPrivateHosts) {
        return host -> {
            InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
            if (!allowPrivateHosts) {
                for (InetAddress address : addresses) {
                    if (isNonPublicAddress(address)) {
                        throw new UnknownHostException("Niord integration host " + host
                                + " resolves to the non-public address " + address.getHostAddress());
                    }
                }
            }
            return addresses;
        };
    }

    /**
     * Returns whether the given address is one the importer must never connect to: the wildcard and
     * loopback addresses, the link-local range - which includes the 169.254.169.254 cloud metadata
     * service - the private IPv4 ranges, the IPv6 unique local addresses and the multicast ranges,
     * plus the reserved IPv4 ranges listed in {@link #isReservedIpv4(byte[])}.
     * <p>
     * Address literals in an unusual notation - the decimal form "2130706433", the short form "127.1",
     * IPv4-mapped IPv6 such as "::ffff:127.0.0.1" - need no handling of their own: they have all been
     * parsed into an {@link InetAddress} by the time they get here. The two IPv6 notations that wrap an
     * arbitrary IPv4 address do need handling, because the JDK does not collapse them the way it
     * collapses "::ffff:x" - see {@link #isNonPublicIpv6(Inet6Address)}.
     * <p>
     * Public because the SECOM outbound client applies the same rule to the peer endpoints the
     * service registry returns ({@code SecomOutboundClient.requirePublicAddresses}).
     *
     * @param address the address to check
     * @return whether the address is a non-public one
     */
    public static boolean isNonPublicAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || (address instanceof Inet6Address a6 ? isNonPublicIpv6(a6) : isReservedIpv4(address.getAddress()));
    }

    /**
     * Returns whether the given IPv6 address is one the importer must never connect to. Beyond the
     * unique local addresses (fc00::/7) this covers the two notations that wrap an arbitrary IPv4
     * address in an IPv6 one - the NAT64 well-known prefix 64:ff9b::/96 and 6to4's 2002::/16 - which
     * the JDK does NOT collapse to an {@link java.net.Inet4Address} the way it collapses "::ffff:x",
     * so "64:ff9b::7f00:1" would otherwise reach 127.0.0.1 unchecked.
     *
     * @param address the IPv6 address to check
     * @return whether the address is a non-public one
     */
    private static boolean isNonPublicIpv6(Inet6Address address) {
        byte[] b = address.getAddress();
        if ((b[0] & 0xFE) == 0xFC) {                                    // fc00::/7 - unique local
            return true;
        }
        byte[] embedded = null;
        if (b[0] == 0x00 && b[1] == 0x64 && b[2] == (byte) 0xFF && b[3] == (byte) 0x9B) {
            embedded = new byte[]{b[12], b[13], b[14], b[15]};          // 64:ff9b::/96 - NAT64
        } else if (b[0] == 0x20 && b[1] == 0x02) {
            embedded = new byte[]{b[2], b[3], b[4], b[5]};               // 2002::/16 - 6to4
        }
        if (embedded == null) {
            return false;
        }
        try {
            return isNonPublicAddress(InetAddress.getByAddress(embedded));
        } catch (UnknownHostException e) {
            return true;    // a four-byte array cannot fail; refuse to connect if it somehow does
        }
    }

    /**
     * Returns whether the given four-byte IPv4 address falls in a reserved range that
     * {@link InetAddress}'s own predicates do not cover: 0.0.0.0/8 (which Linux routes to loopback,
     * so "0.1.2.3" reaches a local service), 100.64.0.0/10 (carrier-grade NAT), 192.0.0.0/24 (IETF
     * protocol assignments), 198.18.0.0/15 (benchmarking) and 240.0.0.0/4 (reserved).
     *
     * @param b the four bytes of the IPv4 address
     * @return whether the address is in a reserved range
     */
    private static boolean isReservedIpv4(byte[] b) {
        if (b.length != 4) {
            return false;
        }
        int o1 = b[0] & 0xFF;
        int o2 = b[1] & 0xFF;
        return o1 == 0                                  // 0.0.0.0/8
                || (o1 == 100 && o2 >= 64 && o2 <= 127) // 100.64.0.0/10
                || (o1 == 192 && o2 == 0 && (b[2] & 0xFF) == 0)  // 192.0.0.0/24
                || (o1 == 198 && (o2 == 18 || o2 == 19))         // 198.18.0.0/15
                || o1 >= 240;                           // 240.0.0.0/4
    }
}
