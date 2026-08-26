package com.carparts.repository;

import com.carparts.domain.Supplier;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByName(String name);

    boolean existsByName(String name);
}
