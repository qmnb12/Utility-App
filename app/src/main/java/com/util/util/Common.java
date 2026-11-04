package com.util.util;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Random placeholder helpers that work on every supported Android API level. */
public final class Common {
    private static final String ALLOWED_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PLACEHOLDER_LENGTH = 50;

    private Common() { }

    public static String getRandomDigits(int digits) {
        validateLength(digits);
        StringBuilder result = new StringBuilder(digits);
        if (digits == 0) return "";
        result.append((char) ('1' + RANDOM.nextInt(9)));
        for (int i = 1; i < digits; i++) {
            result.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return result.toString();
    }

    public static String getRandomString(int length) {
        validateLength(length);
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(ALLOWED_CHARS.charAt(RANDOM.nextInt(ALLOWED_CHARS.length())));
        }
        return result.toString();
    }

    public static String replacePlaceholderWithNumberStr(
            String original, String searchString, int digits) {
        return replace(original, searchString, digits, true);
    }

    public static String replacePlaceholderWithCharStr(
            String original, String searchString, int length) {
        return replace(original, searchString, length, false);
    }

    private static String replace(String original, String token, int length, boolean numeric) {
        if (original == null || token == null || token.isEmpty()) return original;
        validateLength(length);
        Matcher matcher = Pattern.compile(Pattern.quote(token)).matcher(original);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = numeric ? getRandomDigits(length) : getRandomString(length);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static void validateLength(int length) {
        if (length < 1 || length > MAX_PLACEHOLDER_LENGTH) {
            throw new IllegalArgumentException(
                    "Placeholder length must be between 1 and " + MAX_PLACEHOLDER_LENGTH + ".");
        }
    }
}
