package com.lifeos.mapper;

import com.lifeos.domain.Names;
import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.web.dto.ReceiptConfirmResponse;
import com.lifeos.web.dto.ReceiptLookupResponse;
import com.lifeos.web.dto.ReceiptPreviewRequest;
import com.lifeos.web.dto.ReceiptPreviewResponse;
import com.lifeos.web.dto.ReceiptResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE, imports = {Names.class, ReceiptStatus.class})
public interface ReceiptMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "merchantId", source = "merchantId")
    @Mapping(target = "payerId", source = "payerId")
    @Mapping(target = "barcode", source = "request.barcode")
    @Mapping(target = "printedAt", source = "request.printedAt")
    @Mapping(target = "fingerprint", source = "fingerprint")
    @Mapping(target = "currency", expression = "java(request.currency() == null ? \"CNY\" : request.currency())")
    @Mapping(target = "totalCents", source = "totalCents")
    @Mapping(target = "computedCents", source = "computedCents")
    @Mapping(target = "status", expression = "java(ReceiptStatus.PENDING_CONFIRM)")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "rawOcrJson", ignore = true)
    Receipt toPending(ReceiptPreviewRequest request, long merchantId, long payerId,
                      String fingerprint, int totalCents, int computedCents);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "receiptId", source = "receiptId")
    @Mapping(target = "name", source = "line.name")
    @Mapping(target = "nameNorm", expression = "java(Names.norm(line.name()))")
    @Mapping(target = "qty", expression = "java(line.qty() == null ? 1d : line.qty())")
    @Mapping(target = "amountCents", expression = "java(line.amountCents() == null ? 0 : line.amountCents())")
    @Mapping(target = "food", expression = "java(Boolean.TRUE.equals(line.isFood()))")
    @Mapping(target = "category", source = "line.category")
    ReceiptItem toItem(ReceiptPreviewRequest.Line line, long receiptId);

    ReceiptResponse toResponse(Receipt receipt);

    List<ReceiptResponse> toResponseList(List<Receipt> receipts);

    ReceiptPreviewResponse.FoodHint toFoodHint(ReceiptItem item);

    default ReceiptLookupResponse toLookup(Receipt receipt) {
        if (receipt == null) return new ReceiptLookupResponse(false, null);
        return new ReceiptLookupResponse(true, toResponse(receipt));
    }

    default ReceiptPreviewResponse toDuplicate(Receipt existing) {
        return new ReceiptPreviewResponse(
                "duplicate", null, existing.getId(), existing.getStatus(),
                "同一张小票已经记过了", null, null, null, null, null, null);
    }

    default ReceiptPreviewResponse toCreated(long receiptId, String fingerprint, boolean sumOk,
                                             int computedCents, int totalCents, long merchantId,
                                             List<ReceiptPreviewResponse.FoodHint> foodItems) {
        return new ReceiptPreviewResponse(
                "create_pending", receiptId, null, null, null, fingerprint,
                sumOk, computedCents, totalCents, merchantId, foodItems);
    }

    default ReceiptConfirmResponse toConfirmed(long id, List<Long> fridgeItemIds) {
        return new ReceiptConfirmResponse(ReceiptStatus.CONFIRMED, id, fridgeItemIds, null);
    }

    default ReceiptConfirmResponse toNotPending(Receipt receipt) {
        return new ReceiptConfirmResponse(receipt.getStatus(), receipt.getId(), null, "not_pending");
    }
}
