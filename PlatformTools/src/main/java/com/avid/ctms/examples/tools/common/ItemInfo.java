package com.avid.ctms.examples.tools.common;

import kong.unirest.json.JSONObject;

import java.net.*;
import java.util.*;

/**
 * Copyright 2016-2021 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-06-28
 * Time: 14:52
 * Project: CTMS
 */

/**
 * Represents a folder item.
 */
public class ItemInfo {
    private final Map<String, String> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public static final String UNKNOWN_ATTRIBUTE = "#UNKNOWN_ATTRIBUTE#";

    public final String name;
    public final String type;
    public final int depth;
    public final URL href;
    public final boolean hasChildren;
    public String id;


    public ItemInfo(String name, String type, int depth, URL href, boolean hasChildren) {
        this.name = name;
        this.type = type;
        this.depth = depth;
        this.href = href;
        this.hasChildren = hasChildren;

    }

    public ItemInfo(JSONObject item, int depth) throws MalformedURLException {
        this(item.getJSONObject("common").optString("name")
            , item.getJSONObject("base").getString("type")
            , depth
            , new URL(item.getJSONObject("_links").getJSONObject("self").optString("href"))
            , null != item.getJSONObject("_links").opt("loc:collection"));

        this.id = item.getJSONObject("base").getString("id");

        final Set<?> commonAttributesKeySet = item.getJSONObject("common").keySet();
        for (final Object commonAttributeKey : commonAttributesKeySet) {
            attributes.put(
                    commonAttributeKey.toString()
                    , item.getJSONObject("common").getString(commonAttributeKey.toString()));
        }

        if (item.has("attributes")) {
            for (final Object attributeElement : item.getJSONArray("attributes")) {
                final JSONObject attributeDescription = (JSONObject) attributeElement;
                attributes.put(
                        attributeDescription.getString("name")
                        , attributeDescription.getString("value"));
            }
        }
    }

    public String getAttribute(String attributeName) {
        return attributes.getOrDefault(attributeName, UNKNOWN_ATTRIBUTE);
    }

    @Override
    public String toString() {
        final char[] spacer = new char[depth];
        Arrays.fill(spacer, '\t');
        return String.format(
                "%s%sdepth: %d %s"
                , new String(spacer)
                , hasChildren ? "- (collection) " : ""
                , depth
                , name);
    }
}




