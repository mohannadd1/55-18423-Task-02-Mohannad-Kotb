package com.example.lab05.controller;

import com.example.lab05.dto.PurchaseRequest;
import com.example.lab05.model.mongo.PurchaseReceipt;
import com.example.lab05.service.PurchaseService;
import com.example.lab05.repository.mongo.PurchaseReceiptRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/55-18423/purchases")
public class PurchaseController {

    private final PurchaseService purchaseService;
    private final PurchaseReceiptRepository purchaseReceiptRepository;

    public PurchaseController(PurchaseService purchaseService,
                              PurchaseReceiptRepository purchaseReceiptRepository) {
        this.purchaseService = purchaseService;
        this.purchaseReceiptRepository = purchaseReceiptRepository;
    }

    @PostMapping
    public PurchaseReceipt purchase(@RequestBody PurchaseRequest request) {
        return purchaseService.executePurchase(request);
    }

    @GetMapping("/person/{personName}")
    public List<PurchaseReceipt> getByPerson(@PathVariable String personName) {
        return purchaseReceiptRepository.findByPersonName(personName);
    }
}
