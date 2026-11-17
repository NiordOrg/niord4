# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------------------------
# niord-info — the anonymous public site, one image per country, packaging a built dist.
#
#   npm run build:info && npm run image:info -- --tag niord-info               # the base build, from fe/ (manual)
#   npm run build && npm run image:info -- --tag <registry>/<name>:<version>  # a country, from its fe/
#
# The build CONTEXT is never the workspace. It is a fresh folder that country-kit/image-info.mjs
# assembles from a built, checked and pre-compressed dist/niord-info/browser (as browser/) and the
# five nginx files beside this one (as nginx/), with this file; image-info.mjs refuses a
# non-production build, any source map and any file outside its output allow-list before it
# assembles the folder. Nothing is built here, so the image needs no Node.
#
# What keeps administration code out of the public site is the separation check
# (scripts/info-separation-check.mjs, run on every build, and by image:info against the stats of
# the build the image packages) and lint, not this file.
#
# The readers are at sea on a metered link, so every text file is compressed once, in the context,
# at the highest setting the format has — never per request. nginx then serves the .br to a client
# that asks for brotli, the .gz to one that asks for gzip, and the original to one that asks for
# neither.
# ---------------------------------------------------------------------------------------------

# ---------------------------------------------------------------------------------------------
# The server is the Alpine nginx package plus the Alpine brotli module: the official nginx image
# carries no brotli, and brotli is what makes the first visit affordable — a third smaller than
# gzip on the application's own stylesheet. /etc/nginx/modules/*.conf loads the module by itself.
# `gettext` brings envsubst, and the entrypoint does what the official image's template step does.
# `ca-certificates-bundle` is the CA bundle an https backend's certificate is verified against.
#
# ALPINE_IMAGE names the base: by default alpine:3.22 from Docker Hub. A country may name a mirror
# of the same image it has access to instead, such as registry.example.org/library/alpine:3.22;
# image:info passes ALPINE_IMAGE from its environment, and when it is unset the default stands.
# ---------------------------------------------------------------------------------------------
ARG ALPINE_IMAGE=alpine:3.22
FROM ${ALPINE_IMAGE}

# ---------------------------------------------------------------------------------------------
# The server runs as the unprivileged user `nginx`, uid and gid 101, never as root, so the image
# runs in a pod held to the restricted Pod Security standard. The user is created before the
# package, which then keeps it, so the id is fixed rather than whatever the package's own
# `adduser` picks. Everything nginx writes goes under /tmp: the server block the entrypoint
# renders (/etc/nginx/http.d is a link into /tmp), the pid file and the temporary files (named at
# the top of the server block), so a read-only root filesystem needs nothing but a writable /tmp.
# The pid file is named in conf.d/, which Alpine's nginx.conf includes at its top level (the
# package ships no such folder), so it holds for every nginx command, `nginx -t` or a replaced
# command line included. The package's nginx.conf loses its `user` line, which only a master
# process started as root can act on and which would otherwise put a warning in every log.
# ---------------------------------------------------------------------------------------------
RUN addgroup -S -g 101 nginx \
    && adduser -S -D -H -u 101 -h /var/lib/nginx -s /sbin/nologin -G nginx -g nginx nginx \
    && apk add --no-cache nginx nginx-mod-http-brotli gettext ca-certificates-bundle \
    && sed -i '/^user nginx;$/d' /etc/nginx/nginx.conf \
    && mkdir -p /etc/nginx/conf.d \
    && echo 'pid /tmp/nginx.pid;' > /etc/nginx/conf.d/niord-pid.conf \
    && rm -rf /etc/nginx/http.d \
    && ln -s /tmp/niord-http.d /etc/nginx/http.d \
    && ln -sf /dev/stdout /var/log/nginx/access.log \
    && ln -sf /dev/stderr /var/log/nginx/error.log

COPY --chmod=0755 nginx/entrypoint.sh /niord-entrypoint.sh
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
# Alpine's nginx.conf includes http.d/*.conf and conf.d/*.conf by itself, and the entrypoint
# writes the rendered server block into http.d/, which is /tmp/niord-http.d. This fragment
# belongs inside a location and not inside http or the root context, so it is kept out of both
# folders and included by name.
COPY nginx/niord-proxy.conf /etc/nginx/niord-proxy.conf
# The same for the security headers, which the server block and several locations include.
COPY nginx/niord-security-headers.conf /etc/nginx/niord-security-headers.conf
# The two headers every response carries whatever its policy, which the security headers and the
# sandboxed locations include.
COPY nginx/niord-common-headers.conf /etc/nginx/niord-common-headers.conf
COPY browser/ /usr/share/nginx/html/

# NIORD_SERVER_URL:             the Niord backend the allow-listed /rest/ paths are proxied to, as
#                               http(s)://host[:port]. It has no default: the container refuses to
#                               start without it (see the entrypoint).
# WMS_SERVER_URL:               the sea-chart tile server. Empty means "no WMS" (see the template).
# NIORD_TEASER_FRAME_ANCESTORS: the pages allowed to embed the teaser, as a CSP frame-ancestors
#                               source list. `*` keeps every page that embeds it today working.
ENV WMS_SERVER_URL=
ENV NIORD_TEASER_FRAME_ANCESTORS=*

# An unprivileged port: a user other than root may not listen below 1024.
EXPOSE 8080

USER 101:101

ENTRYPOINT ["/niord-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
