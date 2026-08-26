package com.lifeos.service.impl;

import com.lifeos.domain.Fingerprints;
import com.lifeos.domain.FoodCategory;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public Map<String, Object> lookup(Map<String, Object> body) {
        String fp = Fingerprints.receipt(Bodies.str(body, "barcode"), Bodies.str(body, "printed_at"));
        Optional<Receipt> existing = receipts.findByFingerprint(fp);
        if (existing.isEmpty()) return Map.of("found", false);
        return Map.of("found", true, "receipt", existing.get());
    }

    @Override
    @Transactional
    public Map<String, Object> preview(Map<String, Object> body, String handle) {
        String fp = Fingerprints.receipt(Bodies.str(body, "barcode"), Bodies.str(body, "printed_at"));
        Optional<Receipt> existing = receipts.findByFingerprint(fp);
        if (existing.isPresent()) {
            Receipt r = existing.get();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("action", "duplicate");
            out.put("existing_receipt_id", r.id());
            out.put("status", r.status().db());
            out.put("message", "同一张小票已经记过了");
            return out;
        }

        long payerId = people.resolveId(handle);
        long merchantId = ensureMerchant(Bodies.str(body, "merchant_name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("items", List.of());
        int computed = items.stream().mapToInt(i -> Bodies.intVal(i.get("amount_cents"), 0)).sum();
        int total = Bodies.intVal(body.get("total_cents"), 0);
        boolean sumOk = Math.abs(computed - total) <= 2;

        String currency = Bodies.str(body.getOrDefault("currency", "CNY"));
        Receipt pending = new Receipt(
                null, merchantId, payerId, Bodies.str(body, "barcode"), Bodies.str(body, "printed_at"), fp,
                currency, total, computed, ReceiptStatus.PENDING_CONFIRM, null
        );
        Object raw = body.get("raw_ocr_json");
        long receiptId = receipts.insertPending(
                pending, raw == null ? null : raw.toString(), Bodies.str(body, "image_path"),
                Bodies.intVal(body.get("tax_cents"), 0),
                Bodies.intVal(body.get("discount_cents"), 0)
        );

        int order = 0;
        List<Map<String, Object>> foodItems = new ArrayList<>();
        for (Map<String, Object> line : items) {
            boolean isFood = Bodies.bool(line.get("is_food"));
            String name = Bodies.str(line.get("name"));
            String nameNorm = Names.norm(name);
            FoodCategory category = line.get("category") == null ? null : FoodCategory.from(Bodies.str(line.get("category")));
            receipts.insertItem(receiptId, new ReceiptItem(
                    null, receiptId, name, nameNorm,
                    Bodies.doubleVal(line.get("qty"), 1d),
                    Bodies.intVal(line.get("amount_cents"), 0),
                    isFood, category
            ), order++);
            if (isFood) {
                foodItems.add(Map.of("name", name, "name_norm", nameNorm,
                        "category", category == null ? "" : category.db()));
            }
        }

        events.insert("finance", "preview", payerId, "receipts", receiptId, null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "create_pending");
        out.put("receipt_id", receiptId);
        out.put("fingerprint", fp);
        out.put("sum_ok", sumOk);
        out.put("computed_cents", computed);
        out.put("total_cents", total);
        out.put("merchant_id", merchantId);
        out.put("food_items", foodItems);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> confirm(long id, Map<String, Object> body, String handle) {
        Receipt r = receipts.findById(id).orElseThrow(() -> new IllegalArgumentException("receipt not found: " + id));
        if (r.status() != ReceiptStatus.PENDING_CONFIRM) {
            return Map.of("error", "not_pending", "status", r.status().db());
        }
        receipts.markConfirmed(id);
        boolean alsoFridge = body != null && Bodies.bool(body.get("also_fridge"));
        List<Long> fridgeIds = alsoFridge ? createFridgeFromReceipt(id, handle) : List.of();
        events.insert("finance", "confirm", people.resolveId(handle), "receipts", id, null);
        return Map.of("status", ReceiptStatus.CONFIRMED.db(), "receipt_id", id, "fridge_item_ids", fridgeIds);
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
