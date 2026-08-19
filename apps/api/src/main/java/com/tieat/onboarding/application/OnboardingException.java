package com.tieat.onboarding.application;

public final class OnboardingException extends RuntimeException {

    public enum Reason {
        INVITE_INVALID,
        VALIDATION_FAILED,
        LOGIN_ID_ALREADY_IN_USE,
        CATALOG_ENTRY_NOT_FOUND
    }

    private final Reason reason;

    private OnboardingException(Reason reason) {
        this.reason = reason;
    }

    public static OnboardingException inviteInvalid() {
        return new OnboardingException(Reason.INVITE_INVALID);
    }

    public static OnboardingException validationFailed() {
        return new OnboardingException(Reason.VALIDATION_FAILED);
    }

    public static OnboardingException loginIdAlreadyInUse() {
        return new OnboardingException(Reason.LOGIN_ID_ALREADY_IN_USE);
    }

    public static OnboardingException catalogEntryNotFound() {
        return new OnboardingException(Reason.CATALOG_ENTRY_NOT_FOUND);
    }

    public Reason reason() {
        return reason;
    }
}
