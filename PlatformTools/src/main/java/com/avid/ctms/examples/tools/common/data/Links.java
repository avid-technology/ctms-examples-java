package com.avid.ctms.examples.tools.common.data;

import com.avid.ctms.examples.tools.common.data.embedded.Embedded;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Links {

    private LinksData links;
    private Embedded embedded;

    @JsonCreator
    public Links(@JsonProperty("_links") LinksData links,
                 @JsonProperty("_embedded") Embedded embedded) {
        this.links = links;
        this.embedded = embedded;
    }

    public LinksData getLinks() {
        return links;
    }

    public Embedded getEmbedded() {
        return embedded;
    }
}
