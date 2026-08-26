package com.tieat.identity.application;

public final class SessionReauthenticationFailedException extends RuntimeException {

    public SessionReauthenticationFailedException() {
        super("Reauthentication failed");
    }
}
