package com.carparts.service;

import com.carparts.domain.Address;
import com.carparts.domain.Supplier;
import com.carparts.repository.SupplierRepository;
import com.carparts.support.Text;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vendors the shop buys from. It owns the transaction — see {@link CustomerService}. */
@Service
public class SupplierService {

    private final SupplierRepository suppliers;

    public SupplierService(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    @Transactional(readOnly = true)
    public Supplier get(Long id) {
        return suppliers.findById(id).orElseThrow(() -> NotFoundException.of("supplier", id));
    }

    /** A duplicate name is a 409 from {@code uq_supplier_name}. */
    @Transactional
    public Supplier create(String name, String city, String street, String phoneNumber) {
        Supplier supplier = new Supplier(name);
        supplier.setAddress(new Address(city, street));
        supplier.setPhoneNumber(Text.blankToNull(phoneNumber));
        return suppliers.save(supplier);
    }

    /**
     * Changes only the fields supplied; a null leaves that one alone.
     *
     * <p>The address is rebuilt whole rather than field by field, because {@code Address} is one
     * embedded value and not two columns that move independently — a request naming only the
     * city must carry the existing street through instead of writing null over it.
     */
    @Transactional
    public Supplier update(Long id, String name, String city, String street, String phoneNumber) {
        Supplier supplier = get(id);
        if (name != null) {
            supplier.setName(name);
        }
        if (phoneNumber != null) {
            supplier.setPhoneNumber(Text.blankToNull(phoneNumber));
        }
        if (city != null || street != null) {
            // The address is one embedded value, not two columns that move independently: a
            // request naming only the city would otherwise erase the street it did not mention.
            supplier.setAddress(Address.merged(supplier.getAddress(), city, street));
        }
        return supplier;
    }

    /**
     * Removes a supplier.
     *
     * <p>Refused by {@code fk_part_supplier} while any part in the catalogue still names them.
     * A part must have a supplier — the column is NOT NULL — so there is nowhere for those parts
     * to go, and deleting the vendor would have to take the catalogue with it.
     */
    @Transactional
    public void delete(Long id) {
        suppliers.delete(get(id));
    }
}
