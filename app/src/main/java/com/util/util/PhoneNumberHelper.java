package com.util.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Normalizes user-entered telephone numbers and validates USSD strings. */
public final class PhoneNumberHelper {
    private static final int MAX_RECIPIENTS = 50;

    private PhoneNumberHelper() { }

    public static String normalizePhoneNumber(String input, String defaultCountryCode) {
        if (input == null) return null;
        String value = input.trim();
        if (value.isEmpty()) return null;
        // Do not silently turn USSD/special-character input into a phone number.
        if (!value.matches("\\+?[0-9\\s().-]+")) return null;

        value = value.replaceAll("[\\s().-]", "");
        if (value.startsWith("00")) {
            value = "+" + value.substring(2);
        }
        boolean international = value.startsWith("+");
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 4) return null;

        if (!international) {
            String countryDigits = defaultCountryCode == null
                    ? "91" : defaultCountryCode.replaceAll("[^0-9]", "");
            if (countryDigits.isEmpty()) countryDigits = "91";
            if (digits.startsWith("0")) digits = digits.substring(1);

            // Avoid prefixing the country code twice when the user entered it without '+'.
            // Indian national numbers are exactly 10 digits and can begin with
            // "91". Do not mistake those local digits for an already-entered +91 prefix.
            boolean indianTenDigitLocal = "91".equals(countryDigits) && digits.length() == 10;
            boolean alreadyPrefixed = digits.startsWith(countryDigits)
                    && digits.length() > countryDigits.length() + 7
                    && !indianTenDigitLocal;
            if (!alreadyPrefixed) digits = countryDigits + digits;
        }

        if (digits.length() < 4 || digits.length() > 15) return null;
        return "+" + digits;
    }

    public static boolean isValidUssdCode(String input) {
        if (input == null) return false;
        String code = input.trim();
        if (code.length() < 3 || code.length() > 40) return false;
        if (!code.matches("[*#][0-9*#]+#")) return false;
        return code.matches(".*[0-9].*");
    }

    public static List<String> parseRecipients(String raw, String defaultCountryCode) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter at least one phone number.");
        }
        String[] tokens = raw.split("[,;\\n\\r]+");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String token : tokens) {
            String candidate = token.trim();
            if (candidate.isEmpty()) continue;
            String normalized = normalizePhoneNumber(candidate, defaultCountryCode);
            if (normalized == null) {
                throw new IllegalArgumentException("Invalid phone number: " + candidate);
            }
            unique.add(normalized);
            if (unique.size() > MAX_RECIPIENTS) {
                throw new IllegalArgumentException("A maximum of " + MAX_RECIPIENTS + " recipients is allowed.");
            }
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one valid phone number.");
        }
        return new ArrayList<>(unique);
    }

    public static String maskNumber(String number) {
        if (number == null) return "unknown";
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) return digits;
        return "••••" + digits.substring(digits.length() - 4);
    }
}
