package fi.kinetik.arch26demo.client;

import fi.kinetik.arch26demo.client.model.Product;
import fi.kinetik.arch26demo.client.model.Receipt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Synthesizes a fake receipt from whatever catalog is currently loaded. */
public class ReceiptSimulator {
    private static final Logger LOG = LogManager.getLogger(ReceiptSimulator.class);
    private static final int MAX_LINE_ITEMS = 3;

    private final CatalogSyncService catalogSync;
    private final String clientId;
    private final Random random = new Random();

    public ReceiptSimulator(CatalogSyncService catalogSync, String clientId) {
        this.catalogSync = catalogSync;
        this.clientId = clientId;
    }

    /** Returns null if no catalog has been fetched yet. */
    public Receipt simulate() {
        JSONArray catalog = catalogSync.currentCatalog();
        if (catalog == null || catalog.isEmpty()) {
            LOG.warn("no catalog loaded yet, skipping receipt simulation");
            return null;
        }

        int lineCount = 1 + random.nextInt(MAX_LINE_ITEMS);
        List<Product> products = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            JSONObject item = catalog.getJSONObject(random.nextInt(catalog.length()));
            products.add(Product.fromCatalogItem(item, 1 + random.nextInt(3)));
        }

        Receipt receipt = Receipt.create(clientId, products);
        LOG.info("simulated receipt {} with {} line item(s), total={}", receipt.receiptId(), products.size(), receipt.totalAmount());
        return receipt;
    }
}
