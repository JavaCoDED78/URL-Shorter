package com.javaded78.urlshorter.domain.service;

/**
 * Signals that a link with the requested code does not exist. Maps to HTTP 404.
 */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String code) {
        super("link not found: " + code);
    }
}
