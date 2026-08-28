package com.carparts.service;

import com.carparts.domain.Address;
import com.carparts.domain.CustomerOrder;
import com.carparts.domain.Employee;
import com.carparts.domain.OrderItem;
import com.carparts.repository.CustomerOrderRepository;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/** Renders an order as a printable invoice. */
@Service
public class InvoiceService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private final CustomerOrderRepository orders;
    private final TemplateEngine templates;

    public InvoiceService(CustomerOrderRepository orders, TemplateEngine templates) {
        this.orders = orders;
        this.templates = templates;
    }

    /**
     * Everything the document says, as plain values.
     *
     * <p>The template is given this and never an entity. Putting a {@code CustomerOrder} in the
     * context would have the template walk {@code order.customer.name} while the page is being
     * laid out — the same mistake as serialising an entity from a controller, and it only
     * appears to work while a session happens to be open.
     *
     * <p>{@code handlerName} is null for an order taken by an account with nobody on the
     * payroll behind it, and the template omits that line rather than inventing a name.
     */
    public record Invoice(
            Long number,
            String status,
            boolean cancelled,
            String date,
            String branchName,
            String branchAddress,
            String warehouseName,
            String handlerName,
            String customerName,
            String customerPhone,
            String customerEmail,
            List<InvoiceLine> lines,
            String total) {}

    /** One line, already formatted, so the template has nothing left to decide. */
    public record InvoiceLine(String sku, String name, int quantity,
                              String unitPrice, String amount) {}

    /**
     * The invoice for an order, as PDF bytes.
     *
     * <p>Two steps on purpose. {@link #load} reads the order and flattens it; rendering then
     * happens out here, holding no database connection. Laying out a PDF is unbounded work — it
     * grows with the number of lines — and doing it inside a transaction would keep a
     * connection from the pool for the whole of it, which is the same objection that keeps
     * {@code @Transactional} off the controllers.
     *
     * @throws NotFoundException if there is no such order
     */
    public byte[] render(Long orderId) {
        Context context = new Context(Locale.UK);
        context.setVariable("invoice", load(orderId));

        return toPdf(templates.process("invoice", context), orderId);
    }

    /**
     * Reads the order and turns it into plain values.
     *
     * <p>No {@code @Transactional}: {@code findByIdWithItems} fetch-joins the customer, the
     * handler, the branch, the warehouse, the lines and each line's part, so the graph is fully
     * loaded by the time the repository's own transaction ends. That is the same reason
     * {@code GET /api/orders/{id}} needs none. A plain finder here would throw a
     * {@code LazyInitializationException} partway through building the document.
     *
     * <p>Money is formatted once, here. A half-up scale of two is what an invoice is expected to
     * show, and doing it in one place stops a line and the total rounding by different rules.
     */
    private Invoice load(Long orderId) {
        CustomerOrder order = orders.findByIdWithItems(orderId)
                .orElseThrow(() -> NotFoundException.of("order", orderId));

        Employee handler = order.getEmployee();
        return new Invoice(
                order.getId(),
                order.getStatus().name(),
                order.getStatus() == com.carparts.domain.OrderStatus.CANCELLED,
                order.getOrderDate().format(DATE),
                order.getBranch().getName(),
                oneLine(order.getBranch().getAddress()),
                order.getWarehouse().getName(),
                handler == null ? null : handler.getFullName(),
                order.getCustomer().getName(),
                order.getCustomer().getPhoneNumber(),
                order.getCustomer().getEmail(),
                order.getItems().stream().map(InvoiceService::toLine).toList(),
                money(order.total()));
    }

    private static InvoiceLine toLine(OrderItem item) {
        return new InvoiceLine(
                item.getPart().getSku(),
                item.getPart().getName(),
                item.getQuantity(),
                money(item.getUnitPrice()),
                money(item.lineTotal()));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String oneLine(Address address) {
        if (address == null) {
            return null;
        }
        String street = address.getStreet();
        String city = address.getCity();
        if (street == null) {
            return city;
        }
        return city == null ? street : street + ", " + city;
    }

    /**
     * Turns the rendered HTML into a PDF.
     *
     * <p>{@code withHtmlContent} is given an empty base URI deliberately. A document that could
     * resolve relative URLs would fetch whatever they pointed at while rendering, which turns a
     * template into a way of making the server issue requests. An invoice needs no external
     * resource, so it is given no way to ask for one.
     */
    private static byte[] toPdf(String html, Long orderId) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new PdfRendererBuilder()
                    .useFastMode()
                    .withHtmlContent(html, "")
                    .toStream(out)
                    .run();
            return out.toByteArray();
        } catch (IOException e) {
            // Writing to a byte array cannot fail for want of a disk or a network, so this is a
            // genuine surprise rather than an expected condition, and 500 is the honest answer.
            //
            // Deliberately NOT IllegalStateException: ApiExceptionHandler maps that to 409
            // "Not allowed", which is how a domain object refuses an operation. A renderer
            // falling over is not the caller doing something disallowed, and reporting it as a
            // conflict would send them away to fix a request that was fine.
            throw new UncheckedIOException("could not render the invoice for order " + orderId, e);
        }
    }
}
