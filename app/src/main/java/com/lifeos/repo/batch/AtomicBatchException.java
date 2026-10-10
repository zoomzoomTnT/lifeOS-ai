package com.lifeos.repo.batch;

import org.springframework.dao.NonTransientDataAccessException;

/** A step of an {@link AtomicSqlBatch} failed (or the outcome is unknown); see the message. */
public class AtomicBatchException extends NonTransientDataAccessException {

    public AtomicBatchException(String msg) {
        super(msg);
    }

    public AtomicBatchException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
