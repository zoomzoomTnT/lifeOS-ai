package com.lifeos.service.impl;

import com.lifeos.domain.Fingerprints;
import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.domain.Names;
import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.repo.EventRepository;
import com.lifeos.repo.FridgeRepository;
import com.lifeos.repo.MerchantRepository;
import com.lifeos.repo.ReceiptRepository;
import com.lifeos.service.PersonService;
import com.lifeos.service.ReceiptService;
import com.lifeos.web.dto.ReceiptConfirmRequest;
import com.lifeos.web.dto.ReceiptConfirmResponse;
import com.lifeos.web.dto.ReceiptFoodHint;
import com.lifeos.web.dto.ReceiptLineRequest;
import com.lifeos.web.dto.ReceiptLookupRequest;
import com.lifeos.web.dto.ReceiptLookupResponse;
import com.lifeos.web.dto.ReceiptPreviewRequest;
import com.lifeos.web.dto.ReceiptPreviewResponse;
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

    @Override
    public ReceiptLookupResponse lookup(ReceiptLookupRequest request) {
        String fp = Fingerprints.receipt(request.barcode(), request.printedAt());
        Optional<Receipt> existing = receipts.findByFingerprint(fp);
        return existing.map(r -> new ReceiptLookupResponse(true, r))
                .orElseGet(() -> new ReceiptLookupResponse(false, null));
    }

    @Override
    @Transactional
    public ReceiptPreviewResponse preview(ReceiptPreviewRequest request, String handle) {
        String fp = Fingerprints.receipt(request.barcode(), request.printedAt());
        Optional<Receipt> existing = receipts.findByFingerprint(fp);
        if (existing.isPresent()) {
            Receipt r = existing.get();
            return new ReceiptPreviewResponse(
                    "duplicate", null, r.id(), r.status(), "同一张小票已经记过了",
                    fp, null, null, null, null, null
            );
        }

        long payerId = people.resolveId(handle);
        long merchantId = ensureMerchant(request.merchantName());
        List<ReceiptLineRequest> items = request.items() == null ? List.of() : request.items();
        int computed = items.stream().mapToInt(i -> i.amountCents() == null ? 0 : i.amountCents()).sum();
        int total = request.totalCents() == null ? 0 : request.totalCents();
        boolean sumOk = Math.abs(computed - total) <= 2;

        Receipt pending = new Receipt(
                null, merchantId, payerId, request.barcode(), request.printedAt(), fp,
                request.currency() == null ? "CNY" : request.currency(),
                total, computed, ReceiptStatus.PENDING_CONFIRM, null
        );
        String raw = request.rawOcrJson() == null ? null : request.rawOcrJson().toString();
        long receiptId = receipts.insertPending(
                pending, raw, request.imagePath(),
                request.taxCents() == null ? 0 : request.taxCents(),
                request.discountCents() == null ? 0 : request.discountCents()
        );

        int order = 0;
        List<ReceiptFoodHint> foodItems = new ArrayList<>();
        for (ReceiptLineRequest line : items) {
            boolean isFood = Boolean.TRUE.equals(line.isFood());
            String name = line.name();
            String nameNorm = Names.norm(name);
            receipts.insertItem(receiptId, new ReceiptItem(
                    null, receiptId, name, nameNorm,
                    line.qty() == null ? 1d : line.qty(),
                    line.amountCents() == null ? 0 : line.amountCents(),
                    isFood, line.category()
            ), order++);
            if (isFood) {
                foodItems.add(new ReceiptFoodHint(name, nameNorm, line.category()));
            }
        }

        events.insert("finance", "preview", payerId, "receipts", receiptId, null);

        return new ReceiptPreviewResponse(
                "create_pending", receiptId, null, null, null, fp,
                sumOk, computed, total, merchantId, foodItems
        );
    }

    @Override
    @Transactional
    public ReceiptConfirmResponse confirm(long id, ReceiptConfirmRequest request, String handle) {
        Receipt r = receipts.findById(id).orElseThrow(() -> new IllegalArgumentException("receipt not found: " + id));
        if (r.status() != ReceiptStatus.PENDING_CONFIRM) {
            return new ReceiptConfirmResponse(r.status(), id, List.of(), "not_pending");
        }
        receipts.markConfirmed(id);
        boolean alsoFridge = request != null && Boolean.TRUE.equals(request.alsoFridge());
        List<Long> fridgeIds = alsoFridge ? createFridgeFromReceipt(id, handle) : List.of();
        events.insert("finance", "confirm", people.resolveId(handle), "receipts", id, null);
        return new ReceiptConfirmResponse(ReceiptStatus.CONFIRMED, id, fridgeIds, null);
    }

    @Override
    public List<Receipt> list(ReceiptStatus status, int limit) {
        return receipts.list(status, limit);
    }

    private List<Long> createFridgeFromReceipt(long receiptId, String handle) {
        long ownerId = people.resolveId(handle);
        List<Long> ids = new ArrayList<>();
        for (ReceiptItem it : receipts.foodItems(receiptId)) {
            FridgeItem item = new FridgeItem(
                    null, ownerId, ownerId, it.name(), it.nameNorm(), it.category(),
                    FridgeLocation.FRIDGE, FridgeStatus.IN_STOCK, it.qty(),
                    null, receiptId, it.id()
            );
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
