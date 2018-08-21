package com.avid.ctms.examples.tools.common.data.embedded;

import com.avid.ctms.examples.tools.common.data.LinkProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddedProvider {

    private List<LinkProperty> ropcLdapProvider;
    private List<LinkProperty> ropcDefaultProvider;

    @JsonCreator
    public EmbeddedProvider(@JsonProperty("auth-oauth:ropc-ldap") List<LinkProperty> ropcLdapProvider,
                            @JsonProperty("auth:ropc-default") List<LinkProperty> ropcDefaultProvider) {
        this.ropcLdapProvider = ropcLdapProvider;
        this.ropcDefaultProvider = ropcDefaultProvider;
    }

    public List<LinkProperty> getRopcLdapProvider() {
        return ropcLdapProvider;
    }

    public List<LinkProperty> getRopcDefaultProvider() {
        return ropcDefaultProvider;
    }
}
