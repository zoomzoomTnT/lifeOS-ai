package com.lifeos.mapper;

import com.lifeos.domain.Holding;
import com.lifeos.domain.StockEvent;
import com.lifeos.web.dto.HoldingResponse;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.HoldingWriteResponse;
import com.lifeos.web.dto.StockEventRequest;
import com.lifeos.web.dto.StockEventResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface HoldingMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "ownerId", source = "ownerId")
    @Mapping(target = "symbol", expression = "java(request.symbol() == null ? null : request.symbol().trim().toUpperCase())")
    @Mapping(target = "market", source = "request.market")
    @Mapping(target = "name", source = "request.name")
    @Mapping(target = "qty", expression = "java(request.qty() == null ? 0d : request.qty())")
    @Mapping(target = "avgCost", source = "request.avgCost")
    @Mapping(target = "currency", expression = "java(request.currency() == null || request.currency().isBlank() ? request.market().defaultCurrency() : request.currency().trim().toUpperCase())")
    @Mapping(target = "notes", source = "request.notes")
    Holding toNewHolding(HoldingUpsertRequest request, long ownerId);

    @Mapping(target = "events", ignore = true)
    HoldingResponse toResponse(Holding holding);

    default HoldingResponse toResponse(Holding holding, List<StockEvent> events) {
        HoldingResponse base = toResponse(holding);
        return new HoldingResponse(
                base.id(), base.ownerId(), base.symbol(), base.market(), base.name(),
                base.qty(), base.avgCost(), base.currency(), base.notes(),
                base.createdAt(), base.updatedAt(), toEventList(events));
    }

    List<HoldingResponse> toResponseList(List<Holding> holdings);

    default HoldingWriteResponse toWritten(long id, String symbol, com.lifeos.domain.Market market, boolean created) {
        return new HoldingWriteResponse(id, symbol, market, created);
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "holdingId", source = "holdingId")
    @Mapping(target = "kind", source = "request.kind")
    @Mapping(target = "eventDate", source = "request.eventDate")
    @Mapping(target = "notes", source = "request.notes")
    @Mapping(target = "memoId", source = "request.memoId")
    StockEvent toNewEvent(StockEventRequest request, long holdingId);

    StockEventResponse toEventResponse(StockEvent event);

    List<StockEventResponse> toEventList(List<StockEvent> events);
}
