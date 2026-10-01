package com.templeregistry.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates cryptographically random temporary passwords for the Super Admin
 * "reset user password" action.
 *
 * <p>Output is 12 characters drawn from mixed case letters and digits, guaranteed to contain at
 * least one of each class so it always satisfies the application password policy (8–128 chars).
 * Visually ambiguous characters (0/O, 1/l/I) are excluded because the password is transcribed
 * from an email by hand.
 */
public final class TemporaryPasswordGenerator {

    private static final String UPPER  = "ABCDEFGHJKLMNPQRSTUVWXYZ";  // no I, O
    private static final String LOWER  = "abcdefghijkmnpqrstuvwxyz";  // no l, o
    private static final String DIGITS = "23456789";                  // no 0, 1
    private static final String ALL    = UPPER + LOWER + DIGITS;

    private static final int LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswordGenerator() {
    }

    public static String generate() {
        List<Character> chars = new ArrayList<>(LENGTH);
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGITS));
        while (chars.size() < LENGTH) {
            chars.add(pick(ALL));
        }
        // Fisher–Yates with a CSPRNG so the guaranteed classes are not always in positions 0-2.
        for (int i = chars.size() - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars.get(i);
            chars.set(i, chars.get(j));
            chars.set(j, tmp);
        }
        StringBuilder sb = new StringBuilder(LENGTH);
        chars.forEach(sb::append);
        return sb.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }
}
