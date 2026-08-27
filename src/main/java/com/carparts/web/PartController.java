package com.carparts.web;

import com.carparts.domain.Part;
import com.carparts.repository.PartRepository;
import com.carparts.service.NotFoundException;
import com.carparts.service.PartService;
import com.carparts.web.dto.Requests.CorrectFitmentRequest;
import com.carparts.web.dto.Requests.FitmentRequest;
import com.carparts.web.dto.Requests.PartRequest;
import com.carparts.web.dto.Requests.UpdatePartRequest;
import com.carparts.web.dto.Responses.FitmentResponse;
import com.carparts.web.dto.Responses.PartResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue.
 *
 * <p>No {@code @Transactional} here. Every query fetches the supplier it needs, so nothing is
 * left to resolve while the response is being written — see the note on {@link OrderController}.
 */
@RestController
@RequestMapping("/api/parts")
@Tag(name = "Parts", description = "The catalogue, and which cars each part fits")
public class PartController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PartRepository parts;
    private final PartService service;

    public PartController(PartRepository parts, PartService service) {
        this.parts = parts;
        this.service = service;
    }

    /** How a caller may order the catalogue. A closed set, not a column name from the query string. */
    public enum PartSort {
        SKU("sku"), NAME("name"), CHEAPEST("price"), DEAREST("price"), HEAVIEST("weightKg");

        private final String property;

        PartSort(String property) {
            this.property = property;
        }

        Sort toSort() {
            return this == DEAREST || this == HEAVIEST
                    ? Sort.by(Sort.Direction.DESC, property)
                    : Sort.by(Sort.Direction.ASC, property);
        }
    }

    /**
     * Searches the catalogue.
     *
     * <p>{@code make}, {@code model} and {@code year} answer the question this business is
     * actually asked — <em>what fits my 2017 Civic</em>. Any of the three may be given alone:
     * make and model without a year lists everything for that car whatever its age.
     *
     * <p>Paged, and the size is capped. An uncapped {@code size} is an invitation to request the
     * entire catalogue in one call, and the server would try.
     */
    @GetMapping
    @Operation(summary = "Search parts",
               description = "Matches name or SKU. make/model/year finds what fits a given car.")
    public Page<PartResponse> search(
            @Parameter(description = "matched against name and SKU, case-insensitively")
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "e.g. Toyota — with model and year, finds what fits that car")
            @RequestParam(required = false) String make,
            @RequestParam(required = false) String model,
            @Parameter(description = "model year the part must cover")
            @RequestParam(required = false) Short year,
            @RequestParam(defaultValue = "SKU") PartSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), sort.toSort());

        return parts.search(search, supplierId, minPrice, maxPrice, make, model, year, pageable)
                .map(PartResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one part")
    public PartResponse get(@PathVariable Long id) {
        return PartResponse.from(service.get(id));
    }

    /**
     * The cars this part fits.
     *
     * <p>404s on an unknown part rather than returning an empty list: "fits nothing" and "there
     * is no such part" are different answers, and a caller acting on the first when the second is
     * true would be misled.
     */
    @GetMapping("/{id}/fitments")
    @Operation(summary = "List the cars a part fits")
    public List<FitmentResponse> fitments(@PathVariable Long id) {
        if (!parts.existsById(id)) {
            throw NotFoundException.of("part", id);
        }
        return parts.findFitments(id).stream().map(FitmentResponse::from).toList();
    }

    /** Records that this part fits a car. */
    @PostMapping("/{id}/fitments")
    @Operation(summary = "Record a fitment",
               description = "The same make, model and first year cannot be recorded twice.")
    public ResponseEntity<FitmentResponse> addFitment(
            @PathVariable Long id, @Valid @RequestBody FitmentRequest request) {
        FitmentResponse created = FitmentResponse.from(service.addFitment(
                id, request.make(), request.model(), request.yearFrom(), request.yearTo()));
        return ResponseEntity.created(URI.create("/api/parts/" + id + "/fitments")).body(created);
    }

    /**
     * Corrects the last model year a fitment covers.
     *
     * <p>Only {@code yearTo} is changeable, because the part, make, model and first year are the
     * fitment's primary key — altering any of those makes it a different fitment, which is a
     * DELETE and a POST. A model staying in production a year longer than expected is the case
     * this exists for.
     */
    @PatchMapping("/{id}/fitments")
    @Operation(summary = "Correct a fitment's last model year",
               description = "Only yearTo can change; the other fields are the fitment's identity.")
    public FitmentResponse correctFitment(
            @PathVariable Long id,
            @RequestParam String make,
            @RequestParam String model,
            @RequestParam Short yearFrom,
            @Valid @RequestBody CorrectFitmentRequest request) {
        return FitmentResponse.from(
                service.correctFitmentEnd(id, make, model, yearFrom, request.yearTo()));
    }

    /** Removes a fitment, identified by the three fields that key it. */
    @DeleteMapping("/{id}/fitments")
    @Operation(summary = "Remove a fitment")
    public ResponseEntity<Void> removeFitment(
            @PathVariable Long id,
            @RequestParam String make,
            @RequestParam String model,
            @RequestParam Short yearFrom) {
        service.removeFitment(id, make, model, yearFrom);
        return ResponseEntity.noContent().build();
    }

    /**
     * Adds a part.
     *
     * <p>A duplicate SKU is a 409 from {@code uq_part_sku}. {@code reorderLevel} drives the
     * low-stock report; left at zero the part never reports as low, since stock cannot fall
     * below zero.
     */
    @PostMapping
    @Operation(summary = "Add a part", description = "reorderLevel drives the low-stock report.")
    public ResponseEntity<PartResponse> create(@Valid @RequestBody PartRequest request) {
        Part created = service.create(request.sku(), request.name(), request.price(),
                request.weightKg(), request.description(), request.manufacturingPlace(),
                request.reorderLevel(), request.supplierId());
        return ResponseEntity
                .created(URI.create("/api/parts/" + created.getId()))
                .body(PartResponse.from(created));
    }

    /**
     * Changes only the fields supplied.
     *
     * <p>PATCH rather than PUT, and not merely for convenience. Requiring a full object to change
     * one field meant two people editing different things — one repricing, one setting a reorder
     * level — each sent a complete object built from what they had read, and the second silently
     * overwrote the first.
     *
     * <p><b>The SKU cannot be changed.</b> Order lines already issued print it, so editing it
     * rewrites what those invoices appear to say. Repricing, by contrast, is safe: each line
     * captured its own price at sale.
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Update a part",
               description = "Only the fields supplied change. SKU is immutable; repricing never affects existing orders.")
    public PartResponse update(@PathVariable Long id, @Valid @RequestBody UpdatePartRequest request) {
        return PartResponse.from(service.update(id, request.name(), request.price(),
                request.weightKg(), request.description(), request.manufacturingPlace(),
                request.reorderLevel(), request.supplierId()));
    }

    /**
     * Removes a part from the catalogue.
     *
     * <p>Refused with 409 once it has been sold or is stocked anywhere. An invoice line pointing
     * at a part that no longer exists would be worse than a catalogue that keeps its history.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a part",
               description = "Refused while the part is stocked or appears on any order.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
