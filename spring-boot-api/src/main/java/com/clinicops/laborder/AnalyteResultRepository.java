package com.clinicops.laborder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalyteResultRepository extends JpaRepository<AnalyteResult, UUID> {

    List<AnalyteResult> findAllByLabOrderTestId(UUID labOrderTestId);

    /** Full-replace semantics on re-entry, same convention as every other "replace the whole list" write in this codebase. */
    void deleteAllByLabOrderTestId(UUID labOrderTestId);
}
