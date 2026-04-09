package com.example.lab05.service;

import com.example.lab05.dto.DashboardResponse;
import com.example.lab05.model.cassandra.SensorReading;
import com.example.lab05.model.elastic.ProductDocument;
import com.example.lab05.model.mongo.PurchaseReceipt;
import com.example.lab05.model.neo4j.Person;
import com.example.lab05.repository.mongo.PurchaseReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final PurchaseReceiptRepository purchaseReceiptRepository;
    private final SocialGraphService socialGraphService;
    private final SensorService sensorService;
    private final ProductSearchService productSearchService;
    private final RedisTemplate<String, Object> redisTemplate;

    public DashboardService(PurchaseReceiptRepository purchaseReceiptRepository,
                            SocialGraphService socialGraphService,
                            SensorService sensorService,
                            ProductSearchService productSearchService,
                            RedisTemplate<String, Object> redisTemplate) {
        this.purchaseReceiptRepository = purchaseReceiptRepository;
        this.socialGraphService = socialGraphService;
        this.sensorService = sensorService;
        this.productSearchService = productSearchService;
        this.redisTemplate = redisTemplate;
    }

    public DashboardResponse getDashboard(String personName) {
        String cacheKey = "dashboard:" + personName;

        // Step 0 — Redis cache check (soft)
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof DashboardResponse cachedDashboard) {
                return new DashboardResponse(
                        cachedDashboard.personName(),
                        cachedDashboard.totalSpent(),
                        cachedDashboard.purchaseCount(),
                        cachedDashboard.recentPurchases(),
                        cachedDashboard.friendRecommendations(),
                        cachedDashboard.friendsOfFriends(),
                        cachedDashboard.recentActivity(),
                        cachedDashboard.youMightAlsoLike(),
                        true
                );
            }
        } catch (Exception e) {
            log.warn("Redis cache check failed for {}: {}", personName, e.getMessage());
        }

        // Step 1 — MongoDB (hard)
        List<PurchaseReceipt> allReceipts = purchaseReceiptRepository.findByPersonName(personName);
        double totalSpent = allReceipts.stream()
                .mapToDouble(r -> r.getTotalPrice() != null ? r.getTotalPrice() : 0.0)
                .sum();
        int purchaseCount = allReceipts.size();
        List<PurchaseReceipt> recentPurchases = allReceipts.stream()
                .filter(r -> r.getPurchasedAt() != null)
                .sorted((a, b) -> b.getPurchasedAt().compareTo(a.getPurchasedAt()))
                .limit(5)
                .collect(Collectors.toList());

        // Step 2 — Neo4j (soft)
        List<Map<String, Object>> friendRecommendations = new ArrayList<>();
        List<String> friendsOfFriends = new ArrayList<>();
        try {
            friendRecommendations = socialGraphService.getRecommendations(personName, 5);
            List<Person> fof = socialGraphService.getFriendsOfFriends(personName);
            friendsOfFriends = fof.stream()
                    .map(Person::getName)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to fetch Neo4j data for {}: {}", personName, e.getMessage());
        }

        // Step 3 — Cassandra (soft)
        List<SensorReading> recentActivity = new ArrayList<>();
        try {
            recentActivity = sensorService.getLatestReadings(
                    "user-activity-" + personName.toLowerCase(), 10);
        } catch (Exception e) {
            log.warn("Failed to fetch activity for {}: {}", personName, e.getMessage());
        }

        // Step 4 — Elasticsearch (soft)
        List<String> youMightAlsoLike = new ArrayList<>();
        try {
            Set<String> alreadyPurchased = allReceipts.stream()
                    .map(PurchaseReceipt::getProductName)
                    .collect(Collectors.toSet());
            Set<String> categories = allReceipts.stream()
                    .map(PurchaseReceipt::getProductCategory)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            for (String category : categories) {
                List<ProductDocument> suggestions = productSearchService.getByCategory(category);
                suggestions.stream()
                        .filter(p -> !alreadyPurchased.contains(p.getName()))
                        .limit(2)
                        .map(ProductDocument::getName)
                        .forEach(youMightAlsoLike::add);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch ES suggestions for {}: {}", personName, e.getMessage());
        }

        // Step 5 — Construct and cache (soft on cache save)
        DashboardResponse response = new DashboardResponse(
                personName,
                totalSpent,
                purchaseCount,
                recentPurchases,
                friendRecommendations,
                friendsOfFriends,
                recentActivity,
                youMightAlsoLike,
                false
        );

        try {
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofMinutes(5));
        } catch (Exception e) {
            log.warn("Failed to cache dashboard for {}: {}", personName, e.getMessage());
        }

        return response;
    }
}
