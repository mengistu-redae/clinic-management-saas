package com.clinicops.vitals;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test for Vitals.getBmi()'s derived-field logic, same "isolate calculation logic" convention as FeeCalculatorTest. */
class VitalsTest {

    @Test
    void bmiIsComputedFromHeightAndWeight() {
        Vitals vitals = new Vitals();
        vitals.setHeightCm(new BigDecimal("170.0"));
        vitals.setWeightKg(new BigDecimal("70.0"));

        assertThat(vitals.getBmi()).isEqualByComparingTo("24.2");
    }

    @Test
    void bmiIsNullWhenHeightIsMissing() {
        Vitals vitals = new Vitals();
        vitals.setWeightKg(new BigDecimal("70.0"));

        assertThat(vitals.getBmi()).isNull();
    }

    @Test
    void bmiIsNullWhenWeightIsMissing() {
        Vitals vitals = new Vitals();
        vitals.setHeightCm(new BigDecimal("170.0"));

        assertThat(vitals.getBmi()).isNull();
    }

    @Test
    void bmiIsNullRatherThanDivideByZeroWhenHeightIsZero() {
        Vitals vitals = new Vitals();
        vitals.setHeightCm(BigDecimal.ZERO);
        vitals.setWeightKg(new BigDecimal("70.0"));

        assertThat(vitals.getBmi()).isNull();
    }
}
