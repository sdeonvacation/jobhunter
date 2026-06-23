package dev.jobhunter.strategy.aggregator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class SitemapXmlParser {

    private static final Logger log = LoggerFactory.getLogger(SitemapXmlParser.class);

    record SitemapEntry(String url, String lastmod) {}

    /** Stream-parse flat sitemap XML, return all &lt;loc&gt; entries with optional &lt;lastmod&gt;. */
    static List<SitemapEntry> parse(String xmlBody) {
        List<SitemapEntry> entries = new ArrayList<>();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            SAXParser parser = factory.newSAXParser();
            parser.parse(
                new ByteArrayInputStream(xmlBody.getBytes(StandardCharsets.UTF_8)),
                new UrlEntryHandler(entries)
            );
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.warn("Sitemap XML parse error: {}", e.getMessage());
            throw new RuntimeException("Failed to parse sitemap XML: " + e.getMessage(), e);
        }
        return entries;
    }

    /** Parse sitemap index XML, returning child sitemap URLs. */
    static List<String> parseSitemapIndex(String xmlBody) {
        List<SitemapEntry> entries = new ArrayList<>();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            SAXParser parser = factory.newSAXParser();
            parser.parse(
                new ByteArrayInputStream(xmlBody.getBytes(StandardCharsets.UTF_8)),
                new UrlEntryHandler(entries)
            );
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.warn("Sitemap index parse error: {}", e.getMessage());
            throw new RuntimeException("Failed to parse sitemap index XML: " + e.getMessage(), e);
        }
        return entries.stream().map(SitemapEntry::url).toList();
    }

    private static class UrlEntryHandler extends DefaultHandler {
        private final List<SitemapEntry> entries;
        private String currentLoc;
        private String currentLastmod;
        private boolean inLoc;
        private boolean inLastmod;
        private final StringBuilder buffer = new StringBuilder();

        UrlEntryHandler(List<SitemapEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = localName.isBlank() ? qName : localName;
            if ("loc".equalsIgnoreCase(name)) {
                inLoc = true;
                buffer.setLength(0);
            } else if ("lastmod".equalsIgnoreCase(name)) {
                inLastmod = true;
                buffer.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inLoc || inLastmod) {
                buffer.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = localName.isBlank() ? qName : localName;
            if ("loc".equalsIgnoreCase(name)) {
                currentLoc = buffer.toString().trim();
                inLoc = false;
            } else if ("lastmod".equalsIgnoreCase(name)) {
                currentLastmod = buffer.toString().trim();
                inLastmod = false;
            } else if ("url".equalsIgnoreCase(name) || "sitemap".equalsIgnoreCase(name)) {
                if (currentLoc != null && !currentLoc.isBlank()) {
                    entries.add(new SitemapEntry(currentLoc, currentLastmod));
                }
                currentLoc = null;
                currentLastmod = null;
            }
        }
    }
}
