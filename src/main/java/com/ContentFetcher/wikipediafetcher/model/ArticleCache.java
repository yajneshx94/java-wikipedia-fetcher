package com.ContentFetcher.wikipediafetcher.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.Instant;
import java.util.List;

@Document(collection = "article_cache")
public class ArticleCache {

    @Id
    private String id;

    @Indexed(unique = true)
    private String queryKey;       // normalized query (lowercase, trimmed)

    private String title;
    private String mainSummary;
    private String link;
    private List<String> keyConcepts;
    private List<String> topicsToExplore;
    private List<String> suggestions;

    @Indexed(expireAfterSeconds = 86400) // TTL: 24 hours
    private Instant cachedAt;

    public ArticleCache() {}

    public ArticleCache(String queryKey, String title, String mainSummary, String link,
                        List<String> keyConcepts, List<String> topicsToExplore,
                        List<String> suggestions) {
        this.queryKey = queryKey;
        this.title = title;
        this.mainSummary = mainSummary;
        this.link = link;
        this.keyConcepts = keyConcepts;
        this.topicsToExplore = topicsToExplore;
        this.suggestions = suggestions;
        this.cachedAt = Instant.now();
    }

    // Getters
    public String getQueryKey()            { return queryKey; }
    public String getTitle()               { return title; }
    public String getMainSummary()         { return mainSummary; }
    public String getLink()                { return link; }
    public List<String> getKeyConcepts()   { return keyConcepts; }
    public List<String> getTopicsToExplore() { return topicsToExplore; }
    public List<String> getSuggestions()   { return suggestions; }
    public Instant getCachedAt()           { return cachedAt; }
}
