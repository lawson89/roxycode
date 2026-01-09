package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for parsing and manipulating HTML using JSoup.
 */
@ScriptService("jsoupService")
@LLMDoc("Service for parsing and manipulating HTML using JSoup.")
public class JSoupService {

    private final Sandbox sandbox;

    @Inject
    public JSoupService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Parses an HTML file and returns a summary of its structure.
     *
     * @param pathStr The path to the HTML file.
     * @return A summary of the HTML structure.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Parses an HTML file and returns a summary of its structure.")
    public String parseHtml(String pathStr) throws IOException {
        Path path = sandbox.resolve(pathStr);
        Document doc = Jsoup.parse(path.toFile(), "UTF-8");

        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(doc.title()).append("\n");

        Elements metaTags = doc.getElementsByTag("meta");
        if (!metaTags.isEmpty()) {
            sb.append("Meta Tags:\n");
            for (Element meta : metaTags) {
                sb.append("  ").append(meta.outerHtml()).append("\n");
            }
        }

        Elements headers = doc.select("h1, h2, h3, h4, h5, h6");
        if (!headers.isEmpty()) {
            sb.append("Headers:\n");
            for (Element header : headers) {
                sb.append("  ").append(header.tagName()).append(": ").append(header.text()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Extracts all links from an HTML file.
     *
     * @param pathStr The path to the HTML file.
     * @return A list of absolute URLs found in the file.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Extracts all links from an HTML file.")
    public List<String> extractLinks(String pathStr) throws IOException {
        Path path = sandbox.resolve(pathStr);
        Document doc = Jsoup.parse(path.toFile(), "UTF-8");
        Elements links = doc.select("a[href]");

        List<String> urls = new ArrayList<>();
        for (Element link : links) {
            urls.add(link.attr("abs:href"));
        }
        return urls;
    }

    /**
     * Selects elements based on a CSS selector.
     *
     * @param pathStr  The path to the HTML file.
     * @param selector The CSS selector.
     * @return A list of matching elements' outer HTML.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Selects elements based on a CSS selector.")
    public List<String> select(String pathStr, String selector) throws IOException {
        Path path = sandbox.resolve(pathStr);
        Document doc = Jsoup.parse(path.toFile(), "UTF-8");
        Elements elements = doc.select(selector);

        List<String> result = new ArrayList<>();
        for (Element element : elements) {
            result.add(element.outerHtml());
        }
        return result;
    }
}
