package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.filter.geo.CityCountryResolver;
import dev.jobhunter.service.PersonalProfileLoader;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Hook/parse tests for {@link WissenschaftsstellenStrategy} against fixture HTML
 * (no network; the board hooks are invoked directly).
 */
class WissenschaftsstellenStrategyTest {

    private static final String BASE = "https://wissenschaftsstellen.de";
    private static final String DETAIL_URL =
            "https://wissenschaftsstellen.de/stelle/softwareentwickler-tu-berlin-42717";

    private final WissenschaftsstellenStrategy strategy = new WissenschaftsstellenStrategy(null);

    // ─── Metadata / listing URL ───────────────────────────────────────────────

    @Test
    @DisplayName("name() is the registry/config key")
    void name() {
        assertThat(strategy.name()).isEqualTo("wissenschaftsstellen");
    }

    @Test
    @DisplayName("buildListingUrl uses the root path with a seite page param and trims a trailing slash")
    void buildListingUrlShape() {
        assertThat(strategy.buildListingUrl(BASE, 3)).isEqualTo(BASE + "/?seite=3");
        assertThat(strategy.buildListingUrl(BASE + "/", 1)).isEqualTo(BASE + "/?seite=1");
    }

    // ─── JOBS=[...] listing parsing ───────────────────────────────────────────

    @Test
    @DisplayName("parses the inline JOBS array (50 records) and joins detail URLs by numeric id")
    void parsesFullListingPage() {
        StringBuilder array = new StringBuilder("[");
        List<String> anchors = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long id = 42717 - i;
            if (i > 0) {
                array.append(',');
            }
            array.append(jobJson(id, "Technik/Labor", "https://th-deg.de/apply/" + id));
            anchors.add("/stelle/softwareentwickler-tu-berlin-" + id);
        }
        array.append(']');

        List<BoardJob> jobs = strategy.parseListingJobs(
                listingHtml(array.toString(), anchors.toArray(new String[0])));

        assertThat(jobs).hasSize(50);
        BoardJob first = jobs.get(0);
        assertThat(first.externalId()).isEqualTo("42717");
        assertThat(first.title()).isEqualTo("Softwareentwickler (m/w/d)");
        assertThat(first.companyName()).isEqualTo("TU Berlin");
        assertThat(first.location()).isEqualTo("Berlin");
        assertThat(first.applyUrl()).isEqualTo("https://th-deg.de/apply/42717");
        assertThat(first.detailUrl()).isEqualTo(DETAIL_URL);
        assertThat(first.attributes()).containsEntry("kategorie", "Technik/Labor");
        assertThat(first.attributes()).containsEntry("entgeltgruppe", "E13");
    }

    @Test
    @DisplayName("skips malformed rows (missing id or title)")
    void skipsMalformedRows() {
        String array = "["
                + "{\"id\":1,\"titel\":\"Valid\",\"kategorie\":\"Technik/Labor\"},"
                + "{\"titel\":\"No id\",\"kategorie\":\"Technik/Labor\"},"
                + "{\"id\":3,\"kategorie\":\"Technik/Labor\"},"
                + "{\"id\":4,\"titel\":\"Second valid\",\"kategorie\":\"Technik/Labor\"}"
                + "]";

        List<BoardJob> jobs = strategy.parseListingJobs(listingHtml(array, "/stelle/a-1", "/stelle/b-4"));

        assertThat(jobs).extracting(BoardJob::externalId).containsExactly("1", "4");
    }

    @Test
    @DisplayName("sets detailUrl to null when no matching /stelle/ anchor exists but keeps the row")
    void missingAnchorKeepsRow() {
        String array = "["
                + "{\"id\":1,\"titel\":\"One\",\"kategorie\":\"Technik/Labor\"},"
                + "{\"id\":2,\"titel\":\"Two\",\"kategorie\":\"Technik/Labor\"}"
                + "]";

        List<BoardJob> jobs = strategy.parseListingJobs(listingHtml(array, "/stelle/one-1"));

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).detailUrl())
                .isEqualTo("https://wissenschaftsstellen.de/stelle/one-1");
        assertThat(jobs.get(1).detailUrl()).isNull();
    }

    @Test
    @DisplayName("throws ListingParseException when the JOBS= array is absent or the page is blank")
    void throwsWhenJobsArrayAbsent() {
        assertThatThrownBy(() -> strategy.parseListingJobs("<html><body>no jobs here</body></html>"))
                .isInstanceOf(UniversityBoardStrategy.ListingParseException.class);
        assertThatThrownBy(() -> strategy.parseListingJobs(null))
                .isInstanceOf(UniversityBoardStrategy.ListingParseException.class);
        assertThatThrownBy(() -> strategy.parseListingJobs("   "))
                .isInstanceOf(UniversityBoardStrategy.ListingParseException.class);
    }

    @Test
    @DisplayName("throws ListingParseException when the JOBS= payload is not valid JSON")
    void throwsWhenJobsArrayMalformed() {
        assertThatThrownBy(() -> strategy.parseListingJobs(listingHtml("not json", "/stelle/x-1")))
                .isInstanceOf(UniversityBoardStrategy.ListingParseException.class);
    }

    @Test
    @DisplayName("returns an empty list without throwing for a valid empty JOBS=[] array")
    void emptyJobsArrayReturnsEmptyList() {
        assertThat(strategy.parseListingJobs(listingHtml("[]"))).isEmpty();
    }

    // ─── Scope predicate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("matchesScope keeps only kategorie == Technik/Labor")
    void matchesScopeTechnikLaborOnly() {
        assertThat(strategy.matchesScope(listingJob("1", "Technik/Labor"))).isTrue();
        assertThat(strategy.matchesScope(listingJob("2", "Verwaltung"))).isFalse();
        assertThat(strategy.matchesScope(listingJob("3", null))).isFalse();
    }

    // ─── Detail mapping ───────────────────────────────────────────────────────

    @Test
    @DisplayName("parseDetail maps JSON-LD description while leaving postedDate null and keeping the date in rawJson")
    void parseDetailMapsJsonLd() {
        BoardJob job = listingJob("42717", "Technik/Labor");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(fullPostingJsonLd()), job);

        assertThat(mapped).isNotNull();
        assertThat(mapped.externalId()).isEqualTo("42717");
        assertThat(mapped.title()).isEqualTo("Softwareentwickler (m/w/d)");
        assertThat(mapped.companyName()).isEqualTo("TU Berlin");
        assertThat(mapped.location()).isEqualTo("Berlin, DE"); // listing bundesland + JSON-LD country, not HQ "Bonn"
        assertThat(mapped.description()).isEqualTo("Entwickle Software.");
        assertThat(mapped.postedDate()).isNull();
        assertThat(mapped.applyUrl()).isEqualTo("https://th-deg.de/apply/42717");
        assertThat(mapped.salaryMin()).isNull();
        assertThat(mapped.salaryMax()).isNull();
        assertThat(mapped.salaryCurrency()).isNull();
        assertThat(mapped.rawJson())
                .contains("\"baseSalary\"")
                .contains("MONTH")
                .contains("FULL_TIME")
                .contains("\"datePosted\":\"2026-09-11\"")
                .contains("E13");
    }

    @Test
    @DisplayName("JSON-LD datePosted never populates postedDate (aggregator sources surface via discoveredDate)")
    void postedDateStaysNullButRawJsonKeepsDatePosted() {
        BoardJob job = listingJob("42717", "Technik/Labor");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(fullPostingJsonLd()), job);

        assertThat(mapped.postedDate()).isNull();
        assertThat(mapped.rawJson()).contains("\"datePosted\":\"2026-09-11\"");
    }

    @Test
    @DisplayName("falls back to JSON-LD location when the listing bundesland is blank")
    void locationFallsBackToJsonLd() {
        BoardJob job = listingJobWith("42717", "Technik/Labor", null, "https://th-deg.de/apply/1");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(fullPostingJsonLd()), job);

        assertThat(mapped.location()).isEqualTo("Bonn");
    }

    @Test
    @DisplayName("appends the JSON-LD country to a bare state so the location becomes geo-resolvable")
    void locationComposesStateWithJsonLdCountry() {
        BoardJob job = listingJobWith("42717", "Technik/Labor", "Bayern", "https://th-deg.de/apply/1");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(postingWithLocation("DE", "München")), job);

        assertThat(mapped.location()).isEqualTo("Bayern, DE");
    }

    @Test
    @DisplayName("uses the JSON-LD country code verbatim instead of hardcoding Germany")
    void locationUsesJsonLdCountryNotHardcoded() {
        BoardJob job = listingJobWith("42717", "Technik/Labor", "Berlin", "https://th-deg.de/apply/1");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(postingWithLocation("AT", "Wien")), job);

        assertThat(mapped.location()).isEqualTo("Berlin, AT");
    }

    @Test
    @DisplayName("keeps the bare listing state when JSON-LD carries no addressCountry")
    void locationKeepsListingStateWhenCountryMissing() {
        BoardJob job = listingJobWith("42717", "Technik/Labor", "Bayern", "https://th-deg.de/apply/1");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(postingWithLocation(null, "München")), job);

        assertThat(mapped.location()).isEqualTo("Bayern");
    }

    @Test
    @DisplayName("composed location resolves to a target country via CityCountryResolver")
    void composedLocationIsResolvable() throws Exception {
        BoardJob job = listingJobWith("42717", "Technik/Labor", "Bayern", "https://th-deg.de/apply/1");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(postingWithLocation("DE", "München")), job);

        // CityCountryResolver.init() is @PostConstruct/package-private, so drive it explicitly here.
        CityCountryResolver resolver = new CityCountryResolver(mock(PersonalProfileLoader.class));
        java.lang.reflect.Method init = CityCountryResolver.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(resolver);

        assertThat(resolver.resolve("Bayern")).isEmpty(); // the defect: bare state is unresolvable
        assertThat(resolver.resolve(mapped.location())).contains("DE");
        assertThat(resolver.isTargetCountry("DE")).isTrue();
    }

    @Test
    @DisplayName("falls back to the listing fields when JSON-LD omits company/location/applyUrl")
    void detailFallsBackToListingFields() {
        String jsonLd = "{\"@type\":\"JobPosting\",\"title\":\"Softwareentwickler (m/w/d)\","
                + "\"description\":\"<p>Only a description.</p>\"}";
        BoardJob job = listingJob("42717", "Technik/Labor");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(jsonLd), job);

        assertThat(mapped.companyName()).isEqualTo("TU Berlin");
        assertThat(mapped.location()).isEqualTo("Berlin");
        assertThat(mapped.applyUrl()).isEqualTo("https://th-deg.de/apply/42717");
        assertThat(mapped.description()).isEqualTo("Only a description.");
    }

    @Test
    @DisplayName("falls back to the detail URL when neither listing link nor JSON-LD url is present")
    void applyUrlFallsBackToDetailUrl() {
        String jsonLd = "{\"@type\":\"JobPosting\",\"title\":\"Softwareentwickler (m/w/d)\"}";
        BoardJob job = new BoardJob("42717", "Softwareentwickler (m/w/d)", "TU Berlin", "Berlin",
                null, DETAIL_URL, new LinkedHashMap<>(Map.of("kategorie", "Technik/Labor")));

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(jsonLd), job);

        assertThat(mapped.applyUrl()).isEqualTo(DETAIL_URL);
    }

    @Test
    @DisplayName("tolerates absent description/datePosted and still emits the job")
    void toleratesMissingDetailFields() {
        String jsonLd = "{\"@type\":\"JobPosting\",\"title\":\"Softwareentwickler (m/w/d)\"}";
        BoardJob job = listingJob("42717", "Technik/Labor");

        RawAggregatorJob mapped = strategy.parseDetail(detailHtml(jsonLd), job);

        assertThat(mapped).isNotNull();
        assertThat(mapped.description()).isNull();
        assertThat(mapped.postedDate()).isNull();
        assertThat(mapped.title()).isEqualTo("Softwareentwickler (m/w/d)");
    }

    @Test
    @DisplayName("emits from listing fallback when the detail page has no JSON-LD")
    void emitsWithoutJsonLd() {
        BoardJob job = listingJob("42717", "Technik/Labor");

        RawAggregatorJob mapped = strategy.parseDetail("<html><body>no json-ld</body></html>", job);

        assertThat(mapped).isNotNull();
        assertThat(mapped.title()).isEqualTo("Softwareentwickler (m/w/d)");
        assertThat(mapped.description()).isNull();
        assertThat(mapped.rawJson()).contains("E13");
    }

    @Test
    @DisplayName("returns null only when JSON-LD is missing and the fallback title is blank")
    void returnsNullForUntitledPageWithoutJsonLd() {
        BoardJob job = new BoardJob("9", " ", "TU Berlin", "Berlin", null, DETAIL_URL,
                new LinkedHashMap<>());

        assertThat(strategy.parseDetail("<html><body>nothing</body></html>", job)).isNull();
        assertThat(strategy.parseDetail(null, job)).isNull();
    }

    @Test
    @DisplayName("finds the JobPosting when JSON-LD is an array or wrapped in @graph")
    void findsJobPostingInContainerShapes() {
        String arrayWrapped = "[{\"@type\":\"Organization\",\"name\":\"ZeN\"},"
                + "{\"@type\":\"JobPosting\",\"title\":\"Array title\",\"description\":\"From array\"}]";
        String graphWrapped = "{\"@context\":\"https://schema.org\",\"@graph\":["
                + "{\"@type\":\"BreadcrumbList\"},"
                + "{\"@type\":\"JobPosting\",\"title\":\"Graph title\",\"description\":\"From graph\"}]}";

        RawAggregatorJob fromArray = strategy.parseDetail(detailHtml(arrayWrapped),
                listingJob("1", "Technik/Labor"));
        RawAggregatorJob fromGraph = strategy.parseDetail(detailHtml(graphWrapped),
                listingJob("2", "Technik/Labor"));

        assertThat(fromArray.title()).isEqualTo("Array title");
        assertThat(fromArray.description()).isEqualTo("From array");
        assertThat(fromGraph.title()).isEqualTo("Graph title");
        assertThat(fromGraph.description()).isEqualTo("From graph");
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private static BoardJob listingJob(String id, String kategorie) {
        return listingJobWith(id, kategorie, "Berlin", "https://th-deg.de/apply/" + id);
    }

    private static BoardJob listingJobWith(String id, String kategorie, String location, String applyUrl) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kategorie", kategorie);
        attributes.put("befristung", "2 Jahre");
        attributes.put("arbeitszeit_pct", "100");
        attributes.put("entgeltgruppe", "E13");
        attributes.put("tags", "Java,Spring");
        attributes.put("quelle", "wissenschaftsstellen.de");
        attributes.put("inst_typ", "Universität");
        attributes.put("fachbereich_raw", "Informatik");
        return new BoardJob(id, "Softwareentwickler (m/w/d)", "TU Berlin", location, applyUrl,
                DETAIL_URL, attributes);
    }

    private static String listingHtml(String jobsJson, String... anchors) {
        StringBuilder sb = new StringBuilder("<html><body><script>var other=1;var JOBS=")
                .append(jobsJson)
                .append(";window.other=2;</script>");
        for (String anchor : anchors) {
            sb.append("<a href=\"").append(anchor).append("\">Details</a>");
        }
        return sb.append("</body></html>").toString();
    }

    private static String detailHtml(String jsonLd) {
        return "<html><head><script type=\"application/ld+json\">" + jsonLd
                + "</script></head><body></body></html>";
    }

    private static String jobJson(long id, String kategorie, String link) {
        return "{"
                + "\"id\":" + id + ","
                + "\"bundesland\":\"Berlin\","
                + "\"hochschule\":\"TU Berlin\","
                + "\"kuerzel\":\"tu-berlin\","
                + "\"inst_typ\":\"Universität\","
                + "\"kategorie\":\"" + kategorie + "\","
                + "\"titel\":\"Softwareentwickler (m/w/d)\","
                + "\"frist\":\"2026-10-01\","
                + "\"befristung\":\"2 Jahre\","
                + "\"arbeitszeit_pct\":\"100\","
                + "\"entgeltgruppe\":\"E13\","
                + "\"fachbereich_raw\":\"Informatik\","
                + "\"tenure_track\":false,"
                + "\"tags\":\"Java,Spring\","
                + "\"quelle\":\"wissenschaftsstellen.de\","
                + "\"medi_job\":false,"
                + "\"link\":\"" + link + "\""
                + "}";
    }

    private static String postingWithLocation(String country, String locality) {
        StringBuilder address = new StringBuilder("{\"@type\":\"PostalAddress\"");
        if (locality != null) {
            address.append(",\"addressLocality\":\"").append(locality).append('"');
        }
        if (country != null) {
            address.append(",\"addressCountry\":\"").append(country).append('"');
        }
        address.append('}');
        return "{\"@type\":\"JobPosting\",\"title\":\"Softwareentwickler (m/w/d)\","
                + "\"jobLocation\":{\"@type\":\"Place\",\"address\":" + address + "}}";
    }

    private static String fullPostingJsonLd() {        return "{"
                + "\"@context\":\"https://schema.org\","
                + "\"@type\":\"JobPosting\","
                + "\"title\":\"Softwareentwickler (m/w/d)\","
                + "\"description\":\"<p>Entwickle <b>Software</b>.</p>\","
                + "\"identifier\":{\"@type\":\"PropertyValue\",\"value\":42717},"
                + "\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"TU Berlin\"},"
                + "\"employmentType\":[\"FULL_TIME\"],"
                + "\"url\":\"https://th-deg.de/apply/1\","
                + "\"datePosted\":\"2026-09-11\","
                + "\"jobLocation\":{\"@type\":\"Place\",\"address\":{"
                + "\"@type\":\"PostalAddress\",\"addressCountry\":\"DE\","
                + "\"addressLocality\":\"Bonn\",\"streetAddress\":\"Str. 1\","
                + "\"postalCode\":\"10115\",\"addressRegion\":\"Berlin\"}},"
                + "\"baseSalary\":{\"@type\":\"MonetaryAmount\",\"currency\":\"EUR\","
                + "\"value\":{\"@type\":\"QuantitativeValue\",\"minValue\":2050,"
                + "\"maxValue\":2976,\"unitText\":\"MONTH\"}}"
                + "}";
    }
}
