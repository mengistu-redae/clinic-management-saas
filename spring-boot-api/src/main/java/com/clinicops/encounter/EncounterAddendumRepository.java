package com.clinicops.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EncounterAddendumRepository extends JpaRepository<EncounterAddendum, UUID> {

    List<EncounterAddendum> findAllByEncounterIdOrderByCreatedAtAsc(UUID encounterId);
}
