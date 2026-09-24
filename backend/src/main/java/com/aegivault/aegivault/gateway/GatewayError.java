package com.aegivault.aegivault.gateway;

/** Minimal safe error body for gateway endpoint failures. */
public record GatewayError(String message) {}
