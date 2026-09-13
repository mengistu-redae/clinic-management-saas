package com.clinicops.room;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    List<Room> findAllByTenantId(UUID tenantId);

    List<Room> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<Room> findByIdAndTenantId(UUID id, UUID tenantId);
}
