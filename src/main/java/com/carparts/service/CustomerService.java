package com.carparts.service;

import com.carparts.domain.Customer;
import com.carparts.repository.CustomerRepository;
import com.carparts.support.Text;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People who buy parts.
 *
 * <p>Thin, but not a pass-through. What it owns is the transaction: {@code open-in-view} is
 * disabled and controllers carry no {@code @Transactional}, so a read-modify-write done from a
 * controller would load a <em>detached</em> entity and the mutation would be silently discarded
 * — no error, no row changed. The boundary has to live somewhere, and this is where.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public Customer get(Long id) {
        return customers.findById(id).orElseThrow(() -> NotFoundException.of("customer", id));
    }

    /** A duplicate phone number or email is a 409 from {@code uq_customer_phone} / {@code _email}. */
    @Transactional
    public Customer create(String name, String phoneNumber, String email) {
        Customer customer = new Customer(name, phoneNumber);
        customer.setEmail(Text.blankToNull(email));
        return customers.save(customer);
    }

    /**
     * Changes only the fields supplied; a null leaves that one alone.
     *
     * <p><b>An email cannot be cleared here.</b> Blank is normalised to null before the request
     * is even validated, and null means "leave this alone" — so blank and omitted are the same
     * request. That is the deliberate trade: the alternative is a sentinel meaning "actually
     * blank this", which complicates every field to serve one rare case. What blank must never
     * do is reach the column: an empty email is not NULL, so two customers who both have none
     * would collide on {@code uq_customer_email} and the second would be told the address is
     * already registered. See {@link Text#blankToNull}.
     */
    @Transactional
    public Customer update(Long id, String name, String phoneNumber, String email) {
        Customer customer = get(id);
        if (name != null) {
            customer.setName(name);
        }
        if (phoneNumber != null) {
            customer.setPhoneNumber(phoneNumber);
        }
        if (email != null) {
            customer.setEmail(Text.blankToNull(email));
        }
        return customer;
    }

    /**
     * Removes a customer.
     *
     * <p>Refused by {@code fk_customer_order_customer} once they have ordered anything, and that
     * refusal is right: an invoice with nobody on it is worse than a customer who cannot be
     * deleted.
     */
    @Transactional
    public void delete(Long id) {
        customers.delete(get(id));
    }
}
