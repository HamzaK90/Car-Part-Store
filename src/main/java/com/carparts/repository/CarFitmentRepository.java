package com.carparts.repository;

import com.carparts.domain.CarFitment;
import com.carparts.domain.CarFitmentId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Which cars a part fits.
 *
 * <p>Reading a part's fitments goes through {@link PartRepository#findFitments}; this exists for
 * writing them, where the composite key is needed directly.
 */
public interface CarFitmentRepository extends JpaRepository<CarFitment, CarFitmentId> {
}
