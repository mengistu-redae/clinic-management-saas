package com.clinicops.platform;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the Keycloak Organization first, then the local clinics row.
 * There's no compensating rollback call to Keycloak if the DB insert below
 * fails after the Keycloak org was already created - that would leave an
 * orphaned org, recoverable by hand via the Admin Console. Same caveat
 * create-demo-clinic.sh already carries for its own manual version of this
 * flow; not solved here either, since Keycloak doesn't offer a two-phase
 * -commit-friendly API to solve it properly against, and this is meant to
 * be a minimal admin surface, not a distributed-transaction system.
 */
@Service
public class ClinicProvisioningService {

    private final ClinicRepository clinicRepository;
    private final KeycloakOrganizationClient keycloakOrganizationClient;

    public ClinicProvisioningService(ClinicRepository clinicRepository, KeycloakOrganizationClient keycloakOrganizationClient) {
        this.clinicRepository = clinicRepository;
        this.keycloakOrganizationClient = keycloakOrganizationClient;
    }

    @Transactional
    public Clinic provisionClinic(String name, String orgAlias, String domain) {
        if (clinicRepository.findByKeycloakOrgId(orgAlias).isPresent()) {
            throw new ClinicAlreadyExistsException("A clinic already exists for org alias: " + orgAlias);
        }

        keycloakOrganizationClient.createOrganization(name, orgAlias, domain);

        Clinic clinic = new Clinic();
        clinic.setKeycloakOrgId(orgAlias);
        clinic.setName(name);
        return clinicRepository.save(clinic);
    }
}
