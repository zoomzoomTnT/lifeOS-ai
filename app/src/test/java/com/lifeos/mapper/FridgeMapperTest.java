package com.lifeos.mapper;

import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeItemResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FridgeMapperTest {

    private final FridgeMapper mapper = new FridgeMapperImpl();

    @Test
    void toNewItemAppliesDefaultsAndNameNorm() {
        FridgeAddRequest req = new FridgeAddRequest(" 生菜 ", FoodCategory.VEG, null, null, 2);
        FridgeItem item = mapper.toNewItem(req, 9L);
        assertNull(item.getId());
        assertEquals(9L, item.getOwnerId());
        assertEquals(9L, item.getAddedById());
        assertEquals(" 生菜 ", item.getName());
        assertEquals("生菜", item.getNameNorm());
        assertEquals(FoodCategory.VEG, item.getCategory());
        assertEquals(FridgeLocation.FRIDGE, item.getLocation());
        assertEquals(FridgeStatus.IN_STOCK, item.getStatus());
        assertEquals(1d, item.getQty());
    }

    @Test
    void toResponseCopiesDomain() {
        FridgeItem item = mapper.toNewItem(new FridgeAddRequest("茶", FoodCategory.DRINK, FridgeLocation.FREEZER, 2d, null), 1L);
        FridgeItemResponse dto = mapper.toResponse(item);
        assertEquals("茶", dto.name());
        assertEquals("茶", dto.nameNorm());
        assertEquals(FridgeLocation.FREEZER, dto.location());
        assertEquals(FridgeStatus.IN_STOCK, dto.status());
        assertEquals(2d, dto.qty());
    }
}
