package com.avid.ctms.examples.tools.common.data.embedded;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Embedded {

    private List<EmbeddedProviders> providers;

    @JsonCreator
    public Embedded(@JsonProperty("auth:identity-provider") List<EmbeddedProviders> providers){
        this.providers = providers;
    }

    public List<EmbeddedProviders> getProviders() {
        return providers;
    }
}
