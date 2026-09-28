package com.clinicops.finance;

import java.math.BigDecimal;
import java.util.UUID;

public record ProfitAndLossLine(UUID accountId, String code, String name, BigDecimal amount) {
}
