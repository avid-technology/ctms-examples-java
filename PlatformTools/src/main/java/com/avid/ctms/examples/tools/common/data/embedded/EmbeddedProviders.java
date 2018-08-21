package com.avid.ctms.examples.tools.common.data.embedded;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddedProviders {

    private EmbeddedProvider embeddedProvider;
    private String kind;

    @JsonCreator
    public EmbeddedProviders(@JsonProperty("_links") EmbeddedProvider embeddedProvider,
                             @JsonProperty("kind") String kind) {
        this.embeddedProvider = embeddedProvider;
        this.kind = kind;
    }

    public EmbeddedProvider getEmbeddedProvider() {
        return embeddedProvider;
    }

    public String getKind() {
        return kind;
    }
}
