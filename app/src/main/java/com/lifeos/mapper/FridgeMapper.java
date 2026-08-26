package com.lifeos.mapper;

import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeResolveAction;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.domain.Names;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeItemResponse;
import com.lifeos.web.dto.FridgeWriteResponse;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, imports = {
        Names.class, FridgeLocation.class, FridgeStatus.class
})
public interface FridgeMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "ownerId", source = "ownerId")
    @Mapping(target = "addedById", source = "ownerId")
    @Mapping(target = "name", source = "request.name")
    @Mapping(target = "nameNorm", expression = "java(Names.norm(request.name()))")
    @Mapping(target = "category", source = "request.category")
    @Mapping(target = "location", expression = "java(request.location() == null ? FridgeLocation.FRIDGE : request.location())")
    @Mapping(target = "status", expression = "java(FridgeStatus.IN_STOCK)")
    @Mapping(target = "qty", expression = "java(request.qty() == null ? 1d : request.qty())")
    @Mapping(target = "expiresAt", ignore = true)
    @Mapping(target = "sourceReceiptId", ignore = true)
    @Mapping(target = "sourceReceiptItemId", ignore = true)
    FridgeItem toNewItem(FridgeAddRequest request, long ownerId);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "ownerId", source = "ownerId")
    @Mapping(target = "addedById", source = "ownerId")
    @Mapping(target = "name", source = "item.name")
    @Mapping(target = "nameNorm", source = "item.nameNorm")
    @Mapping(target = "category", source = "item.category")
    @Mapping(target = "location", expression = "java(FridgeLocation.FRIDGE)")
    @Mapping(target = "status", expression = "java(FridgeStatus.IN_STOCK)")
    @Mapping(target = "qty", source = "item.qty")
    @Mapping(target = "expiresAt", ignore = true)
    @Mapping(target = "sourceReceiptId", source = "receiptId")
    @Mapping(target = "sourceReceiptItemId", source = "item.id")
    FridgeItem fromReceiptItem(ReceiptItem item, long ownerId, long receiptId);

    FridgeItemResponse toResponse(FridgeItem item);

    List<FridgeItemResponse> toResponseList(List<FridgeItem> items);

    default FridgeWriteResponse toCreated(long id) {
        return new FridgeWriteResponse(id, FridgeStatus.IN_STOCK, null);
    }

    default FridgeWriteResponse toResolved(long id, FridgeResolveAction action) {
        return new FridgeWriteResponse(id, null, action);
    }
}
