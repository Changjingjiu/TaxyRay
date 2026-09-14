package io.github.taxray.data.remote

/** Product categories select estimates, not a merchant's actual tax status. */
internal enum class ReceiptCategory(val wire: String, val label: String, val ratePercent: String) {
    GENERAL_GOODS("general_goods", "一般商品", "13"),
    PROCESSED_FOOD("processed_food", "加工食品", "13"),
    PROCESSED_DAIRY("processed_dairy", "加工乳制品", "13"),
    AGRICULTURAL_PRODUCT("agricultural_product", "初级农产品", "9"),
    FRESH_MILK("fresh_milk", "鲜奶", "9"),
    EDIBLE_OIL_SALT("edible_oil_salt", "食用植物油及盐", "9"),
    PUBLICATION("publication", "出版物", "9"),
    TRANSPORT("transport", "交通运输", "9"),
    CATERING("catering", "餐饮服务", "6"),
    ACCOMMODATION("accommodation", "住宿服务", "6"),
    LIFE_SERVICE("life_service", "生活服务", "6"),
    UNKNOWN("unknown", "类别待确认", "13");

    companion object {
        fun fromWire(value: String): ReceiptCategory = entries.single { it.wire == value }
    }
}

internal enum class ClassificationIssue(val wire: String, val hint: String?) {
    NONE("none", null),
    NAME_INCOMPLETE("name_incomplete", "品名不完整 可补充品名并调整税率"),
    CATEGORY_AMBIGUOUS("category_ambiguous", "商品类别不明确 可调整税率");

    companion object {
        fun fromWire(value: String): ClassificationIssue = entries.single { it.wire == value }
    }
}

internal object ReceiptClassification {
    // These narrow product-name exclusions implement 2026 Announcement 9, Annex 1.
    // Never infer primary agriculture from an ingredient (e.g. milk/meat/flour) alone.
    // This is not a general keyword classifier and does not override service transactions.
    fun guardPrimaryFood(category: ReceiptCategory, name: String): ReceiptCategory {
        if (category != ReceiptCategory.AGRICULTURAL_PRODUCT && category != ReceiptCategory.FRESH_MILK) return category
        if (listOf("饼干", "糕点", "蛋糕", "馅饼", "方便面", "速冻饺子", "冰淇淋").any(name::contains)) {
            return ReceiptCategory.PROCESSED_FOOD
        }
        if (listOf("酸奶", "酸牛奶", "发酵乳", "发酵奶", "奶酪", "奶油", "调制乳").any(name::contains)) {
            return ReceiptCategory.PROCESSED_DAIRY
        }
        return category
    }
}
