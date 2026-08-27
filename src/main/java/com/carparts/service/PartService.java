package com.carparts.service;

import com.carparts.domain.CarFitment;
import com.carparts.domain.CarFitmentId;
import com.carparts.domain.Part;
import com.carparts.domain.Supplier;
import com.carparts.repository.CarFitmentRepository;
import com.carparts.repository.PartRepository;
import com.carparts.repository.SupplierRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The catalogue: what is sold, what it costs, and which cars it fits. */
@Service
public class PartService {

    private final PartRepository parts;
    private final SupplierRepository suppliers;
    private final CarFitmentRepository fitments;

    public PartService(PartRepository parts, SupplierRepository suppliers,
                       CarFitmentRepository fitments) {
        this.parts = parts;
        this.suppliers = suppliers;
        this.fitments = fitments;
    }

    @Transactional
    public Part create(String sku, String name, BigDecimal price, BigDecimal weightKg,
                       String description, String manufacturingPlace, int reorderLevel,
                       Long supplierId) {
        Part part = new Part(sku, name, price, weightKg, supplier(supplierId));
        part.setDescription(description);
        part.setManufacturingPlace(manufacturingPlace);
        part.setReorderLevel(reorderLevel);
        return parts.save(part);
    }

    /**
     * Changes only the fields supplied; a null leaves that field alone.
     *
     * <p>This is why the endpoint is a PATCH. With PUT the caller had to resend every field to
     * change one, which is not merely tedious — two people editing different fields would each
     * send a complete object built from what they had read, and the second would overwrite the
     * first's change without either noticing. Sending only what you mean to alter removes that.
     *
     * <p><b>The SKU is not among the fields.</b> It is the identifier customers quote, suppliers
     * match on, and invoices print. Order lines already issued display it, so editing it silently
     * rewrites what those documents appear to say. A part with the wrong SKU is a new part, or a
     * correction made deliberately in the database — not an API call.
     */
    @Transactional
    public Part update(Long id, String name, BigDecimal price, BigDecimal weightKg,
                       String description, String manufacturingPlace, Integer reorderLevel,
                       Long supplierId) {
        Part part = get(id);
        if (name != null) {
            part.setName(name);
        }
        if (price != null) {
            part.setPrice(price);
        }
        if (weightKg != null) {
            part.setWeightKg(weightKg);
        }
        if (description != null) {
            part.setDescription(description);
        }
        if (manufacturingPlace != null) {
            part.setManufacturingPlace(manufacturingPlace);
        }
        if (reorderLevel != null) {
            part.setReorderLevel(reorderLevel);
        }
        if (supplierId != null) {
            part.setSupplier(supplier(supplierId));
        }
        return part;
    }

    @Transactional(readOnly = true)
    public Part get(Long id) {
        return parts.findWithSupplier(id).orElseThrow(() -> NotFoundException.of("part", id));
    }

    /**
     * Removes a part from the catalogue.
     *
     * <p>Refused by the database once the part has been sold or is stocked anywhere. An invoice
     * line pointing at a part that no longer exists is worse than a catalogue that keeps its
     * history, so the foreign keys are right to say no.
     */
    @Transactional
    public void delete(Long id) {
        parts.delete(get(id));
    }

    /**
     * Records that a part fits a car.
     *
     * <p>{@code ck_car_fitment_year_range} refuses a last year before the first, and the
     * composite primary key refuses the same make, model and starting year twice. Both are
     * checked here first so the caller gets a sentence rather than a constraint name.
     */
    @Transactional
    public CarFitment addFitment(Long partId, String make, String model, Short yearFrom, Short yearTo) {
        Part part = get(partId);
        if (yearTo < yearFrom) {
            throw new InvalidRequestException(
                    "the last model year (" + yearTo + ") cannot precede the first (" + yearFrom + ")");
        }
        CarFitmentId id = new CarFitmentId(partId, make, model, yearFrom);
        if (fitments.existsById(id)) {
            throw new InvalidRequestException(
                    part.getSku() + " already has a fitment for " + make + " " + model
                            + " from " + yearFrom);
        }
        return fitments.save(new CarFitment(part, make, model, yearFrom, yearTo));
    }

    /**
     * Corrects the last model year a fitment covers.
     *
     * <p>Only {@code yearTo} can change, and that is the schema talking rather than a limitation:
     * the part, make, model and first year make up the primary key, so altering any of them makes
     * it a different fitment. {@code yearTo} is the one detail that can be wrong without the
     * fitment itself being wrong — a model stays in production a year longer than expected.
     */
    @Transactional
    public CarFitment correctFitmentEnd(Long partId, String make, String model,
                                        Short yearFrom, Short yearTo) {
        CarFitment fitment = fitments.findById(new CarFitmentId(partId, make, model, yearFrom))
                .orElseThrow(() -> new NotFoundException(
                        "no fitment for " + make + " " + model + " from " + yearFrom
                                + " on part " + partId));
        if (yearTo < yearFrom) {
            throw new InvalidRequestException(
                    "the last model year (" + yearTo + ") cannot precede the first (" + yearFrom + ")");
        }
        fitment.setYearTo(yearTo);
        return fitment;
    }

    /** Removes a fitment. Identified by the same three fields that key it. */
    @Transactional
    public void removeFitment(Long partId, String make, String model, Short yearFrom) {
        CarFitmentId id = new CarFitmentId(partId, make, model, yearFrom);
        if (!fitments.existsById(id)) {
            throw new NotFoundException(
                    "no fitment for " + make + " " + model + " from " + yearFrom + " on part " + partId);
        }
        fitments.deleteById(id);
    }

    private Supplier supplier(Long id) {
        return suppliers.findById(id).orElseThrow(() -> NotFoundException.of("supplier", id));
    }
}
