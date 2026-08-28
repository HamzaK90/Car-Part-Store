package com.carparts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the invoice actually says, not merely that bytes came back.
 *
 * <p>Asserting the response is a PDF proves the renderer ran. It does not prove the document
 * carries the right figures, and an invoice that quietly shows today's catalogue price instead
 * of the price agreed at sale would pass such a check comfortably. So these read the text out
 * of the PDF's content streams and assert on it.
 *
 * <p>That also guards something subtler: openhtmltopdf renders {@code letter-spacing} by
 * positioning each glyph separately, which leaves the text layer reading {@code C A N C E L L E
 * D}. The document looks right and is unsearchable. Extraction is the only way to see it.
 */
@DisplayName("what the invoice says")
class InvoiceContentTest extends AbstractWebTest {

    private static final Pattern STREAM = Pattern.compile("stream\\r?\\n(.*?)endstream", Pattern.DOTALL);
    private static final Pattern SHOWN = Pattern.compile("\\((?:[^()\\\\]|\\\\.)*\\)");

    /** The readable text of a PDF, without a PDF library. */
    private static String textOf(byte[] pdf) {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        Matcher streams = STREAM.matcher(raw);
        while (streams.find()) {
            byte[] chunk = streams.group(1).getBytes(StandardCharsets.ISO_8859_1);
            byte[] body = inflate(chunk);
            Matcher shown = SHOWN.matcher(new String(body, StandardCharsets.ISO_8859_1));
            while (shown.find()) {
                String s = shown.group();
                out.append(s, 1, s.length() - 1).append(' ');
            }
        }
        return out.toString();
    }

    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    break;
                }
                out.write(buffer, 0, n);
            }
            return out.size() > 0 ? out.toByteArray() : data;
        } catch (Exception e) {
            return data;   // not compressed; the raw bytes are already the content
        } finally {
            inflater.end();
        }
    }

    private byte[] invoiceFor(long orderId, String token) throws Exception {
        return perform(get("/api/orders/" + orderId + "/invoice.pdf", token))
                .getResponse().getContentAsByteArray();
    }

    private record Sale(long orderId, long partId, String sku, double total) {}

    /** An order of its own, at a known price, so the figures on the document are predictable. */
    private Sale sell(int quantity, String buyerToken) throws Exception {
        String admin = login("layla");
        String sku = "IV-" + System.nanoTime();
        JsonNode supplier = body(post("/api/suppliers", admin,
                Map.of("name", "Invoice Vendor " + System.nanoTime())));
        JsonNode part = body(post("/api/parts", admin, Map.of(
                "sku", sku, "name", "Invoice Part", "price", 45.00, "weightKg", 1.0,
                "reorderLevel", 0, "supplierId", supplier.path("id").asLong())));
        long partId = part.path("id").asLong();
        body(post("/api/warehouses/3/stock", login("omar"),
                Map.of("partId", partId, "quantity", 100)));
        JsonNode order = body(post("/api/orders", buyerToken, Map.of(
                "customerId", 1, "branchId", 1, "warehouseId", 3,
                "lines", List.of(Map.of("partId", partId, "quantity", quantity)))));
        return new Sale(order.path("id").asLong(), partId, sku, order.path("total").asDouble());
    }

    @Test
    @DisplayName("the document carries the order, the parties and every line")
    void carriesTheOrder() throws Exception {
        String staff = login("omar");
        Sale sale = sell(3, staff);

        String text = textOf(invoiceFor(sale.orderId(), staff));

        assertThat(text).contains(String.valueOf(sale.orderId()));
        assertThat(text).as("the customer").contains("Ahmad Sweidan");
        assertThat(text).as("the branch that took it").contains("Downtown Branch");
        assertThat(text).as("the warehouse it came from").contains("Zarqa Warehouse");
        assertThat(text).as("the line's SKU").contains(sale.sku());
        assertThat(text).as("the total").contains("135.00");
        assertThat(text).as("the unit price").contains("45.00");
    }

    @Test
    @DisplayName("every word is searchable text, not glyphs spaced apart")
    void textIsSearchable() throws Exception {
        String staff = login("omar");
        String text = textOf(invoiceFor(sell(1, staff).orderId(), staff));

        // The heading and the status are the two that were broken by letter-spacing.
        assertThat(text).contains("Invoice").doesNotContain("I n v o i c e");
        assertThat(text).contains("PLACED").doesNotContain("P L A C E D");
    }

    @Test
    @DisplayName("repricing the part does not move the invoice — criterion 4, on the document")
    void repricingDoesNotMoveTheDocument() throws Exception {
        String staff = login("omar");
        Sale sale = sell(2, staff);
        assertThat(sale.total()).isEqualTo(90.00);

        body(patch("/api/parts/" + sale.partId(), login("layla"), Map.of("price", 4321.00)));

        String text = textOf(invoiceFor(sale.orderId(), staff));
        assertThat(text).as("the price agreed at sale").contains("45.00").contains("90.00");
        assertThat(text).as("today's catalogue price has no business here")
                .doesNotContain("4321.00");
    }

    @Test
    @DisplayName("a cancelled order keeps its invoice, and the invoice says so")
    void cancelledOrderIsMarked() throws Exception {
        String staff = login("omar");
        Sale sale = sell(1, staff);
        body(post("/api/orders/" + sale.orderId() + "/cancel", staff, null));

        String text = textOf(invoiceFor(sale.orderId(), staff));

        // A document that simply disappears leaves nothing to reconcile against.
        assertThat(text).contains("CANCELLED");
        assertThat(text).as("and explains what became of the parts")
                .contains("returned to stock");
    }

    @Test
    @DisplayName("an order with no named handler prints without inventing one")
    void noHandlerPrintsCleanly() throws Exception {
        String staff = login("omar");
        Sale sale = sell(1, login("admin"));   // the account with nobody on the payroll

        String text = textOf(invoiceFor(sale.orderId(), staff));

        assertThat(text).contains("Invoice");
        assertThat(text).as("the served-by line is omitted, not filled with a placeholder")
                .doesNotContain("Served by");
    }

    @Test
    @DisplayName("an invoice for an order that does not exist is a 404, not an empty PDF")
    void unknownOrder() throws Exception {
        String staff = login("omar");
        JsonNode problem = body(get("/api/orders/999999/invoice.pdf", staff));
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(problem.path("type").asText()).endsWith("/not-found");

        assertThat(status(get("/api/orders/abc/invoice.pdf", staff))).isEqualTo(400);
        assertThat(status(get("/api/orders/1001/invoice.pdf", null))).isEqualTo(401);
    }

    @Test
    @DisplayName("served inline, with a filename for saving")
    void servedInline() throws Exception {
        var response = perform(get("/api/orders/1001/invoice.pdf", login("omar"))).getResponse();
        assertThat(response.getContentType()).startsWith("application/pdf");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("inline").contains("invoice-1001.pdf");
    }
}
