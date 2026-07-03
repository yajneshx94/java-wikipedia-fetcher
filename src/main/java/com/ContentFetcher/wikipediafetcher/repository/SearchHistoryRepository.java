package com.ContentFetcher.wikipediafetcher.repository;

import com.ContentFetcher.wikipediafetcher.model.SearchHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SearchHistoryRepository extends MongoRepository<SearchHistory, String> {
    List<SearchHistory> findTop10ByOrderBySearchedAtDesc();
}
