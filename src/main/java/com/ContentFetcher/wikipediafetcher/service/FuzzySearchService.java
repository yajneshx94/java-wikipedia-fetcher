package com.ContentFetcher.wikipediafetcher.service;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FuzzySearchService {

    private final LevenshteinDistance levenshtein = LevenshteinDistance.getDefaultInstance();

    /**
     * Given a user query and a list of known titles (e.g. from cache or prior searches),
     * returns the closest matching title if the distance is within threshold.
     * Returns null if no close match found.
     */
    public String findClosestMatch(String query, List<String> knownTitles) {
        if (knownTitles == null || knownTitles.isEmpty()) return null;

        String lowerQuery = query.toLowerCase().trim();
        String bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String title : knownTitles) {
            int distance = levenshtein.apply(lowerQuery, title.toLowerCase().trim());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = title;
            }
        }

        // Only suggest if distance is reasonable (≤30% of query length)
        int threshold = Math.max(3, (int) (lowerQuery.length() * 0.30));
        return (bestDistance <= threshold) ? bestMatch : null;
    }

    /**
     * Returns true if two strings are close enough to be considered
     * the same topic (used to avoid duplicate entries in related topics).
     */
    public boolean isSimilar(String a, String b) {
        int distance = levenshtein.apply(a.toLowerCase().trim(), b.toLowerCase().trim());
        int threshold = Math.max(2, (int) (a.length() * 0.25));
        return distance <= threshold;
    }
}
