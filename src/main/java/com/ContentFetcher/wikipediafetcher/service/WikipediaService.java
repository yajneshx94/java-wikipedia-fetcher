package com.ContentFetcher.wikipediafetcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ContentFetcher.wikipediafetcher.dto.SearchResult;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WikipediaService {

    private static final String WIKIPEDIA_API_URL = "https://en.wikipedia.org/w/api.php";
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WikipediaService() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("User-Agent", "WikipediaFetcher/1.0 (https://github.com/yajneshx94/wikipedia-fetcher; yajneshrajan83@gmail.com)");
            return execution.execute(request, body);
        });
    }

    public SearchResult search(String query) {
        try {
            // Manually build URL to avoid encoding issues with pipe character
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String apiUrl = WIKIPEDIA_API_URL +
                    "?action=query" +
                    "&format=json" +
                    "&prop=extracts|pageimages|info" +
                    "&inprop=url" +
                    "&exintro=1" +
                    "&explaintext=1" +
                    "&generator=search" +
                    "&gsrsearch=" + encodedQuery +
                    "&gsrnamespace=0" +
                    "&gsrlimit=5" +
                    "&redirects=1";

            String response = restTemplate.getForObject(apiUrl, String.class);

            if (response == null || response.isEmpty()) {
                return SearchResult.error("No response from Wikipedia API.");
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode pages = root.path("query").path("pages");

            if (pages.isMissingNode() || pages.isEmpty() || !pages.isObject()) {
                return SearchResult.error("No results found for '" + query + "'.");
            }

            // Sort pages by their search result index
            List<JsonNode> sortedPages = new ArrayList<>();
            pages.elements().forEachRemaining(sortedPages::add);
            sortedPages.sort(Comparator.comparingInt(p -> p.path("index").asInt(Integer.MAX_VALUE)));

            SearchResult.Article mainArticle = null;
            List<String> allTitles = new ArrayList<>();

            for (JsonNode articleNode : sortedPages) {
                String pageTitle = articleNode.path("title").asText("");
                String summary = articleNode.path("extract").asText("");
                String fullUrl = articleNode.path("fullurl").asText("");

                // Skip if title is empty
                if (pageTitle.isEmpty()) {
                    continue;
                }

                allTitles.add(pageTitle);

                // Select the first valid article (has content and is not a disambiguation page)
                if (mainArticle == null && !summary.isEmpty() &&
                        !summary.toLowerCase().endsWith("may refer to:") &&
                        !summary.toLowerCase().contains("disambiguation")) {

                    // Ensure we have a valid URL
                    if (!fullUrl.isEmpty()) {
                        mainArticle = new SearchResult.Article(pageTitle, summary, fullUrl);
                    }
                }
            }

            // If no valid main article found, return error
            if (mainArticle == null) {
                if (!allTitles.isEmpty()) {
                    return SearchResult.error("Could not find a detailed article for '" + query + "'. Try being more specific.");
                } else {
                    return SearchResult.error("No results found for '" + query + "'.");
                }
            }

            // Create suggestions list (excluding the main article)
            final String mainArticleTitle = mainArticle.title();
            List<String> suggestions = allTitles.stream()
                    .filter(title -> !title.equals(mainArticleTitle))
                    .limit(4)
                    .collect(Collectors.toList());

            return SearchResult.success(mainArticle, suggestions);

        } catch (RestClientException e) {
            // This error happens if there's a network issue (e.g., no internet)
            return SearchResult.error("Unable to connect to Wikipedia. Please check your internet connection.");
        } catch (Exception e) {
            // This is a catch-all for any other unexpected problems
            return SearchResult.error("An unexpected error occurred while searching. Please try again.");
        }
    }
}
