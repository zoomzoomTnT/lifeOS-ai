package com.lifeos.domain;

import jakarta.persistence.Converter;

public final class DbEnumConverters {
    private DbEnumConverters() {}

    @Converter(autoApply = true)
    public static class PersonRoleConv extends DbEnumConverter<PersonRole> {
        public PersonRoleConv() { super(PersonRole.class); }
    }

    @Converter(autoApply = true)
    public static class FoodCategoryConv extends DbEnumConverter<FoodCategory> {
        public FoodCategoryConv() { super(FoodCategory.class); }
    }

    @Converter(autoApply = true)
    public static class FridgeLocationConv extends DbEnumConverter<FridgeLocation> {
        public FridgeLocationConv() { super(FridgeLocation.class); }
    }

    @Converter(autoApply = true)
    public static class FridgeStatusConv extends DbEnumConverter<FridgeStatus> {
        public FridgeStatusConv() { super(FridgeStatus.class); }
    }

    @Converter(autoApply = true)
    public static class MemoKindConv extends DbEnumConverter<MemoKind> {
        public MemoKindConv() { super(MemoKind.class); }
    }

    @Converter(autoApply = true)
    public static class MemoStatusConv extends DbEnumConverter<MemoStatus> {
        public MemoStatusConv() { super(MemoStatus.class); }
    }

    @Converter(autoApply = true)
    public static class ReceiptStatusConv extends DbEnumConverter<ReceiptStatus> {
        public ReceiptStatusConv() { super(ReceiptStatus.class); }
    }

    @Converter(autoApply = true)
    public static class MerchantKindConv extends DbEnumConverter<MerchantKind> {
        public MerchantKindConv() { super(MerchantKind.class); }
    }

    @Converter(autoApply = true)
    public static class LocationTagConv extends DbEnumConverter<LocationTag> {
        public LocationTagConv() { super(LocationTag.class); }
    }

    @Converter(autoApply = true)
    public static class MarketConv extends DbEnumConverter<Market> {
        public MarketConv() { super(Market.class); }
    }

    @Converter(autoApply = true)
    public static class StockEventKindConv extends DbEnumConverter<StockEventKind> {
        public StockEventKindConv() { super(StockEventKind.class); }
    }
}
