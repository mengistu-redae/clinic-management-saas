package com.clinicops.platform;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClinicProvisioningServiceTest {

    private final ClinicRepository clinicRepository = mock(ClinicRepository.class);
    private final KeycloakOrganizationClient keycloakOrganizationClient = mock(KeycloakOrganizationClient.class);
    private final KeycloakUserProvisioningClient keycloakUserProvisioningClient = mock(KeycloakUserProvisioningClient.class);
    private final ClinicProvisioningService service =
            new ClinicProvisioningService(clinicRepository, keycloakOrganizationClient, keycloakUserProvisioningClient);

    @Test
    void aFreshAliasCreatesTheKeycloakOrgThenSavesTheLocalRow() {
        when(clinicRepository.findByKeycloakOrgId("new-clinic")).thenReturn(Optional.empty());
        when(keycloakOrganizationClient.createOrganization("New Clinic", "new-clinic", "new-clinic.example"))
                .thenReturn("kc-org-id-123");
        when(clinicRepository.save(any(Clinic.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Clinic result = service.provisionClinic("New Clinic", "new-clinic", "new-clinic.example");

        assertThat(result.getKeycloakOrgId()).isEqualTo("new-clinic");
        assertThat(result.getName()).isEqualTo("New Clinic");

        // Keycloak is called before the local save - verified call order.
        var order = inOrder(keycloakOrganizationClient, clinicRepository);
        order.verify(keycloakOrganizationClient).createOrganization("New Clinic", "new-clinic", "new-clinic.example");
        order.verify(clinicRepository).save(any(Clinic.class));
    }

    @Test
    void aTakenAliasThrowsAndNeverCallsKeycloakAtAll() {
        Clinic existing = new Clinic();
        existing.setKeycloakOrgId("taken-alias");
        when(clinicRepository.findByKeycloakOrgId("taken-alias")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.provisionClinic("Dupe", "taken-alias", "dupe.example"))
                .isInstanceOf(ClinicAlreadyExistsException.class);

        verify(keycloakOrganizationClient, never()).createOrganization(anyString(), anyString(), anyString());
        verify(clinicRepository, never()).save(any());
    }

    @Test
    void aKeycloakFailurePropagatesWithoutSavingAnInconsistentLocalRow() {
        when(clinicRepository.findByKeycloakOrgId("flaky-clinic")).thenReturn(Optional.empty());
        when(keycloakOrganizationClient.createOrganization(anyString(), anyString(), anyString()))
                .thenThrow(new KeycloakAdminException("Keycloak is unreachable"));

        assertThatThrownBy(() -> service.provisionClinic("Flaky Clinic", "flaky-clinic", "flaky.example"))
                .isInstanceOf(KeycloakAdminException.class);

        verify(clinicRepository, never()).save(any());
    }

    @Test
    void noAdminEmailNeverTouchesTheUserProvisioningClient() {
        when(clinicRepository.findByKeycloakOrgId("no-admin-clinic")).thenReturn(Optional.empty());
        when(keycloakOrganizationClient.createOrganization("No Admin Clinic", "no-admin-clinic", "no-admin.example"))
                .thenReturn("kc-org-id-456");
        when(clinicRepository.save(any(Clinic.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClinicProvisioningResult result =
                service.provisionClinic("No Admin Clinic", "no-admin-clinic", "no-admin.example", null, null);

        assertThat(result.initialAdminTemporaryPassword()).isNull();
        verify(keycloakUserProvisioningClient, never()).createUser(anyString(), anyString(), anyString());
    }

    @Test
    void anAdminEmailProvisionsARealClinicAdminLoginAndReturnsATemporaryPassword() {
        when(clinicRepository.findByKeycloakOrgId("with-admin-clinic")).thenReturn(Optional.empty());
        when(keycloakOrganizationClient.createOrganization("With Admin Clinic", "with-admin-clinic", "with-admin.example"))
                .thenReturn("kc-org-id-789");
        when(clinicRepository.save(any(Clinic.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(keycloakUserProvisioningClient.createUser("admin@with-admin.example", "Jane", "Doe"))
                .thenReturn("kc-user-id-1");

        ClinicProvisioningResult result = service.provisionClinic(
                "With Admin Clinic", "with-admin-clinic", "with-admin.example", "admin@with-admin.example", "Jane Doe");

        assertThat(result.clinic().getKeycloakOrgId()).isEqualTo("with-admin-clinic");
        assertThat(result.initialAdminTemporaryPassword()).isNotBlank();

        verify(keycloakUserProvisioningClient).createUser("admin@with-admin.example", "Jane", "Doe");
        verify(keycloakUserProvisioningClient).assignRealmRole("kc-user-id-1", "clinic_admin");
        verify(keycloakOrganizationClient).addMember("kc-org-id-789", "kc-user-id-1");
        verify(keycloakUserProvisioningClient).setTemporaryPassword("kc-user-id-1", result.initialAdminTemporaryPassword());
    }
}
