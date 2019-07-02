package com.avid.ctms.examples.tools.common;

import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;

import java.util.Optional;

public class AuthorizationResponse {
    private final String accessTokenHeaderFieldValue;
    private final kong.unirest.HttpResponse<kong.unirest.JsonNode> loginResponse;

    public AuthorizationResponse(String accessTokenHeaderFieldValue, HttpResponse<JsonNode> loginResponse) {
        this.accessTokenHeaderFieldValue = accessTokenHeaderFieldValue;
        this.loginResponse = loginResponse;
    }

    public Optional<String> getAccessTokenHeaderFieldValue() {
        return Optional.ofNullable(accessTokenHeaderFieldValue);
    }

    public Optional<HttpResponse<JsonNode>> getLoginResponse() {
        return Optional.ofNullable(loginResponse);
    }
}
