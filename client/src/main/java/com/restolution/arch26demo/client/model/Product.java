package com.restolution.arch26demo.client.model;

import org.json.JSONObject;

public record Product(String id, String name, double price, int quantity) {
    public static Product fromCatalogItem(JSONObject item, int quantity) {
        return new Product(item.getString("id"), item.getString("name"), item.getDouble("price"), quantity);
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("price", price)
                .put("quantity", quantity);
    }
}
