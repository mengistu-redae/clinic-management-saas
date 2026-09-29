package com.clinicops.accounting;

import com.clinicops.payment.Payment;
import com.clinicops.payment.Refund;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JournalServiceTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountSeedingService accountSeedingService = mock(AccountSeedingService.class);
    private final JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
    private final JournalLineRepository journalLineRepository = mock(JournalLineRepository.class);
    private final JournalService service = new JournalService(
            accountRepository, accountSeedingService, journalEntryRepository, journalLineRepository);

    private Account account(UUID tenantId, String code, String type) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setTenantId(tenantId);
        account.setCode(code);
        account.setType(type);
        return account;
    }

    private void stubAccounts(UUID tenantId, Account cash, Account revenueOrRefunds) {
        when(accountRepository.findByTenantIdAndCode(tenantId, "1000")).thenReturn(Optional.of(cash));
        when(accountRepository.findByTenantIdAndCode(tenantId, "4000")).thenReturn(Optional.of(revenueOrRefunds));
        when(accountRepository.findByTenantIdAndCode(tenantId, "4900")).thenReturn(Optional.of(revenueOrRefunds));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry entry = invocation.getArgument(0);
            entry.setId(UUID.randomUUID());
            return entry;
        });
    }

    @Test
    void postingAPaymentEnsuresSeedingThenDebitsCashAndCreditsRevenue() {
        UUID tenantId = UUID.randomUUID();
        Account cash = account(tenantId, "1000", "asset");
        Account revenue = account(tenantId, "4000", "revenue");
        stubAccounts(tenantId, cash, revenue);

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setTenantId(tenantId);
        payment.setAppointmentId(UUID.randomUUID());
        payment.setAmount(new BigDecimal("50.00"));
        payment.setMethod("card");
        payment.setRecordedBy(UUID.randomUUID());

        service.postForPayment(payment);

        verify(accountSeedingService).ensureSeeded(tenantId);
        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getSourceType()).isEqualTo("appointment_payment");
        assertThat(entryCaptor.getValue().getSourceId()).isEqualTo(payment.getId());
        assertThat(entryCaptor.getValue().getPostedBy()).isEqualTo(payment.getRecordedBy());

        ArgumentCaptor<JournalLine> lineCaptor = ArgumentCaptor.forClass(JournalLine.class);
        verify(journalLineRepository, times(2)).save(lineCaptor.capture());
        JournalLine debit = lineCaptor.getAllValues().get(0);
        JournalLine credit = lineCaptor.getAllValues().get(1);
        assertThat(debit.getEntryType()).isEqualTo("debit");
        assertThat(debit.getAccountId()).isEqualTo(cash.getId());
        assertThat(debit.getAmount()).isEqualByComparingTo("50.00");
        assertThat(credit.getEntryType()).isEqualTo("credit");
        assertThat(credit.getAccountId()).isEqualTo(revenue.getId());
        assertThat(credit.getAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void aLabOrderPaymentUsesTheLabOrderPaymentSourceType() {
        UUID tenantId = UUID.randomUUID();
        stubAccounts(tenantId, account(tenantId, "1000", "asset"), account(tenantId, "4000", "revenue"));

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setTenantId(tenantId);
        payment.setLabOrderId(UUID.randomUUID());
        payment.setAmount(new BigDecimal("30.00"));
        payment.setMethod("cash");

        service.postForPayment(payment);

        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getSourceType()).isEqualTo("lab_order_payment");
    }

    @Test
    void aDispensePaymentUsesTheDispensePaymentSourceType() {
        UUID tenantId = UUID.randomUUID();
        stubAccounts(tenantId, account(tenantId, "1000", "asset"), account(tenantId, "4000", "revenue"));

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setTenantId(tenantId);
        payment.setDispenseRecordId(UUID.randomUUID());
        payment.setAmount(new BigDecimal("15.00"));
        payment.setMethod("card");

        service.postForPayment(payment);

        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getSourceType()).isEqualTo("dispense_payment");
    }

    @Test
    void postingARefundDebitsRefundsAndAllowancesAndCreditsCash() {
        UUID tenantId = UUID.randomUUID();
        Account cash = account(tenantId, "1000", "asset");
        Account refunds = account(tenantId, "4900", "revenue");
        when(accountRepository.findByTenantIdAndCode(tenantId, "1000")).thenReturn(Optional.of(cash));
        when(accountRepository.findByTenantIdAndCode(tenantId, "4900")).thenReturn(Optional.of(refunds));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry entry = invocation.getArgument(0);
            entry.setId(UUID.randomUUID());
            return entry;
        });

        Refund refund = new Refund();
        refund.setId(UUID.randomUUID());
        refund.setTenantId(tenantId);
        refund.setAmount(new BigDecimal("20.00"));
        refund.setRefundedBy(UUID.randomUUID());

        service.postForRefund(refund);

        ArgumentCaptor<JournalEntry> entryCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getSourceType()).isEqualTo("refund");
        assertThat(entryCaptor.getValue().getSourceId()).isEqualTo(refund.getId());

        ArgumentCaptor<JournalLine> lineCaptor = ArgumentCaptor.forClass(JournalLine.class);
        verify(journalLineRepository, times(2)).save(lineCaptor.capture());
        assertThat(lineCaptor.getAllValues().get(0).getAccountId()).isEqualTo(refunds.getId());
        assertThat(lineCaptor.getAllValues().get(0).getEntryType()).isEqualTo("debit");
        assertThat(lineCaptor.getAllValues().get(1).getAccountId()).isEqualTo(cash.getId());
        assertThat(lineCaptor.getAllValues().get(1).getEntryType()).isEqualTo("credit");
    }

    @Test
    void aMissingSeededAccountThrowsRatherThanPostingAnUnbalancedEntry() {
        UUID tenantId = UUID.randomUUID();
        when(accountRepository.findByTenantIdAndCode(tenantId, "1000")).thenReturn(Optional.empty());

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setTenantId(tenantId);
        payment.setAppointmentId(UUID.randomUUID());
        payment.setAmount(new BigDecimal("10.00"));

        assertThatThrownBy(() -> service.postForPayment(payment)).isInstanceOf(IllegalStateException.class);
        verify(journalEntryRepository, times(0)).save(any());
    }
}
