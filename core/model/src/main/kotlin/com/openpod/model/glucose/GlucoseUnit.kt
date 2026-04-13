package com.openpod.model.glucose

/**
 * Unit of measurement for glucose display.
 *
 * Internally all glucose values are stored in mg/dL. This enum controls
 * only the display conversion applied at the UI layer.
 *
 * @property displayLabel Human-readable label shown in settings.
 */
enum class GlucoseUnit(val displayLabel: String) {
    /** Milligrams per deciliter — used in the US, Japan, and several other countries. */
    MG_DL("mg/dL"),

    /** Millimoles per liter — used in the UK, EU, Canada, and Australia. */
    MMOL_L("mmol/L"),
    ;

    companion object {
        /** Conversion factor: 1 mmol/L = 18.0182 mg/dL. */
        const val MGDL_PER_MMOLL = 18.0182

        /**
         * Convert a mg/dL value to mmol/L, rounded to one decimal place.
         *
         * @param mgDl Glucose value in mg/dL.
         * @return Glucose value in mmol/L.
         */
        fun mgDlToMmolL(mgDl: Int): Double =
            Math.round(mgDl / MGDL_PER_MMOLL * 10.0) / 10.0

        /**
         * Convert a mmol/L value to mg/dL, rounded to the nearest integer.
         *
         * @param mmolL Glucose value in mmol/L.
         * @return Glucose value in mg/dL.
         */
        fun mmolLToMgDl(mmolL: Double): Int =
            Math.round(mmolL * MGDL_PER_MMOLL).toInt()
    }
}
