package com.lifeos.mapper;

import com.lifeos.domain.Holding;
import com.lifeos.domain.Market;
import com.lifeos.domain.StockEvent;
import com.lifeos.domain.StockEventKind;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.StockEventRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HoldingMapperTest {

    private final HoldingMapper mapper = new HoldingMapperImpl();

    @Test
    void toNewHoldingNormalizesSymbolAndCurrency() {
        HoldingUpsertRequest req = new HoldingUpsertRequest(" aapl ", Market.US, "Apple", 2d, 180.5, null, "calls");
        Holding holding = mapper.toNewHolding(req, 1L);
        assertNull(holding.getId());
        assertEquals(1L, holding.getOwnerId());
        assertEquals("AAPL", holding.getSymbol());
        assertEquals(Market.US, holding.getMarket());
        assertEquals("Apple", holding.getName());
        assertEquals(2d, holding.getQty());
        assertEquals(180.5, holding.getAvgCost());
        assertEquals("USD", holding.getCurrency());
        assertEquals("calls", holding.getNotes());
    }

    @Test
    void toNewEventCopiesKindAndMemo() {
        StockEvent event = mapper.toNewEvent(
                new StockEventRequest(StockEventKind.OPTIONS_EXPIRY, "2026-09-18", "weekly", 9L), 7L);
        assertEquals(7L, event.getHoldingId());
        assertEquals(StockEventKind.OPTIONS_EXPIRY, event.getKind());
        assertEquals("2026-09-18", event.getEventDate());
        assertEquals("weekly", event.getNotes());
        assertEquals(9L, event.getMemoId());
    }
}
