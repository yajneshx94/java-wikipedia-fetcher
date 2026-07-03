package com.ContentFetcher.wikipediafetcher.controller;

import com.ContentFetcher.wikipediafetcher.dto.SearchResult;
import com.ContentFetcher.wikipediafetcher.model.SearchHistory;
import com.ContentFetcher.wikipediafetcher.repository.SearchHistoryRepository;
import com.ContentFetcher.wikipediafetcher.service.WikipediaService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class SearchController {

    private final WikipediaService wikipediaService;
    private final SearchHistoryRepository historyRepo;

    public SearchController(WikipediaService wikipediaService,
                            SearchHistoryRepository historyRepo) {
        this.wikipediaService = wikipediaService;
        this.historyRepo = historyRepo;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<SearchHistory> recentSearches = historyRepo.findTop10ByOrderBySearchedAtDesc();
        model.addAttribute("recentSearches", recentSearches);
        return "index";
    }

    @PostMapping("/search")
    public String search(@RequestParam("query") String query, Model model) {
        model.addAttribute("lastQuery", query);

        if (query == null || query.trim().isEmpty()) {
            model.addAttribute("error", "Please enter a search term.");
            return "index";
        }

        try {
            SearchResult result = wikipediaService.search(query);

            if (result == null) {
                model.addAttribute("error", "Service returned no results.");
                return "index";
            }

            if (result.error() != null && !result.error().isEmpty()) {
                model.addAttribute("error", result.error());
                return "index";
            }

            if (result.article() != null) {
                model.addAttribute("article", result.article());
            } else {
                model.addAttribute("error", "No article found for: " + query);
            }

            if (result.suggestions() != null && !result.suggestions().isEmpty()) {
                model.addAttribute("suggestions", result.suggestions());
            }

        } catch (Exception e) {
            System.err.println("Error occurred while searching for: " + query);
            e.printStackTrace();
            model.addAttribute("error", "An unexpected error occurred: " + e.getMessage());
        }

        // Always show recent history in sidebar
        model.addAttribute("recentSearches", historyRepo.findTop10ByOrderBySearchedAtDesc());
        return "index";
    }
}
