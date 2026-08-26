package com.lifeos.service.impl;

import com.lifeos.domain.Fingerprints;
import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.Names;
import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.mapper.FridgeMapper;
import com.lifeos.mapper.ReceiptMapper;
import com.lifeos.repo.EventRepository;
import com.lifeos.repo.FridgeRepository;
import com.lifeos.repo.MerchantRepository;
import com.lifeos.repo.ReceiptRepository;
import com.lifeos.service.PersonService;
import com.lifeos.service.ReceiptService;
import com.lifeos.web.dto.ReceiptConfirmRequest;
import com.lifeos.web.dto.ReceiptConfirmResponse;
import com.lifeos.web.dto.ReceiptLookupRequest;
import com.lifeos.web.dto.ReceiptLookupResponse;
import com.lifeos.web.dto.ReceiptPreviewRequest;
import com.lifeos.web.dto.ReceiptPreviewResponse;
import com.lifeos.web.dto.ReceiptResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReceiptServiceImpl implements ReceiptService {

    private final ReceiptRepository receipts;
    private final MerchantRepository merchants;
    private final FridgeRepository fridge;
    private final EventRepository events;
    private final PersonService people;
    private final ReceiptMapper receiptMapper;
    private final FridgeMapper fridgeMapper;

    @Override
    public ReceiptLookupResponse lookup(ReceiptLookupRequest request) {
        String fp = Fingerprints.receipt(request.barcode(), request.printedAt());
        Optional<Receipt> existing = receipts.findByFingerprint(fp);
        return receiptMapper.toLookup(existing.orElse(null));
    }

    @Override
    @Transactional
    public ReceiptPreviewResponse preview(ReceiptPreviewRequest request, String handle) {
        String fp = Fingerprints.receipt(request.barcode(), request.printedAt());
        Optional<Receipt> existing = receipts.findByFingerprint(fp);
        if (existing.isPresent()) {
            return receiptMapper.toDuplicate(existing.get());
        }

        long payerId = people.resolveId(handle);
        long merchantId = ensureMerchant(request.merchantName());
        List<ReceiptPreviewRequest.Line> items = request.items() == null ? List.of() : request.items();
        int computed = items.stream().mapToInt(i -> i.amountCents() == null ? 0 : i.amountCents()).sum();
        int total = request.totalCents() == null ? 0 : request.totalCents();
        boolean sumOk = Math.abs(computed - total) <= 2;

        Receipt pending = receiptMapper.toPending(request, merchantId, payerId, fp, total, computed);
        Object raw = request.rawOcrJson();
        long receiptId = receipts.insertPending(
                pending, raw == null ? null : raw.toString(), request.imagePath(),
                request.taxCents() == null ? 0 : request.taxCents(),
                request.discountCents() == null ? 0 : request.discountCents()
        );

        int order = 0;
        List<ReceiptPreviewResponse.FoodHint> foodItems = new ArrayList<>();
        for (ReceiptPreviewRequest.Line line : items) {
            ReceiptItem item = receiptMapper.toItem(line, receiptId);
            receipts.insertItem(receiptId, item, order++);
            if (item.food()) {
                foodItems.add(receiptMapper.toFoodHint(item));
            }
        }

        events.insert("finance", "preview", payerId, "receipts", receiptId, null);
        return receiptMapper.toCreated(receiptId, fp, sumOk, computed, total, merchantId, foodItems);
    }

    @Override
    @Transactional
    public ReceiptConfirmResponse confirm(long id, ReceiptConfirmRequest request, String handle) {
        Receipt r = receipts.findById(id).orElseThrow(() -> new IllegalArgumentException("receipt not found: " + id));
        if (r.status() != ReceiptStatus.PENDING_CONFIRM) {
            return receiptMapper.toNotPending(r);
        }
        receipts.markConfirmed(id);
        boolean alsoFridge = request != null && Boolean.TRUE.equals(request.alsoFridge());
        List<Long> fridgeIds = alsoFridge ? createFridgeFromReceipt(id, handle) : List.of();
        events.insert("finance", "confirm", people.resolveId(handle), "receipts", id, null);
        return receiptMapper.toConfirmed(id, fridgeIds);
    }

    @Override
    public List<ReceiptResponse> list(ReceiptStatus status, int limit) {
        return receiptMapper.toResponseList(receipts.list(status, limit));
    }

    private List<Long> createFridgeFromReceipt(long receiptId, String handle) {
        long ownerId = people.resolveId(handle);
        List<Long> ids = new ArrayList<>();
        for (ReceiptItem it : receipts.foodItems(receiptId)) {
            FridgeItem item = fridgeMapper.fromReceiptItem(it, ownerId, receiptId);
            ids.add(fridge.insertInStock(item, null));
        }
        return ids;
    }

    private long ensureMerchant(String name) {
        if (name == null || name.isBlank()) name = "未知商户";
        String norm = Names.norm(name);
        String insertName = name;
        return merchants.findIdByNameNorm(norm)
                .orElseGet(() -> merchants.insert(insertName, norm));
    }
}
