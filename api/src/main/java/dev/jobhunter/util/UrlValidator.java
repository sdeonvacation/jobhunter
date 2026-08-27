package dev.jobhunter.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Lightweight URL predicates used at ingest time to short-circuit jobs whose apply
 * URLs can never be fetched or enriched by the pipeline. Keeping these checks in
 * one utility avoids spreading scheme/host string handling across services.
 */
public final class UrlValidator {

    private UrlValidator() {}

    /**
     * Hosts whose career pages are gated SPAs (login walls, JS-only shells, or
     * no public job-description API). Any aggregator job pointing here will be
     * rejected at ingest; fetching returns an empty shell that defeats both
     * description extraction and liveness checks.
     */
    public static final Set<String> KNOWN_UNENRICHABLE_HOSTS = Set.of(
            "careers.ibm.com",
            "jobs.merck.com",
            "careers.microsoft.com",
            "jobs.cisco.com"
    );

    /**
     * Returns true only for URLs the pipeline can actually fetch over HTTP(S).
     * Catches {@code mailto:}, {@code javascript:}, {@code tel:}, {@code file:},
     * and other non-fetchable schemes that occasionally leak through aggregator
     * feeds.
     */
    public static boolean isFetchable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /**
     * Returns true if the URL's host is in {@link #KNOWN_UNENRICHABLE_HOSTS}.
     * Comparison is case-insensitive. Returns false on malformed URLs rather
     * than throwing — callers treat both as "not unenrichable".
     */
    public static boolean isKnownUnenrichable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String host = new URI(url).getHost();
            return host != null && KNOWN_UNENRICHABLE_HOSTS.contains(host.toLowerCase());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
