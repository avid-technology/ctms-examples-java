/*
 * Copyright 2019-2021 by Avid Technology, Inc.
 */

package com.avid.ctms.examples.tools.common.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties
public class LinkProperty {

    private String href;
    private String name;


    @JsonCreator
    public LinkProperty(@JsonProperty("href") String href,
                        @JsonProperty("name") String name) {
        this.href = href;
        this.name = name;
    }

    public String getHref() {
        return href;
    }

    public String getName() {
        return name;
    }
}
