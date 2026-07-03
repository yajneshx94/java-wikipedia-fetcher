package com.ContentFetcher.wikipediafetcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ContentFetcher.wikipediafetcher.dto.SearchResult;
import com.ContentFetcher.wikipediafetcher.model.ArticleCache;
import com.ContentFetcher.wikipediafetcher.model.SearchHistory;
import com.ContentFetcher.wikipediafetcher.repository.ArticleCacheRepository;
import com.ContentFetcher.wikipediafetcher.repository.SearchHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tech-only knowledge explorer ("Techpedia" mode).
 *
 * Every search is biased toward technology/computing topics using:
 *   1. A query rewrite that nudges Wikipedia's search ranking toward tech articles.
 *   2. A scored candidate-picking pass instead of "first non-disambiguation hit".
 *   3. A tech-relevance filter applied to Key Concepts / Topics to Explore,
 *      so non-tech links (islands, gemstones, mythology, etc.) never surface.
 */
@Service
public class WikipediaService {

    private static final String WIKIPEDIA_API_URL = "https://en.wikipedia.org/w/api.php";

    // Words that signal "this page is about technology/computing".
    // Used both to score candidate articles and to filter related-topic links.
    private static final List<String> TECH_SIGNAL_WORDS = List.of(
            "programming", "software", "computing", "computer", "framework",
            "library", "algorithm", "database", "api", "compiler", "interpreter",
            "developer", "development", "code", "coding", "language", "engine",
            "operating system", "open-source", "open source", "github", "server",
            "backend", "back-end", "frontend", "front-end", "devops", "cloud",
            "network", "protocol", "encryption", "data structure", "machine learning",
            "artificial intelligence", "app", "application", "platform", "runtime",
            "kernel", "virtual machine", "container", "microservice", "web",
            "internet", "byte", "script", "markup", "it company", "technology company",
            "tech company", "jvm", "ide", "linux", "unix", "git", "version control",
            "query language", "object-oriented", "object oriented", "syntax",
            "bytecode", "package manager", "build tool", "command line", "cli",
            "repository", "deploy", "deployment", "automation", "scripting",
            "hardware", "processor", "cpu", "gpu", "embedded system", "firmware",
            "web browser", "web server", "html", "css", "json", "xml", "sdk",
            "open standard", "tech startup", "software company"
    );

    // Words that signal a page is almost certainly NOT about technology,
    // even if it superficially shares a name with a tech topic.
    private static final List<String> NON_TECH_SIGNAL_WORDS = List.of(
            "island", "gemstone", "mineral", "mythology", "deity", "goddess", "god of",
            "river", "mountain", "province", "village", "municipality", "district of",
            "footballer", "cricketer", "actress", "actor", "singer", "album", "song by",
            "novel by", "film by", "tv series", "television series", "city in", "town in",
            "politician", "painter", "sculptor", "dynasty", "empire", "battle of",
            "war of", "species", "genus", "animal", "plant", "chemical compound",
            "disease", "syndrome", "monarch", "king of", "queen of", "saint",
            "biblical", "religious", "temple", "shrine", "constellation", "asteroid",
            "comet", "moon of", "national park", "wildlife", "bird", "fish species",
            "musician", "rapper", "athlete", "olympic", "tournament", "championship"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArticleCacheRepository cacheRepo;
    private final SearchHistoryRepository historyRepo;
    private final FuzzySearchService fuzzyService;

    public WikipediaService(ArticleCacheRepository cacheRepo,
                            SearchHistoryRepository historyRepo,
                            FuzzySearchService fuzzyService) {
        this.cacheRepo = cacheRepo;
        this.historyRepo = historyRepo;
        this.fuzzyService = fuzzyService;
        this.restTemplate = new RestTemplate();
        this.restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("User-Agent",
                    "Techpedia/1.0 (https://github.com/yajneshx94/wikipedia-fetcher; yajneshrajan83@gmail.com)");
            return execution.execute(request, body);
        });
    }

    public SearchResult search(String rawQuery) {
        String query = rawQuery.replaceAll("[()]", " ").replaceAll("\\s+", " ").trim();
        String queryKey = query.toLowerCase();
        System.out.println("[SEARCH] rawQuery='" + rawQuery + "' cleanedQuery='" + query + "'");

        // --- 1. Cache check ---
        Optional<ArticleCache> cached = cacheRepo.findByQueryKey(queryKey);
        if (cached.isPresent()) {
            System.out.println("[SEARCH] cache HIT for '" + queryKey + "'");
            return buildResultFromCache(cached.get());
        }

        // --- 2. Resolve query, biased toward tech, via opensearch ---
        String resolvedQuery = resolveQueryViaOpenSearch(query);
        System.out.println("[SEARCH] resolvedQuery='" + resolvedQuery + "'");

        if (!resolvedQuery.equalsIgnoreCase(query)) {
            Optional<ArticleCache> resolvedCached = cacheRepo.findByQueryKey(resolvedQuery.toLowerCase());
            if (resolvedCached.isPresent()) {
                System.out.println("[SEARCH] cache HIT for resolved query '" + resolvedQuery + "'");
                return buildResultFromCache(resolvedCached.get());
            }
        }

        // --- 3. Fuzzy match against cached titles ---
        List<String> allCachedTitles = cacheRepo.findAll()
                .stream().map(ArticleCache::getTitle).collect(Collectors.toList());
        String fuzzyMatch = fuzzyService.findClosestMatch(query, allCachedTitles);
        if (fuzzyMatch != null) {
            Optional<ArticleCache> fuzzyResult = cacheRepo.findByQueryKey(fuzzyMatch.toLowerCase());
            if (fuzzyResult.isPresent()) {
                System.out.println("[SEARCH] fuzzy cache HIT: '" + fuzzyMatch + "'");
                return buildResultFromCache(fuzzyResult.get());
            }
        }

        // --- 4. Search Wikipedia ---
        try {
            // CRITICAL FIX: resolvedQuery often comes back from opensearch as an exact
            // Wikipedia title like "Java (programming language)" — gsrsearch (full-text
            // search) treats literal parentheses as syntax and returns ZERO results for
            // such strings. Strip them here, the same way we clean raw user input.
            String searchableQuery = resolvedQuery.replaceAll("[()]", " ").replaceAll("\\s+", " ").trim();
            String encodedQuery = URLEncoder.encode(searchableQuery, StandardCharsets.UTF_8);

            String searchUrl = WIKIPEDIA_API_URL +
                    "?action=query" +
                    "&format=json" +
                    "&prop=extracts|info|pageprops" +
                    "&ppprop=disambiguation" +
                    "&inprop=url" +
                    "&exintro=1" +
                    "&explaintext=1" +
                    "&generator=search" +
                    "&gsrsearch=" + encodedQuery +
                    "&gsrnamespace=0" +
                    "&gsrlimit=10" +
                    "&redirects=1";

            System.out.println("[SEARCH] calling Wikipedia: " + searchUrl);
            String searchResponse = restTemplate.getForObject(searchUrl, String.class);
            if (searchResponse == null || searchResponse.isEmpty()) {
                System.out.println("[SEARCH] empty response from Wikipedia");
                return SearchResult.error("No response from Wikipedia API.");
            }

            JsonNode root = objectMapper.readTree(searchResponse);
            JsonNode pages = root.path("query").path("pages");

            if (pages.isMissingNode() || pages.isEmpty() || !pages.isObject()) {
                System.out.println("[SEARCH] no pages found, retrying with plain query");
                return retryWithPlainQuery(resolvedQuery, rawQuery);
            }

            List<JsonNode> sortedPages = new ArrayList<>();
            pages.elements().forEachRemaining(sortedPages::add);
            sortedPages.sort(Comparator.comparingInt(p -> p.path("index").asInt(Integer.MAX_VALUE)));

            System.out.println("[SEARCH] got " + sortedPages.size() + " candidate pages:");
            for (JsonNode n : sortedPages) {
                System.out.println("    - title='" + n.path("title").asText("") +
                        "' isDisambig=" + n.path("pageprops").has("disambiguation") +
                        " extractLen=" + n.path("extract").asText("").length());
            }

            CandidatePick pick = pickBestTechCandidate(sortedPages, resolvedQuery);
            System.out.println("[SEARCH] pickBestTechCandidate result: " + (pick == null ? "NULL" : pick.title));

            if (pick == null) {
                if (!sortedPages.isEmpty()) {
                    JsonNode firstNode = sortedPages.get(0);
                    String disambigTitle = firstNode.path("title").asText("");
                    String disambigPageId = firstNode.path("pageid").asText("");
                    System.out.println("[SEARCH] attempting disambiguation drill on '" + disambigTitle + "' (pageid=" + disambigPageId + ")");
                    if (!disambigPageId.isEmpty()) {
                        String drilled = drillIntoDisambiguation(disambigPageId);
                        System.out.println("[SEARCH] drillIntoDisambiguation returned: " + drilled);
                        if (drilled != null && !drilled.equalsIgnoreCase(disambigTitle)) {
                            return search(drilled);
                        }
                    }
                }
                System.out.println("[SEARCH] falling through to retryWithPlainQuery");
                return retryWithPlainQuery(resolvedQuery, rawQuery);
            }

            String pageId = pick.node.path("pageid").asText("");
            List<String> rawTopics = fetchTopicsToExplore(pageId, pick.title);
            List<String> topicsToExplore = filterToTechTopics(rawTopics);
            List<String> keyConcepts = extractKeyConcepts(pick.summary, topicsToExplore);

            final String finalMainTitle = pick.title;
            List<String> suggestions = sortedPages.stream()
                    .map(n -> n.path("title").asText(""))
                    .filter(t -> !t.isEmpty() && !t.equals(finalMainTitle))
                    .distinct()
                    .limit(4)
                    .collect(Collectors.toList());

            saveToCache(queryKey, resolvedQuery, query, pick.title, pick.summary, pick.url,
                    keyConcepts, topicsToExplore, suggestions);
            historyRepo.save(new SearchHistory(rawQuery, pick.title));

            SearchResult.Article article = new SearchResult.Article(
                    pick.title,
                    buildQuickSummary(pick.summary),
                    pick.summary,
                    pick.url,
                    keyConcepts,
                    topicsToExplore
            );
            return SearchResult.success(article, suggestions);

        } catch (RestClientException e) {
            System.out.println("[SEARCH] RestClientException: " + e.getMessage());
            return SearchResult.error("Unable to connect to Wikipedia. Please check your internet connection.");
        } catch (Exception e) {
            System.out.println("[SEARCH] Exception: " + e);
            e.printStackTrace();
            return SearchResult.error("An unexpected error occurred while searching. Please try again.");
        }
    }

    /**
     * Fallback: search Wikipedia with the plain (non-tech-biased) query, but still
     * apply tech scoring to whatever comes back. Used when the tech-biased query
     * returns nothing — better to show a possibly-imperfect tech match than nothing.
     */
    private SearchResult retryWithPlainQuery(String resolvedQuery, String rawQuery) {
        try {
            String searchableQuery = resolvedQuery.replaceAll("[()]", " ").replaceAll("\\s+", " ").trim();
            String encodedQuery = URLEncoder.encode(searchableQuery, StandardCharsets.UTF_8);
            String searchUrl = WIKIPEDIA_API_URL +
                    "?action=query" +
                    "&format=json" +
                    "&prop=extracts|info|pageprops" +
                    "&ppprop=disambiguation" +
                    "&inprop=url" +
                    "&exintro=1" +
                    "&explaintext=1" +
                    "&generator=search" +
                    "&gsrsearch=" + encodedQuery +
                    "&gsrnamespace=0" +
                    "&gsrlimit=10" +
                    "&redirects=1";

            String searchResponse = restTemplate.getForObject(searchUrl, String.class);
            if (searchResponse == null || searchResponse.isEmpty()) {
                return SearchResult.error("No tech results found for '" + rawQuery + "'.");
            }

            JsonNode root = objectMapper.readTree(searchResponse);
            JsonNode pages = root.path("query").path("pages");
            if (pages.isMissingNode() || pages.isEmpty() || !pages.isObject()) {
                return SearchResult.error("No tech results found for '" + rawQuery + "'. Try a different technology, tool, or concept.");
            }

            List<JsonNode> sortedPages = new ArrayList<>();
            pages.elements().forEachRemaining(sortedPages::add);
            sortedPages.sort(Comparator.comparingInt(p -> p.path("index").asInt(Integer.MAX_VALUE)));

            CandidatePick pick = pickBestTechCandidate(sortedPages, resolvedQuery);
            if (pick == null) {
                return SearchResult.error("'" + rawQuery + "' doesn't appear to be a technology topic. Try searching for a programming language, framework, tool, or tech concept.");
            }

            String pageId = pick.node.path("pageid").asText("");
            List<String> rawTopics = fetchTopicsToExplore(pageId, pick.title);
            List<String> topicsToExplore = filterToTechTopics(rawTopics);
            List<String> keyConcepts = extractKeyConcepts(pick.summary, topicsToExplore);

            final String finalMainTitle = pick.title;
            List<String> suggestions = sortedPages.stream()
                    .map(n -> n.path("title").asText(""))
                    .filter(t -> !t.isEmpty() && !t.equals(finalMainTitle))
                    .distinct()
                    .limit(4)
                    .collect(Collectors.toList());

            String queryKey = rawQuery.toLowerCase().trim();
            saveToCache(queryKey, resolvedQuery, resolvedQuery, pick.title, pick.summary, pick.url,
                    keyConcepts, topicsToExplore, suggestions);
            historyRepo.save(new SearchHistory(rawQuery, pick.title));

            SearchResult.Article article = new SearchResult.Article(
                    pick.title,
                    buildQuickSummary(pick.summary),
                    pick.summary,
                    pick.url,
                    keyConcepts,
                    topicsToExplore
            );
            return SearchResult.success(article, suggestions);

        } catch (Exception e) {
            return SearchResult.error("An unexpected error occurred while searching. Please try again.");
        }
    }

    private void saveToCache(String primaryKey, String resolvedQuery, String originalQuery,
                             String title, String summary, String url,
                             List<String> keyConcepts, List<String> topics, List<String> suggestions) {
        try {
            ArticleCache toCache = new ArticleCache(primaryKey, title, summary, url, keyConcepts, topics, suggestions);
            cacheRepo.save(toCache);
        } catch (Exception ignored) {}

        if (!resolvedQuery.equalsIgnoreCase(originalQuery)) {
            try {
                ArticleCache resolvedCache = new ArticleCache(
                        resolvedQuery.toLowerCase(), title, summary, url, keyConcepts, topics, suggestions);
                cacheRepo.save(resolvedCache);
            } catch (Exception ignored) {}
        }
    }

    /** Holds a candidate article along with its extracted fields, for scoring. */
    private static class CandidatePick {
        JsonNode node;
        String title;
        String summary;
        String url;
        int score;
    }

    /**
     * Scans all candidate pages and picks the one most likely to be a genuine
     * technology/computing article AND the best match for what the user searched:
     *   - Disqualifies disambiguation pages and pages with no extract/url.
     *   - Disqualifies pages whose extract contains strong non-tech signals
     *     UNLESS they also contain tech signals.
     *   - Title relevance dominates the score: a candidate whose title starts with
     *     or closely matches the search term scores far higher than one that merely
     *     mentions the term often in its body text. This prevents cases like
     *     searching "python" and getting "Mojo (programming language)" just because
     *     Mojo's article happens to mention "Python" and "programming language"
     *     repeatedly — Mojo's title has nothing to do with the query, so it should
     *     never outrank the actual Python article.
     *   - Tech-word density in the extract is only a secondary signal, used to
     *     reject genuinely non-tech pages and to break ties between similarly
     *     titled candidates.
     */
    private CandidatePick pickBestTechCandidate(List<JsonNode> sortedPages, String searchedQuery) {
        CandidatePick best = null;
        String normalizedQuery = searchedQuery.toLowerCase().trim();
        // First "core" word of the query, e.g. "python" out of "Python programming" —
        // used to check whether a candidate's title is actually about the same subject.
        String coreQueryWord = normalizedQuery.split("\\s+")[0];

        for (JsonNode node : sortedPages) {
            String title = node.path("title").asText("");
            String extract = node.path("extract").asText("").trim();
            String url = node.path("fullurl").asText("");
            boolean isDisambig = node.path("pageprops").has("disambiguation");

            if (title.isEmpty() || extract.isEmpty() || url.isEmpty() || isDisambig) continue;

            String lowerExtract = extract.toLowerCase();
            boolean looksLikeDisambigText = lowerExtract.contains("may refer to:")
                    || lowerExtract.contains("may also refer to:")
                    || lowerExtract.contains("most commonly refers to:");
            if (looksLikeDisambigText) continue;

            int techScore = countSignalMatches(lowerExtract, TECH_SIGNAL_WORDS);
            int nonTechScore = countSignalMatches(lowerExtract, NON_TECH_SIGNAL_WORDS);

            // Reject genuinely non-tech pages outright, same as before.
            if (nonTechScore > 0 && techScore == 0) continue;
            if (techScore == 0) continue;

            // --- Title relevance score (this is now the dominant factor) ---
            String lowerTitle = title.toLowerCase();
            int titleScore;
            if (lowerTitle.equals(coreQueryWord)) {
                titleScore = 100; // exact title match, e.g. query "java" → title "java"
            } else if (lowerTitle.startsWith(coreQueryWord + " ") || lowerTitle.startsWith(coreQueryWord + "(")) {
                titleScore = 90; // "Java (programming language)", "Python (programming language)"
            } else if (lowerTitle.contains(coreQueryWord)) {
                titleScore = 40; // title contains the word but isn't the primary subject
            } else {
                titleScore = 0; // title has nothing to do with the searched word at all
            }

            System.out.println("    [SCORE] '" + title + "' titleScore=" + titleScore +
                    " techScore=" + techScore + " nonTechScore=" + nonTechScore);

            // A candidate whose title doesn't even contain the searched term should
            // never win just because its body text is tech-heavy (the Mojo/Python bug).
            if (titleScore == 0) continue;

            int finalScore = (titleScore * 10) + techScore - nonTechScore;

            if (best == null || finalScore > best.score) {
                CandidatePick pick = new CandidatePick();
                pick.node = node;
                pick.title = title;
                pick.summary = extract;
                pick.url = url;
                pick.score = finalScore;
                best = pick;
            }
        }
        return best;
    }

    private int countSignalMatches(String lowerText, List<String> signals) {
        int count = 0;
        for (String s : signals) {
            if (lowerText.contains(s)) count++;
        }
        return count;
    }

    /**
     * Filters a list of related-topic titles down to ones that look tech-relevant,
     * using the title text itself as the signal (titles rarely contain full sentences,
     * so we match against common tech naming patterns and the signal word list).
     */
    private List<String> filterToTechTopics(List<String> topics) {
        if (topics.isEmpty()) return topics;
        List<String> filtered = topics.stream()
                .filter(this::looksTechRelated)
                .limit(8)
                .collect(Collectors.toList());
        // If filtering removed everything, fall back to the original (unfiltered) list
        // capped at 8 — better to show something than an empty section.
        return filtered.isEmpty() ? topics.stream().limit(8).collect(Collectors.toList()) : filtered;
    }

    private boolean looksTechRelated(String title) {
        String lower = title.toLowerCase();
        for (String bad : NON_TECH_SIGNAL_WORDS) {
            if (lower.contains(bad)) return false;
        }
        // Categories ending in common tech category patterns count automatically
        if (lower.contains("programming") || lower.contains("software") || lower.contains("computing")
                || lower.contains("computer") || lower.contains("technology") || lower.contains("internet")
                || lower.contains("(company)") || lower.contains("framework") || lower.contains("language")) {
            return true;
        }
        for (String good : TECH_SIGNAL_WORDS) {
            if (lower.contains(good)) return true;
        }
        // Short, capitalized single/double-word titles (typical of tech proper nouns like
        // "Docker", "Kubernetes", "Maven") are allowed through since the signal word list
        // can't cover every product/tool name. Long descriptive titles without any tech
        // signal are more likely to be unrelated categories.
        return title.split(" ").length <= 2;
    }

    /**
     * Uses Wikipedia's opensearch API to resolve/autocorrect a query, biased toward
     * technology phrasing so ambiguous single-word terms resolve to their tech sense.
     */
    private String resolveQueryViaOpenSearch(String query) {
        try {
            // Try a tech-flavored version first
            String techQuery = query + " programming";
            String resolved = openSearchLookup(techQuery);
            if (resolved != null) return resolved;

            // Fall back to the plain query if the tech-flavored search found nothing
            String plainResolved = openSearchLookup(query);
            return plainResolved != null ? plainResolved : query;
        } catch (Exception ignored) {
            return query;
        }
    }

    private String openSearchLookup(String searchTerm) {
        try {
            String encoded = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
            String url = WIKIPEDIA_API_URL +
                    "?action=opensearch" +
                    "&format=json" +
                    "&search=" + encoded +
                    "&limit=3" +
                    "&redirects=resolve";

            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return null;

            JsonNode root = objectMapper.readTree(response);
            if (root.isArray() && root.size() > 1) {
                JsonNode suggestions = root.get(1);
                if (suggestions.isArray() && suggestions.size() > 0) {
                    String suggestion = suggestions.get(0).asText("");
                    if (!suggestion.isEmpty()) return suggestion;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Given a disambiguation page's pageid, fetches its internal links and
     * returns the one most likely to be the tech-related sub-article.
     * Disambiguation pages (e.g. "Java", "Python") can have 50-100+ links,
     * so we paginate through all of them rather than fetching just the first batch.
     */
    private String drillIntoDisambiguation(String pageId) {
        try {
            String plcontinue = null;
            int pagesFetched = 0;
            int totalLinksScanned = 0;

            while (pagesFetched < 5) { // safety cap: at most 5 pages of links (~500 links)
                String url = WIKIPEDIA_API_URL +
                        "?action=query" +
                        "&format=json" +
                        "&prop=links" +
                        "&pageids=" + pageId +
                        "&plnamespace=0" +
                        "&pllimit=max" +
                        (plcontinue != null ? "&plcontinue=" + URLEncoder.encode(plcontinue, StandardCharsets.UTF_8) : "");

                String response = restTemplate.getForObject(url, String.class);
                if (response == null) {
                    System.out.println("    [DRILL] null response from Wikipedia");
                    return null;
                }

                JsonNode root = objectMapper.readTree(response);
                JsonNode linksNode = root.path("query").path("pages").path(pageId).path("links");

                if (linksNode.isArray()) {
                    totalLinksScanned += linksNode.size();
                    for (JsonNode link : linksNode) {
                        String title = link.path("title").asText("");
                        if (title.isEmpty()) continue;
                        String lower = title.toLowerCase();
                        if (lower.contains("(programming language)") || lower.contains("(software)")
                                || lower.contains("(company)") || lower.contains("(operating system)")
                                || lower.contains("(framework)")) {
                            System.out.println("    [DRILL] found tech link: '" + title + "' after scanning " + totalLinksScanned + " links");
                            return title;
                        }
                    }
                }

                JsonNode continueNode = root.path("continue").path("plcontinue");
                if (continueNode.isMissingNode()) break; // no more pages
                plcontinue = continueNode.asText();
                pagesFetched++;
            }
            System.out.println("    [DRILL] no tech link found after scanning " + totalLinksScanned + " total links across " + (pagesFetched + 1) + " page(s)");
            return null; // No tech-flavored link found in any page of links
        } catch (Exception e) {
            System.out.println("    [DRILL] exception: " + e);
            return null;
        }
    }

    private SearchResult buildResultFromCache(ArticleCache c) {
        SearchResult.Article article = new SearchResult.Article(
                c.getTitle(),
                buildQuickSummary(c.getMainSummary()),
                c.getMainSummary(),
                c.getLink(),
                c.getKeyConcepts(),
                c.getTopicsToExplore()
        );
        return SearchResult.success(article, c.getSuggestions());
    }

    /**
     * Fetches the article's internal links/categories AND its full plain-text body
     * (not just the intro), then ranks candidate topics by how often they're actually
     * mentioned in the full article — this is the "frequency analysis" step from the
     * original roadmap. Without this, Wikipedia's links/categories come back in
     * alphabetical order with no relation to conceptual importance (e.g. "ACM Queue"
     * and "Adoptium" ranking above "JVM" or "Garbage Collection" purely because A
     * comes before J/G alphabetically).
     */
    private List<String> fetchTopicsToExplore(String pageId, String mainTitle) {
        try {
            String linksUrl = WIKIPEDIA_API_URL +
                    "?action=query" +
                    "&format=json" +
                    "&prop=links|categories" +
                    "&pageids=" + pageId +
                    "&pllimit=100" +
                    "&cllimit=20" +
                    "&plnamespace=0";

            String response = restTemplate.getForObject(linksUrl, String.class);
            if (response == null) return List.of();

            JsonNode root = objectMapper.readTree(response);
            JsonNode pageNode = root.path("query").path("pages").path(pageId);

            List<String> candidates = new ArrayList<>();

            JsonNode links = pageNode.path("links");
            if (links.isArray()) {
                links.forEach(l -> {
                    String title = l.path("title").asText("");
                    if (!title.isEmpty() && !title.equals(mainTitle)) {
                        candidates.add(title);
                    }
                });
            }

            JsonNode categories = pageNode.path("categories");
            if (categories.isArray()) {
                categories.forEach(c -> {
                    String cat = c.path("title").asText("").replace("Category:", "").trim();
                    if (!cat.isEmpty()) candidates.add(cat);
                });
            }

            List<String> deduped = candidates.stream()
                    .distinct()
                    .filter(t -> !t.startsWith("Wikipedia") && !t.startsWith("Help:"))
                    .collect(Collectors.toList());

            // Fetch the FULL article text (not just the intro) so we can rank by
            // genuine mention frequency rather than alphabetical link order.
            String fullText = fetchFullArticleText(pageId);
            if (fullText == null || fullText.isBlank()) {
                // Fallback: no full text available, just cap the alphabetical list.
                return deduped.stream().limit(30).collect(Collectors.toList());
            }

            String lowerFullText = fullText.toLowerCase();
            return deduped.stream()
                    .map(title -> new Object[]{title, countOccurrences(lowerFullText, title.toLowerCase())})
                    .filter(arr -> (Integer) arr[1] > 0) // only topics actually mentioned in the body
                    .sorted((a, b) -> (Integer) b[1] - (Integer) a[1]) // most-mentioned first
                    .map(arr -> (String) arr[0])
                    .limit(30)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            return List.of();
        }
    }

    /** Fetches the full plain-text content of an article (used for frequency ranking). */
    private String fetchFullArticleText(String pageId) {
        try {
            String url = WIKIPEDIA_API_URL +
                    "?action=query" +
                    "&format=json" +
                    "&prop=extracts" +
                    "&pageids=" + pageId +
                    "&explaintext=1";
            // NOTE: no exintro=1 here — we want the full article body, not just the lead.

            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return null;
            JsonNode root = objectMapper.readTree(response);
            return root.path("query").path("pages").path(pageId).path("extract").asText("");
        } catch (Exception e) {
            return null;
        }
    }

    /** Counts non-overlapping occurrences of `needle` in `haystack` (case-sensitive on input). */
    private int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private List<String> extractKeyConcepts(String summaryText, List<String> topics) {
        // `topics` is now already ranked by full-article mention frequency
        // (see fetchTopicsToExplore), so the most frequently-discussed —
        // and therefore most conceptually central — topics are already first.
        if (topics.isEmpty()) return List.of();
        return topics.stream().limit(6).collect(Collectors.toList());
    }

    private String buildQuickSummary(String text) {
        if (text == null || text.isBlank()) return "";
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String s : sentences) {
            if (count >= 3) break;
            sb.append(s).append(" ");
            count++;
        }
        return sb.toString().trim();
    }
}