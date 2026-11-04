package com.util.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class CommonTest {
    @Test
    public void generatesRequestedNumberOfDigits() {
        String value = Common.getRandomDigits(12);
        assertEquals(12, value.length());
        assertTrue(value.matches("[1-9][0-9]{11}"));
    }

    @Test
    public void replacesLiteralDigitPlaceholder() {
        String value = Common.replacePlaceholderWithNumberStr(
                "OTP: #random_digit4#", "#random_digit4#", 4);
        assertTrue(value.matches("OTP: [1-9][0-9]{3}"));
    }

    @Test
    public void replacesLiteralStringPlaceholder() {
        String value = Common.replacePlaceholderWithCharStr(
                "Code: #random_string6#", "#random_string6#", 6);
        assertTrue(value.matches("Code: [A-Za-z]{6}"));
    }

    @Test
    public void leavesUnmatchedMessageUnchanged() {
        assertEquals("plain text",
                Common.replacePlaceholderWithNumberStr("plain text", "#random_digit4#", 4));
    }
}
