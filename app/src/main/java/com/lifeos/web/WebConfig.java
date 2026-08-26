package com.lifeos.web;

import com.lifeos.domain.DbEnum;
import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeResolveAction;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.domain.MemoKind;
import com.lifeos.domain.MemoStatus;
import com.lifeos.domain.PersonRole;
import com.lifeos.domain.ReceiptStatus;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        register(registry, FridgeStatus.class);
        register(registry, FridgeLocation.class);
        register(registry, FridgeResolveAction.class);
        register(registry, FoodCategory.class);
        register(registry, ReceiptStatus.class);
        register(registry, MemoKind.class);
        register(registry, MemoStatus.class);
        register(registry, PersonRole.class);
    }

    private static <E extends Enum<E> & DbEnum> void register(FormatterRegistry registry, Class<E> type) {
        registry.addConverter(String.class, type, s -> DbEnum.of(type, s));
    }
}
