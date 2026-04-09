package com.example.lab05.service;

import com.example.lab05.dto.PurchaseRequest;
import com.example.lab05.model.cassandra.SensorReading;
import com.example.lab05.model.cassandra.SensorReadingKey;
import com.example.lab05.model.elastic.ProductDocument;
import com.example.lab05.model.mongo.PurchaseReceipt;
import com.example.lab05.repository.mongo.PurchaseReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final ProductService productService;
    private final PurchaseReceiptRepository purchaseReceiptRepository;
    private final SocialGraphService socialGraphService;
    private final SensorService sensorService;
    private final ProductSearchService productSearchService;
    private final RedisTemplate<String, Object> redisTemplate;

    public PurchaseService(ProductService productService,
                           PurchaseReceiptRepository purchaseReceiptRepository,
                           SocialGraphService socialGraphService,
                           SensorService sensorService,
                           ProductSearchService productSearchService,
                           RedisTemplate<String, Object> redisTemplate) {
        this.productService = productService;
        this.purchaseReceiptRepository = purchaseReceiptRepository;
        this.socialGraphService = socialGraphService;
        this.sensorService = sensorService;
        this.productSearchService = productSearchService;
        this.redisTemplate = redisTemplate;
    }

    public PurchaseReceipt executePurchase(PurchaseRequest request) {

        // Step 1 — PostgreSQL (HARD dependency)
        var product = productService.getProductById(request.productId());
        if (product.getStockQuantity() < request.quantity()) {
            throw new RuntimeException("Insufficient stock for product: " + product.getName());
        }
        product.setStockQuantity(product.getStockQuantity() - request.quantity());
        productService.updateProduct(product.getId(), product);

        // Step 2 — MongoDB (HARD dependency)
        PurchaseReceipt receipt = new PurchaseReceipt(
                request.personName(),
                product.getName(),
                product.getCategory(),
                request.quantity(),
                product.getPrice(),
                request.purchaseDetails()
        );
        receipt = purchaseReceiptRepository.save(receipt);

        // Step 3 — Neo4j (soft)
        try {
            socialGraphService.purchase(
                    request.personName(),
                    product.getName(),
                    request.quantity(),
                    product.getPrice()
            );
        } catch (Exception e) {
            log.warn("Failed to create PURCHASED edge for {} -> {}: {}",
                    request.personName(), product.getName(), e.getMessage());
        }

        // Step 4 — Cassandra (soft)
        try {
            SensorReading reading = new SensorReading();
            reading.setKey(new SensorReadingKey("user-activity-" + request.personName().toLowerCase(), null));
            reading.setLocation(product.getName());
            reading.setTemperature(0.0);
            reading.setHumidity(0.0);
            sensorService.recordReading(reading);
        } catch (Exception e) {
            log.warn("Failed to log purchase event for {}: {}",
                    request.personName(), e.getMessage());
        }

        // Step 5 — Elasticsearch (soft)
        try {
            if (product.getStockQuantity() == 0) {
                List<ProductDocument> results = productSearchService.searchByName(product.getName());
                if (!results.isEmpty()) {
                    ProductDocument esProduct = results.get(0);
                    esProduct.setInStock(false);
                    productSearchService.saveProduct(esProduct);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to update ES inStock for product {}: {}",
                    product.getId(), e.getMessage());
        }

        // Step 6 — Redis (soft)
        try {
            String cacheKey = "dashboard:" + request.personName();
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            log.warn("Failed to evict dashboard cache for {}: {}",
                    request.personName(), e.getMessage());
        }

        return receipt;
    }
}
