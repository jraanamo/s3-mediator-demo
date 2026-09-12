package com.restolution.arch26demo.client.selfcheck;

import java.util.Objects;

public final class Check {
    private Check() {
    }

    public static void that(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
