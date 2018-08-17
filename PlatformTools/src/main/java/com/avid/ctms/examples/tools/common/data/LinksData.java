package com.avid.ctms.examples.tools.common.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LinksData {

    private List<LinkProperty> identityProviders;
    private List<LinkProperty> token;

    @JsonCreator
    public LinksData(@JsonProperty("auth:identity-providers") List<LinkProperty> identityProviders,
                     @JsonProperty("auth:token") List<LinkProperty> token) {
        this.identityProviders = identityProviders;
        this.token = token;
    }

    public List<LinkProperty> getIdentityProviders() {
        return identityProviders;
    }

    public List<LinkProperty> getToken() {
        return token;
    }
}
