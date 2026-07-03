package com.ContentFetcher.wikipediafetcher.repository;

import com.ContentFetcher.wikipediafetcher.model.ArticleCache;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ArticleCacheRepository extends MongoRepository<ArticleCache, String> {
    Optional<ArticleCache> findByQueryKey(String queryKey);
}
