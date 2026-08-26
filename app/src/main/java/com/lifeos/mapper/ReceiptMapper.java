package com.lifeos.mapper;

import com.lifeos.domain.Names;
import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.web.dto.ReceiptPreviewRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, imports = {Names.class, ReceiptStatus.class})
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
}
