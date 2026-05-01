package com.functorful.stripewebhook.signature;

/**
 * Thrown by {@link StripeSignatureVerifier#verify} when the request fails
 * any verification step (missing header, malformed header, stale or future
 * timestamp, signature mismatch).
 *
 * <p>The caller maps this to an HTTP 400 response with a generic detail
 * message — Tomás's veto: no signature-failure detail leakage to clients.
 */
public final class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }
}
