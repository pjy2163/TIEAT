package com.tieat.identity.application;

public final class PasswordReauthenticationRequiredException extends RuntimeException {

    public PasswordReauthenticationRequiredException() {
        super("Password reauthentication is required");
    }
}
