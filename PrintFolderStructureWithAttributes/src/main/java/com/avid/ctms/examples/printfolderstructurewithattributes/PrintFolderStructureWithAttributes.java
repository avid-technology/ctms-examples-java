package com.avid.ctms.examples.printfolderstructurewithattributes;

import com.avid.ctms.examples.tools.common.ItemInfo;
import com.avid.ctms.examples.tools.common.PlatformTools;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-11-22
 * Time: 07:36
 * Project: CTMS
 */

/**
 * This example shows how to get attributes from folders of a folder structure.
 */
public class PrintFolderStructureWithAttributes {
    private static final Logger LOG = Logger.getLogger(PrintFolderStructureWithAttributes.class.getName());

    private PrintFolderStructureWithAttributes() {
    }

    /**
     * Traverses the structure of the folder tree (location structure) with embedded resources and collects the results
     * in the passed list including the values of extra attributes if any.
     *
     * @param rootItem        the URL to start traversal from
     * @param results         the list, in which the results of traversal will be collected !!will be modified!!
     * @param depth           the depth of the traversal
     * @param extraAttributes a comma separated list of attributes to query for each item additionally
     */
    private static void traverse(ItemInfo rootItem, List<ItemInfo> results, int depth, String extraAttributes) throws Exception {
        Objects.requireNonNull(extraAttributes);

        final Collection<ItemInfo> children = new ArrayList<>();

//        final URL itemURL
//                = UriBuilder
//                    .fromUri(rootItem.href.replace(" ", "%20"))
//                    .queryParam("attributes", extraAttributes)
//                    .build()
//                    .toURL();

        final HttpURLConnection getItemConnection = (HttpURLConnection) rootItem.href.openConnection();
        getItemConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
        getItemConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
        getItemConnection.setRequestProperty("Accept", "application/hal+json");

        final int itemStatus = getItemConnection.getResponseCode();
        if (HttpURLConnection.HTTP_OK == itemStatus) {
            final String rawItemPageResults = PlatformTools.getContent(getItemConnection);
            final JSONObject itemResult = JSONObject.fromObject(rawItemPageResults);
            results.add(new ItemInfo(itemResult, depth));

            final JSONObject embedded = (JSONObject) itemResult.get("_embedded");
            JSONObject collection = null;
            if (null != embedded) {
                collection = (JSONObject) embedded.get("loc:collection");
            }
            // The item to traverse is a folder:
            if (null != collection) {
                // Get the items of the folder pagewise:
                JSONObject embeddedItems = (JSONObject) collection.get("_embedded");
                if (null != embeddedItems) {
                    do {
                        final Object itemsObject = embeddedItems.get("loc:item");
                        if (null != itemsObject) {
                            if (itemsObject instanceof JSONArray) {
                                final JSONArray items = (JSONArray) itemsObject;

                                final Collection<ItemInfo> itemPage = new ArrayList<>(items.size());
                                for (final Object item : items) {
                                    final JSONObject folderItem = (JSONObject) item;
                                    itemPage.add(new ItemInfo(folderItem, depth + 1));
                                }
                                children.addAll(itemPage);
                            } else {
                                children.add(new ItemInfo((JSONObject) itemsObject, depth + 1));
                            }
                        }

                        final JSONObject linkToNextPage = (JSONObject) collection.getJSONObject("_links").get("next");
                        if (null != linkToNextPage) {
                            final HttpURLConnection itemNextPageConnection = (HttpURLConnection) new URL(linkToNextPage.getString("href").replace(" ", "%20")).openConnection();
                            itemNextPageConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                            itemNextPageConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                            itemNextPageConnection.setRequestProperty("Accept", "application/hal+json");

                            final int itemNextPageStatus = itemNextPageConnection.getResponseCode();
                            if (HttpURLConnection.HTTP_OK == itemNextPageStatus) {
                                final String rawNextItemPageResults = PlatformTools.getContent(itemNextPageConnection);
                                collection = JSONObject.fromObject(rawNextItemPageResults);
                                embeddedItems = (JSONObject) collection.get("_embedded");
                            } else {
                                collection = null;
                            }
                        } else {
                            collection = null;
                        }
                    } while (null != collection && null != embeddedItems);
                }
            }

            for (final ItemInfo item : children) {
                if (item.hasChildren) {
                    traverse(item, results, depth + 1, extraAttributes);
                }
            }

            for (final ItemInfo item : children) {
                if (!item.hasChildren) {
                    results.add(item);
                }
            }
        } else {
            LOG.log(Level.INFO, "Get item failed for item <{0}>. -> {1}", new Object[] {rootItem.href, PlatformTools.getContent(getItemConnection)});
        }
    }

    public static Map<String, Object> toMap(JSONObject object) throws JSONException {
        final Map<String, Object> map = new HashMap<>(object.size());

        final Iterator keysItr = object.keys();
        while (keysItr.hasNext()) {
            final String key = (String) keysItr.next();
            Object value = object.get(key);

            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }

    public static List<Object> toList(JSONArray array) throws JSONException {
        final List<Object> list = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); ++i) {
            Object value = array.get(i);
            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }

    private static Map<String, Object> getAttributeMappings(String apiDomain, String serviceType, String realm, String... attributeKeys) throws IOException {
        final URL attributeMappingsURL
                = UriBuilder
                .fromUri(String.format("https://%s/apis/%s;version=0;realm=%s/metadata/mappings", apiDomain, serviceType, realm))
                .queryParam("attributes", String.join(",", Arrays.asList(attributeKeys)))
                .build()
                .toURL();


        //final URL attributeMappingsURL = new URL(String.format("https://%s/apis/%s;version=0;realm=%s/metadata/mappings&at", apiDomain, serviceType, realm));
        final HttpURLConnection getAttributeMappingsConnection = (HttpURLConnection) attributeMappingsURL.openConnection();
        getAttributeMappingsConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
        getAttributeMappingsConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
        getAttributeMappingsConnection.setRequestProperty("Accept", "application/hal+json");

        final int attributeMappingsStatus = getAttributeMappingsConnection.getResponseCode();
        if (HttpURLConnection.HTTP_OK == attributeMappingsStatus) {
            /// Get the mappings:
            final String rawAttributeMappingsResults = PlatformTools.getContent(getAttributeMappingsConnection);
            final JSONObject attributeMappingsResults = JSONObject.fromObject(rawAttributeMappingsResults);

            return toMap(attributeMappingsResults);
        }

        return Collections.emptyMap();
    }

    public static void main(String[] args) throws Exception {
        if (6 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <servicetype> <serviceversion> <realm> <username> <password>", PrintFolderStructureWithAttributes.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String serviceType = args[1];
            final String serviceVersion = args[2];
            final String realm = args[3];
            final String username = args[4];
            final String password = args[5];

            final String[] extraAttributeKeys = {"CREATION_DATETIME", "COMMENT", "RIGHTS_INDICATOR", "piffpaff", "escape\\,me"};

            final boolean successfullyAuthorized = PlatformTools.authorize(apiDomain, username, password);
            if (successfullyAuthorized) {
                try {
                    /// Query CTMS Registry:
                    final String registryServiceVersion = "0";
                    final String defaultLocationsUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/locations", apiDomain, serviceType, serviceVersion, realm);
                    final List<String> locationsUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "loc:locations", defaultLocationsUriTemplate);

                    /// Check presence of the locations resource and continue with HATEOAS:
                    final URL locationsURL = new URL(locationsUriTemplates.get(0));
                    final HttpURLConnection getLocationsConnection = (HttpURLConnection) locationsURL.openConnection();
                    getLocationsConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                    getLocationsConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                    getLocationsConnection.setRequestProperty("Accept", "application/hal+json");

                    final int locationsStatus = getLocationsConnection.getResponseCode();
                    if (HttpURLConnection.HTTP_OK == locationsStatus) {
                        /// Get the root folder item:
                        final String rawLocationsResults = PlatformTools.getContent(getLocationsConnection);
                        final JSONObject locationsResults = JSONObject.fromObject(rawLocationsResults);
                        final String urlRootItem = locationsResults.getJSONObject("_links").getJSONObject("loc:root-item").getString("href");

                        /// Resolve the real attribute names:
                        final Map<String, Object> attributeMappings = getAttributeMappings(apiDomain, serviceType, realm, extraAttributeKeys);
                        final List<CharSequence> extraAttributesArguments = new ArrayList<>(attributeMappings.size());
                        for (final Map.Entry<String, Object> item : attributeMappings.entrySet()) {
                            final String effectiveValue = item.getValue().toString().replace(",", "\\,");
                            extraAttributesArguments.add(effectiveValue);
                        }
                        final String extraAttributes = String.join(",", extraAttributesArguments);


                        // !!
                        // The MAM Connectivity Toolkit Connector does always embed all direct items of a folder. For other
                        // service types, the query parameter embed=asset must be added if necessary.
                        // E.g. resulting in => https://$apiDomain/apis/$serviceType;version=0;realm=$realm/locations/folders?embed=asset
                        // !!
                        final URL itemURL
                                = UriBuilder
                                .fromUri(urlRootItem/*.replace(" ", "%20")*/)
                                .queryParam("attributes", extraAttributes)
                                .build()
                                .toURL();

                        final ItemInfo rootItem = new ItemInfo(null, null, 0, itemURL, true);

                        final List<ItemInfo> results = new ArrayList<>();
                        /// Traverse the folder tree and collect the results in the passed list:
                        final long then = System.currentTimeMillis();
                        traverse(rootItem, results, 0, extraAttributes);
                        final StringBuilder sb = new StringBuilder();
                        try (final Formatter formatter = new Formatter(sb)){
                            for (final ItemInfo item : results) {
                                formatter.format(
                                        "%s, Comment: \"%s\", RightsIndicator: \"%s\", piffpaff: \"%s\", escape\\,me \"%s\"%n"
                                        , item
                                        , item.getAttribute("comment")
                                        , item.getAttribute("rights_indicator")
                                        , item.getAttribute("piffpaff")
                                        , item.getAttribute("escape\\,me"));
                            }

                            final long took = System.currentTimeMillis() - then;
                            LOG.log(Level.INFO, "{0}elapsed: {1}", new Object[] {sb, took});
                        }
                    } else {
                        LOG.log(Level.INFO, "Resource <{0}> not found. -> {1}", new Object[] {locationsURL, PlatformTools.getContent(getLocationsConnection)});
                    }
                } catch (final Throwable throwable) {
                    LOG.log(Level.SEVERE, "failure", throwable);
                } finally {
                    PlatformTools.logout(apiDomain);
                }
            } else {
                LOG.log(Level.INFO, "Authorization failed.");
            }

            LOG.log(Level.INFO, "End");
        }
    }
}
