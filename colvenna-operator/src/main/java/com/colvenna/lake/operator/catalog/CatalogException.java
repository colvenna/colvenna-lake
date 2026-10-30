package com.colvenna.lake.operator.catalog;

/** A colvenna-catalog call failed; {@link #status()} is the HTTP status, or 0 if none arrived. */
public class CatalogException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public CatalogException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public CatalogException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
