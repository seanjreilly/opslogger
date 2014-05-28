package com.equalexperts.logging;

/**
 * A processor that turns a throwable (and stack trace) into a representation
 * (normally single-line) suitable for a log file.
 */
interface StackTraceProcessor {
    String process(Throwable throwable);
}