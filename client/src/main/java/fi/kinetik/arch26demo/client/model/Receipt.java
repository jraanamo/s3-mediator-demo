package fi.kinetik.arch26demo.client.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Receipt(String receiptId, String clientId, Instant date, double totalAmount, List<Product> products) {

    public static Receipt create(String clientId, List<Product> products) {
        double total = products.stream().mapToDouble(p -> p.price() * p.quantity()).sum();
        return new Receipt(UUID.randomUUID().toString(), clientId, Instant.now(), total, products);
    }

    public String toJson() {
        JSONArray productArray = new JSONArray();
        products.forEach(p -> productArray.put(p.toJson()));
        return new JSONObject()
                .put("receiptId", receiptId)
                .put("clientId", clientId)
                .put("date", date.toString())
                .put("totalAmount", totalAmount)
                .put("products", productArray)
                .toString();
    }
}
