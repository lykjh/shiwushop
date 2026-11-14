package com.shiwushop.marketing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record MarketingSettings(@Value("${shop.marketing.enabled:false}") boolean enabled) {
}
