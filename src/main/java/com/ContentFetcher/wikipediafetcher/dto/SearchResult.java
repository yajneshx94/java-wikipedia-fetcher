package com.ContentFetcher.wikipediafetcher.dto;

import java.util.List;

public record SearchResult(
        Article article,
        List<String> suggestions,
        String error
) {

    public record Article(
            String title,
            String quickSummary,       // 2-3 sentence teaser
            String mainSummary,        // full cleaned extract
            String link,
            List<String> keyConcepts,  // extracted key topics from the article
            List<String> topicsToExplore  // ranked related topics
    ) {
        // Backward-compat: old code that only passes title/summary/link
        public Article(String title, String summary, String link) {
            this(title, buildQuickSummary(summary), summary, link, List.of(), List.of());
        }

        private static String buildQuickSummary(String text) {
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

    public static SearchResult success(Article article, List<String> suggestions) {
        return new SearchResult(article, suggestions, null);
    }

    public static SearchResult error(String errorMessage) {
        return new SearchResult(null, null, errorMessage);
    }
}
