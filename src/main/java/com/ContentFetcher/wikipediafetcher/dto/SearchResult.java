package com.ContentFetcher.wikipediafetcher.dto;

import java.util.List;

// Using Java Records for simple, immutable data carriers
public record SearchResult(Article article, List<String> suggestions, String error) {

    // A record to hold the main article's details
    public record Article(String title, String summary, String link) {}

    // Factory method for creating a success response
    public static SearchResult success(Article article, List<String> suggestions) {
        return new SearchResult(article, suggestions, null);
    }

    // Factory method for creating an error response
    public static SearchResult error(String errorMessage) {
        return new SearchResult(null, null, errorMessage);
    }
}