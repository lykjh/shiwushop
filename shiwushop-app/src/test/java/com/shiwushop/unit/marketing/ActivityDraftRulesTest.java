package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.ActivityDraftRules;
import com.shiwushop.marketing.dto.ActivityDraft;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ActivityDraftRulesTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static ValidatorFactory validation;
    static final String BASE = """
            {"name":"冬日活动","type":"FULL_REDUCTION","startAt":"2026-11-01T00:00:00+08:00",
             "endAt":"2026-12-01T00:00:00+08:00","eligibleLevelCodes":["ORDINARY","SILVER"],"rulesText":"指定商品优惠",
             "scopes":[{"skuId":1,"scopeRole":"SALE"}],"tiers":[{"thresholdAmount":20,"reductionAmount":5}],
             "couponRule":null,"budgetProposals":[{"party":"MERCHANT","shopId":10,"budgetAmount":100,"platformShareBp":0}]}
            """;

    @BeforeAll
    static void start() {
        validation = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void stop() {
        validation.close();
    }

    ActivityDraft draft(ObjectNode node) {
        return JSON.treeToValue(node, ActivityDraft.class);
    }

    ObjectNode base() {
        return (ObjectNode) JSON.readTree(BASE);
    }

    static Stream<Arguments> validTypes() {
        return Stream.of(
                Arguments.of("MEMBER_PRICE",
                        "{\"skuId\":1,\"scopeRole\":\"SALE\",\"memberPrices\":{\"SILVER\":10,\"GOLD\":10,\"PLATINUM\":8}}"),
                Arguments.of("LIMITED_PRICE", "{\"skuId\":1,\"scopeRole\":\"SALE\",\"activityPrice\":10}"),
                Arguments.of("FLASH", "{\"skuId\":1,\"scopeRole\":\"SALE\",\"activityPrice\":10,\"perUserLimit\":1}"),
                Arguments.of("GROUP",
                        "{\"skuId\":1,\"scopeRole\":\"SALE\",\"activityPrice\":10,\"teamSize\":2,\"participationSeconds\":60}"),
                Arguments.of("BARGAIN",
                        "{\"skuId\":1,\"scopeRole\":\"SALE\",\"bargainStartPrice\":10,\"bargainTargetPrice\":9.98,\"requiredHelpers\":2,\"participationSeconds\":60,\"dailyHelpLimit\":5,\"maxOpenSessions\":10}"),
                Arguments.of("POINTS",
                        "{\"skuId\":1,\"scopeRole\":\"REDEEM\",\"pointCostUnits\":100,\"perUserLimit\":1,\"unitCostAmount\":0}"),
                Arguments.of("FULL_REDUCTION", "{\"skuId\":1,\"scopeRole\":\"SALE\"}"),
                Arguments.of("FULL_GIFT", "{\"skuId\":1,\"scopeRole\":\"SALE\"}"),
                Arguments.of("COUPON", "{\"skuId\":1,\"scopeRole\":\"SALE\"}"));
    }

    @ParameterizedTest
    @MethodSource("validTypes")
    void eachActivityTypeAcceptsOnlyItsDefinedShape(String type, String scope) {
        var node = base();
        node.put("type", type);
        node.set("scopes", JSON.readTree("[" + scope + "]"));
        if (!type.equals("FULL_REDUCTION"))
            node.putArray("tiers");
        if (type.equals("FULL_GIFT")) {
            node.withArray("scopes").add(JSON.readTree("{\"skuId\":2,\"scopeRole\":\"GIFT\",\"unitCostAmount\":0}"));
            node.withArray("tiers").add(JSON.readTree("{\"thresholdAmount\":20,\"giftSkuId\":2,\"giftQuantity\":1}"));
        }
        if (type.equals("COUPON"))
            node.set("couponRule", JSON.readTree("""
                    {"faceAmount":5,"thresholdAmount":20,"issueLimit":100,"perUserLimit":1,
                     "claimEndAt":"2026-12-01T00:00:00+08:00","validityDays":30,"grantMode":"CLAIM"}
                    """));
        var dto = draft(node);
        assertTrue(validation.getValidator().validate(dto).isEmpty());
        assertDoesNotThrow(() -> ActivityDraftRules.validate(dto));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "status", "creatorUserId", "reviewerUserId"})
    void serverOwnedFieldsAreRejectedRatherThanIgnored(String field) {
        var node = base();
        node.put(field, "forged");
        assertThrows(tools.jackson.core.JacksonException.class, () -> draft(node));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"1\"", "true", "9223372036854775808"})
    void skuIdentityIsNotCoercedOrTruncated(String value) {
        var node = base();
        ((ObjectNode) node.path("scopes").get(0)).set("skuId", JSON.readTree(value));
        assertThrows(tools.jackson.core.JacksonException.class, () -> draft(node));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"1\"", "true", "2147483648"})
    void countsAreNotCoercedOrTruncated(String value) {
        var node = base();
        ((ObjectNode) node.path("scopes").get(0)).set("perUserLimit", JSON.readTree(value));
        assertThrows(tools.jackson.core.JacksonException.class, () -> draft(node));
    }

    @Test
    void wrongTypeFieldAndDuplicatedThresholdAreRejected() {
        var node = base();
        ((ObjectNode) node.path("scopes").get(0)).put("activityPrice", 10);
        assertEquals(400,
                assertThrows(BusinessException.class, () -> ActivityDraftRules.validate(draft(node))).getCode());
        var duplicate = base();
        duplicate.withArray("tiers").add(JSON.readTree("{\"thresholdAmount\":20.00,\"reductionAmount\":4}"));
        assertThrows(BusinessException.class, () -> ActivityDraftRules.validate(draft(duplicate)));
    }

    @Test
    void equalInstantsAndSubMillisecondCollapsedWindowAreRejected() {
        var equal = base();
        equal.put("endAt", "2026-10-31T16:00:00Z");
        assertThrows(BusinessException.class, () -> ActivityDraftRules.validate(draft(equal)));
        var small = base();
        small.put("startAt", "2026-11-01T00:00:00.0001+08:00");
        small.put("endAt", "2026-11-01T00:00:00.0009+08:00");
        assertThrows(BusinessException.class, () -> ActivityDraftRules.validate(draft(small)));
    }

    @Test
    void duplicateLevelsAndMixedPartyShapeAreRejected() {
        var duplicate = base();
        duplicate.withArray("eligibleLevelCodes").add("SILVER");
        assertThrows(BusinessException.class, () -> ActivityDraftRules.validate(draft(duplicate)));
        var mixed = base();
        ((ObjectNode) mixed.path("budgetProposals").get(0)).put("party", "PLATFORM");
        assertThrows(BusinessException.class, () -> ActivityDraftRules.validate(draft(mixed)));
    }

    @Test
    void unknownNestedFieldsAreRejected() {
        var node = base();
        ((ObjectNode) node.path("budgetProposals").get(0)).put("heldAmount", 0);
        assertThrows(tools.jackson.core.JacksonException.class, () -> draft(node));
    }
}
