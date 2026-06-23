package dev.jobhunter.strategy.aggregator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SitemapXmlParserTest {

    // ─── parse() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parse()")
    class ParseTests {

        @Test
        @DisplayName("parses flat sitemap with loc and lastmod")
        void parsesLocAndLastmod() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                      <url>
                        <loc>https://example.com/jobs/123</loc>
                        <lastmod>2024-06-01</lastmod>
                      </url>
                      <url>
                        <loc>https://example.com/jobs/456</loc>
                        <lastmod>2024-06-02</lastmod>
                      </url>
                    </urlset>
                    """;

            List<SitemapXmlParser.SitemapEntry> entries = SitemapXmlParser.parse(xml);

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).url()).isEqualTo("https://example.com/jobs/123");
            assertThat(entries.get(0).lastmod()).isEqualTo("2024-06-01");
            assertThat(entries.get(1).url()).isEqualTo("https://example.com/jobs/456");
            assertThat(entries.get(1).lastmod()).isEqualTo("2024-06-02");
        }

        @Test
        @DisplayName("parses loc without lastmod — lastmod is null")
        void parsesLocWithoutLastmod() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                      <url>
                        <loc>https://example.com/jobs/789</loc>
                      </url>
                    </urlset>
                    """;

            List<SitemapXmlParser.SitemapEntry> entries = SitemapXmlParser.parse(xml);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).url()).isEqualTo("https://example.com/jobs/789");
            assertThat(entries.get(0).lastmod()).isNull();
        }

        @Test
        @DisplayName("returns empty list for empty urlset")
        void emptyUrlset() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                    </urlset>
                    """;

            assertThat(SitemapXmlParser.parse(xml)).isEmpty();
        }

        @Test
        @DisplayName("skips url entries with blank loc")
        void skipsBlankLoc() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <urlset>
                      <url>
                        <loc>   </loc>
                      </url>
                      <url>
                        <loc>https://example.com/jobs/1</loc>
                      </url>
                    </urlset>
                    """;

            List<SitemapXmlParser.SitemapEntry> entries = SitemapXmlParser.parse(xml);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).url()).isEqualTo("https://example.com/jobs/1");
        }

        @Test
        @DisplayName("handles loc with surrounding whitespace")
        void trimsLocWhitespace() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <urlset>
                      <url>
                        <loc>
                          https://example.com/jobs/trim
                        </loc>
                      </url>
                    </urlset>
                    """;

            List<SitemapXmlParser.SitemapEntry> entries = SitemapXmlParser.parse(xml);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).url()).isEqualTo("https://example.com/jobs/trim");
        }

        @Test
        @DisplayName("parses many entries")
        void parsesManyEntries() {
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><urlset>");
            for (int i = 1; i <= 200; i++) {
                sb.append("<url><loc>https://example.com/jobs/").append(i).append("</loc></url>");
            }
            sb.append("</urlset>");

            List<SitemapXmlParser.SitemapEntry> entries = SitemapXmlParser.parse(sb.toString());

            assertThat(entries).hasSize(200);
            assertThat(entries.get(0).url()).isEqualTo("https://example.com/jobs/1");
            assertThat(entries.get(199).url()).isEqualTo("https://example.com/jobs/200");
        }

        @Test
        @DisplayName("throws RuntimeException on malformed XML")
        void throwsOnMalformedXml() {
            assertThatThrownBy(() -> SitemapXmlParser.parse("<urlset><url><loc>unclosed"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to parse sitemap XML");
        }

        @Test
        @DisplayName("throws on DOCTYPE declaration (XXE prevention)")
        void throwsOnDoctype() {
            String xml = """
                    <?xml version="1.0"?>
                    <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <urlset><url><loc>&xxe;</loc></url></urlset>
                    """;

            assertThatThrownBy(() -> SitemapXmlParser.parse(xml))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // ─── parseSitemapIndex() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("parseSitemapIndex()")
    class ParseSitemapIndexTests {

        @Test
        @DisplayName("returns child sitemap URLs from sitemapindex")
        void returnsChildUrls() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                      <sitemap>
                        <loc>https://example.com/sitemap-jobs-1.xml</loc>
                        <lastmod>2024-06-01</lastmod>
                      </sitemap>
                      <sitemap>
                        <loc>https://example.com/sitemap-jobs-2.xml</loc>
                      </sitemap>
                    </sitemapindex>
                    """;

            List<String> urls = SitemapXmlParser.parseSitemapIndex(xml);

            assertThat(urls).containsExactly(
                    "https://example.com/sitemap-jobs-1.xml",
                    "https://example.com/sitemap-jobs-2.xml"
            );
        }

        @Test
        @DisplayName("returns empty list for empty sitemapindex")
        void emptyIndex() {
            String xml = """
                    <?xml version="1.0"?>
                    <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                    </sitemapindex>
                    """;

            assertThat(SitemapXmlParser.parseSitemapIndex(xml)).isEmpty();
        }

        @Test
        @DisplayName("throws RuntimeException on malformed index XML")
        void throwsOnMalformed() {
            assertThatThrownBy(() -> SitemapXmlParser.parseSitemapIndex("not xml at all <<>>"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to parse sitemap index XML");
        }
    }

    // ─── SitemapEntry record ──────────────────────────────────────────────────

    @Test
    @DisplayName("SitemapEntry record equality and accessors")
    void sitemapEntryRecord() {
        SitemapXmlParser.SitemapEntry e1 = new SitemapXmlParser.SitemapEntry("https://x.com/1", "2024-01-01");
        SitemapXmlParser.SitemapEntry e2 = new SitemapXmlParser.SitemapEntry("https://x.com/1", "2024-01-01");

        assertThat(e1.url()).isEqualTo("https://x.com/1");
        assertThat(e1.lastmod()).isEqualTo("2024-01-01");
        assertThat(e1).isEqualTo(e2);
    }
}
