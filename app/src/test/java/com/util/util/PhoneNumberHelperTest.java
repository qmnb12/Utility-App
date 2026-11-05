package com.util.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class PhoneNumberHelperTest {
    @Test
    public void normalizesIndianNationalNumber() {
        assertEquals("+919876543210",
                PhoneNumberHelper.normalizePhoneNumber("98765 43210", "+91"));
    }

    @Test
    public void prefixesIndianTenDigitNumberEvenWhenItStartsWithCountryCodeDigits() {
        assertEquals("+919123456789",
                PhoneNumberHelper.normalizePhoneNumber("9123456789", "+91"));
    }

    @Test
    public void acceptsIndianNumberWithCountryCodeButWithoutPlus() {
        assertEquals("+919876543210",
                PhoneNumberHelper.normalizePhoneNumber("919876543210", "+91"));
    }

    @Test
    public void preservesSingaporeNumberWithCountryCodeWithoutPlus() {
        assertEquals("+6581234567",
                PhoneNumberHelper.normalizePhoneNumber("6581234567", "+65"));
    }

    @Test
    public void removesIndianTrunkZero() {
        assertEquals("+919876543210",
                PhoneNumberHelper.normalizePhoneNumber("09876543210", "+91"));
    }

    @Test
    public void preservesInternationalNumber() {
        assertEquals("+447700900123",
                PhoneNumberHelper.normalizePhoneNumber("+44 7700 900123", "+91"));
    }

    @Test
    public void rejectsNumberLongerThanE164Limit() {
        assertNull(PhoneNumberHelper.normalizePhoneNumber("+1234567890123456", "+91"));
    }

    @Test
    public void rejectsUssdStringAsPhoneNumber() {
        assertNull(PhoneNumberHelper.normalizePhoneNumber("*123#", "+91"));
    }

    @Test
    public void acceptsCommonUssdCodes() {
        assertTrue(PhoneNumberHelper.isValidUssdCode("*123#"));
        assertTrue(PhoneNumberHelper.isValidUssdCode("*#06#"));
        assertFalse(PhoneNumberHelper.isValidUssdCode("9876543210"));
    }

    @Test
    public void parsesAndDeduplicatesRecipients() {
        List<String> actual = PhoneNumberHelper.parseRecipients(
                "9876543210, +919876543210\n09876543210", "+91");
        assertEquals(1, actual.size());
        assertEquals("+919876543210", actual.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidRecipientInsteadOfSilentlySkippingIt() {
        PhoneNumberHelper.parseRecipients("9876543210, abc", "+91");
    }
}
