package com.ContentFetcher.wikipediafetcher.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "search_history")
public class SearchHistory {

    @Id
    private String id;

    private String query;
    private String resolvedTitle;  // what article was actually returned
    private Instant searchedAt;

    public SearchHistory() {}

    public SearchHistory(String query, String resolvedTitle) {
        this.query = query;
        this.resolvedTitle = resolvedTitle;
        this.searchedAt = Instant.now();
    }

    public String getQuery()         { return query; }
    public String getResolvedTitle() { return resolvedTitle; }
    public Instant getSearchedAt()   { return searchedAt; }
}
