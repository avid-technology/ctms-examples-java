package com.avid.ctms.examples.tools.common.data.token;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Token {
    private final String accessToken;
    private final int expiresIn;
    private final String tokenType;
    private final String refreshToken;
    private final String scope;
    private final String idToken;

    @JsonCreator
    public Token(@JsonProperty("access_token") String accessToken,
                 @JsonProperty("expires_in") int expiresIn,
                 @JsonProperty("token_type") String tokenType,
                 @JsonProperty("refresh_token") String refreshToken,
                 @JsonProperty("scope") String scope,
                 @JsonProperty("id_token") String idToken) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
        this.tokenType = tokenType;
        this.refreshToken = refreshToken;
        this.scope = scope;
        this.idToken = idToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public int getExpiresIn() {
        return expiresIn;
    }

    public String getTokenType() {
        return tokenType;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getScope() {
        return scope;
    }

    public String getIdToken() {
        return idToken;
    }

    public boolean isOpenIdConnectEnabled() {
        return null != idToken;
    }

    @Override
    public String toString() {
        return String.format("accessToken = %s", this.accessToken);
    }
}
