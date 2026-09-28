package com.clinicops.finance;

import java.math.BigDecimal;
import java.util.List;

public record ProfitAndLossResponse(
        int year,
        int month,
        List<ProfitAndLossLine> revenueLines,
        List<ProfitAndLossLine> expenseLines,
        BigDecimal totalRevenue,
        BigDecimal totalExpense,
        BigDecimal netIncome
) {
}
