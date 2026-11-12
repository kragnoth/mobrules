package com.kragnoth.mobrules.rule;

/** A problem in a rule file, reported with the JSON path where it happened. */
public final class RuleException extends RuntimeException {
    public RuleException(String path, String message) {
        super(path + ": " + message);
    }
}
