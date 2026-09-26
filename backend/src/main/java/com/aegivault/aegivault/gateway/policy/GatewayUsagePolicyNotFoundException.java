package com.aegivault.aegivault.gateway.policy;

/**
 * Thrown when no policy with the id exists for the calling owner. Missing
 * ids and other owners' ids produce this same exception with this same
 * message, so callers cannot probe for another owner's policies or learn
 * whether a given id exists somewhere else.
 */
public class GatewayUsagePolicyNotFoundException extends RuntimeException {

    public GatewayUsagePolicyNotFoundException() {
        super("Usage policy not found.");
    }
}