// Keycloak silent check-sso callback: report the redirect URL back to the app.
// External file (not inline) so the Content-Security-Policy can stay at script-src 'self'.
parent.postMessage(location.href, location.origin);
