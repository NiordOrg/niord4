#!/bin/sh
# Checks the deployment's settings, renders /etc/nginx/templates/*.template into
# /etc/nginx/http.d/ and hands over to nginx. The image runs as the unprivileged user nginx, so
# http.d is a link to /tmp/niord-http.d, which this script creates: /tmp is the one place the
# container writes to.
#
# The official nginx image does this in its own entrypoint; this image is built on the Alpine
# nginx package, because that is the one with a brotli module, and the package has no entrypoint
# of its own. The rule that matters is copied from the official script: only names that are
# actually in the environment are substituted, so nginx's own $uri, $host and $remote_addr pass
# through envsubst untouched and a typo in a ${NAME} stays visible in the rendered file instead of
# turning into an empty string.
#
# Both values checked below end up inside the nginx configuration, so a value that does not have
# the expected shape stops the container here, with a message that names the variable, rather
# than as an nginx syntax error about a line the operator never wrote.
set -e

template_dir=/etc/nginx/templates
# The target of the image's /etc/nginx/http.d link, which nginx.conf includes.
output_dir=/tmp/niord-http.d

fail() {
    echo "niord-info: $*" >&2
    exit 1
}

# Whether the whole of $1 matches the extended regular expression $2. awk reads both from the
# environment, where no escape is interpreted, and anchors ^ and $ on the whole string, so a
# value with a line break in it cannot pass on its first line alone.
matches() {
    NIORD_CHECK_VALUE="$1" NIORD_CHECK_PATTERN="$2" \
        awk 'BEGIN { exit !(ENVIRON["NIORD_CHECK_VALUE"] ~ ENVIRON["NIORD_CHECK_PATTERN"]) }'
}

# NIORD_SERVER_URL — the Niord backend, as scheme://host[:port]. Required: an image that fell
# back to some default backend would serve that backend's warnings under this site's name. One
# trailing slash is dropped, since that is how an address is often copied; a path is refused,
# because the allow-listed locations append the backend's own /rest/… paths to this value and
# the one message PDF passes the request line on unchanged. A host name with an underscore, as a
# Compose service name can have, is refused as well: the backend is sent this name as its Host
# header, and its web server answers every request whose Host holds one with 400.
[ -n "${NIORD_SERVER_URL:-}" ] ||
    fail "NIORD_SERVER_URL is not set. It names the Niord backend, as http(s)://host[:port]."
NIORD_SERVER_URL="${NIORD_SERVER_URL%/}"
matches "$NIORD_SERVER_URL" '^https?://([A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(:[0-9]+)?$' ||
    fail "NIORD_SERVER_URL must be the backend's address as http(s)://host[:port], with no path," \
        "query or credentials; got '$NIORD_SERVER_URL'."
export NIORD_SERVER_URL

# NIORD_TEASER_FRAME_ANCESTORS — the pages allowed to embed the teaser, as the source list of a
# Content-Security-Policy frame-ancestors directive: `*`, `'none'`, `'self'` or origins such as
# https://www.example.org, separated by spaces.
[ -n "${NIORD_TEASER_FRAME_ANCESTORS:-}" ] ||
    fail "NIORD_TEASER_FRAME_ANCESTORS is empty. It lists the pages allowed to embed the teaser;" \
        "* allows every page, 'none' allows none."
matches "$NIORD_TEASER_FRAME_ANCESTORS" "^[A-Za-z0-9 .:/*'_-]+$" ||
    fail "NIORD_TEASER_FRAME_ANCESTORS may hold only origins, *, 'self' and 'none', separated by" \
        "spaces; got '$NIORD_TEASER_FRAME_ANCESTORS'."

if [ -d "$template_dir" ]; then
    mkdir -p "$output_dir"
    defined_envs=$(awk 'END { for (name in ENVIRON) printf "${%s} ", name }' </dev/null)
    for template in "$template_dir"/*.template; do
        [ -f "$template" ] || continue
        output="$output_dir/$(basename "$template" .template)"
        echo "niord-info: rendering $template -> $output"
        envsubst "$defined_envs" <"$template" >"$output"
    done
fi

exec "$@"
