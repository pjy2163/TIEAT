package com.tieat.identity.application;

public final class SessionReauthenticationInvalidException extends RuntimeException {

    public SessionReauthenticationInvalidException() {
        super("Reauthentication input is invalid");
    }
}
