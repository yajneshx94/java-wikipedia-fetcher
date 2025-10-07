package com.ContentFetcher.wikipediafetcher.controller;

import com.ContentFetcher.wikipediafetcher.dto.SearchResult;
import com.ContentFetcher.wikipediafetcher.service.WikipediaService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SearchController {

    private final WikipediaService wikipediaService;

    public SearchController(WikipediaService wikipediaService) {
        this.wikipediaService = wikipediaService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/search")
    public String search(@RequestParam("query") String query, Model model) {
        // Store the query for display
        model.addAttribute("lastQuery", query);

        if (query == null || query.trim().isEmpty()) {
            model.addAttribute("error", "Please enter a search term.");
            return "index";
        }

        try {
            SearchResult result = wikipediaService.search(query);

            // Check if result is null (shouldn't happen but safety first)
            if (result == null) {
                model.addAttribute("error", "Service returned no results.");
                return "index";
            }

            // Handle error response
            if (result.error() != null && !result.error().isEmpty()) {
                model.addAttribute("error", result.error());
                return "index";
            }

            // Handle success response - but check for null article
            if (result.article() != null) {
                model.addAttribute("article", result.article());
            } else {
                model.addAttribute("error", "No article found for: " + query);
            }

            // Add suggestions if they exist
            if (result.suggestions() != null && !result.suggestions().isEmpty()) {
                model.addAttribute("suggestions", result.suggestions());
            }

        } catch (Exception e) {
            System.err.println("Error occurred while searching for: " + query);
            e.printStackTrace();
            model.addAttribute("error", "An unexpected error occurred: " + e.getMessage());
        }

        return "index";
    }
}