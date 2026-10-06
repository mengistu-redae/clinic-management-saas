package com.clinicops.clinicgroup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClinicGroupRepository extends JpaRepository<ClinicGroup, UUID> {
}
