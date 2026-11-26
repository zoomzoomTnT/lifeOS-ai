package com.lifeos.service.impl;

import com.lifeos.domain.Fingerprints;
import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.Names;
import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.mapper.FridgeMapper;
import com.lifeos.mapper.ReceiptMapper;
import com.lifeos.repo.ReceiptRepository;
import com.lifeos.repo.batch.ReceiptBatchWriter;
import com.lifeos.service.ReceiptService;
import com.lifeos.web.dto.ReceiptConfirmRequest;
import com.lifeos.web.dto.ReceiptConfirmResponse;
import com.lifeos.web.dto.ReceiptLookupRequest;
import com.lifeos.web.dto.ReceiptLookupResponse;
import com.lifeos.web.dto.ReceiptPreviewRequest;
import com.lifeos.web.dto.ReceiptPreviewResponse;
import com.lifeos.web.dto.ReceiptResponse;
import com.lifeos.web.NotFoundException;
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
    private final ReceiptMapper receiptMapper;
    private final FridgeMapper fridgeMapper;
    private final ReceiptBatchWriter batchWriter;

    @Override
    public ReceiptLookupResponse lookup(ReceiptLookupRequest request) {
        return receiptMapper.toLookup(findExisting(request).orElse(null));
    }

    @Override
    @Transactional
    public ReceiptPreviewResponse preview(ReceiptPreviewRequest request, long personId) {
        String fp = Fingerprints.receipt(
                request.barcode(), request.merchantName(), request.printedAt(), request.totalCents());
        Optional<Receipt> existing = findExisting(request);
        if (existing.isPresent()) {
            return receiptMapper.toDuplicate(existing.get());
        }

        long payerId = personId;
        String merchantName = request.merchantName() == null || request.merchantName().isBlank()
                ? "未知商户" : request.merchantName();
        List<ReceiptPreviewRequest.Line> items = request.items() == null ? List.of() : request.items();
        int computed = items.stream().mapToInt(i -> i.amountCents() == null ? 0 : i.amountCents()).sum();
        int total = request.totalCents() == null ? 0 : request.totalCents();
        boolean sumOk = Math.abs(computed - total) <= 2;

        // merchant id is resolved inside the batch (by name_norm); 0 is a placeholder here.
        Receipt pending = receiptMapper.toPending(request, 0L, payerId, fp, total, computed);
        Object raw = request.rawOcrJson();
        pending.setRawOcrJson(raw == null ? null : raw.toString());
        pending.setImagePath(request.imagePath());
        pending.setTaxCents(request.taxCents() == null ? 0 : request.taxCents());
        pending.setDiscountCents(request.discountCents() == null ? 0 : request.discountCents());

        List<ReceiptItem> rows = new ArrayList<>();
        List<ReceiptPreviewResponse.FoodHint> foodItems = new ArrayList<>();
        for (ReceiptPreviewRequest.Line line : items) {
            ReceiptItem item = receiptMapper.toItem(line, 0L);
            rows.add(item);
            if (item.isFood()) {
                foodItems.add(receiptMapper.toFoodHint(item));
            }
        }

        // One atomic write: merchant + receipt + items + event (one request on Turso).
        ReceiptBatchWriter.PreviewIds ids = batchWriter.writePreview(
                merchantName, Names.norm(merchantName), pending, rows, payerId);
        long receiptId = ids.receiptId();
        long merchantId = ids.merchantId();
        return receiptMapper.toCreated(receiptId, fp, sumOk, computed, total, merchantId, foodItems);
    }

    @Override
    @Transactional
    public ReceiptConfirmResponse confirm(long id, ReceiptConfirmRequest request, long personId) {
        Receipt r = receipts.findById(id).filter(x -> x.getPayerId() != null && x.getPayerId() == personId)
                .orElseThrow(() -> new NotFoundException("receipt not found: " + id)); // or someone else's
        if (r.getStatus() != ReceiptStatus.PENDING_CONFIRM) {
            return receiptMapper.toNotPending(r);
        }
        long actorId = personId;
        boolean alsoFridge = request != null && Boolean.TRUE.equals(request.alsoFridge());
        List<FridgeItem> toFridge = alsoFridge ? fridgeFromReceipt(id, actorId) : List.of();
        // One atomic write: status + fridge rows + event (one request on Turso).
        List<Long> fridgeIds = batchWriter.writeConfirm(id, toFridge, actorId);
        return receiptMapper.toConfirmed(id, fridgeIds);
    }

    @Override
    public List<ReceiptResponse> list(ReceiptStatus status, int limit) {
        return receiptMapper.toResponseList(receipts.list(status, limit));
    }

    private List<FridgeItem> fridgeFromReceipt(long receiptId, long ownerId) {
        List<FridgeItem> out = new ArrayList<>();
        for (ReceiptItem it : receipts.foodItems(receiptId)) {
            out.add(fridgeMapper.fromReceiptItem(it, ownerId, receiptId));
        }
        return out;
    }

    private Optional<Receipt> findExisting(ReceiptLookupRequest request) {
        return findExisting(request.barcode(), request.merchantName(), request.printedAt(), request.totalCents());
    }

    private Optional<Receipt> findExisting(ReceiptPreviewRequest request) {
        return findExisting(request.barcode(), request.merchantName(), request.printedAt(), request.totalCents());
    }

    private Optional<Receipt> findExisting(String barcode, String merchantName, String printedAt, Integer totalCents) {
        String fp = Fingerprints.receipt(barcode, merchantName, printedAt, totalCents);
        Optional<Receipt> hit = receipts.findByFingerprint(fp);
        if (hit.isPresent() || Fingerprints.normalizeBarcode(barcode).isEmpty()) {
            return hit;
        }
        return receipts.findByFingerprint(Fingerprints.legacy(barcode, printedAt));
    }
}
