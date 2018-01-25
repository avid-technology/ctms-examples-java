package com.avid.ctms.examples.fastprintfolderstructure;

import java.net.*;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

import com.avid.ctms.examples.tools.common.ItemInfo;
import com.avid.ctms.examples.tools.common.PlatformTools;
import net.sf.json.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-06-14
 * Time: 08:54
 * Project: CTMS
 */

/**
 * This example traverses the structure of the folder tree (location structure) with embedded resources and prints the
 * results to stdout.
 */
public class FastPrintFolderStructure {
    private static final Logger LOG = Logger.getLogger(FastPrintFolderStructure.class.getName());

    private FastPrintFolderStructure() {
    }

    /**
     * Traverses the structure of the folder tree (location structure) with embedded resources and collects the results
     * in the passed list.
     *
     * @param rootItem the URL to start traversal from
     * @param results  the list, in which the results of traversal will be collected !!will be modified!!
     * @param depth    the depth of the traversal
     */
    private static void traverse(ItemInfo rootItem, List<ItemInfo> results, int depth) throws Exception {
        final Collection<ItemInfo> children = new ArrayList<>();
        final URL itemURL = rootItem.href; //new URL(rootItem.href.replace(" ", "%20"));

        final HttpURLConnection getItemConnection = (HttpURLConnection) itemURL.openConnection();
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
                            if (200 == itemNextPageStatus) {
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
                    traverse(item, results, depth + 1);
                }
            }

            for (final ItemInfo item : children) {
                if (!item.hasChildren) {
                    results.add(item);
                }
            }
        } else {
            final String message = PlatformTools.getContent(getItemConnection);
            LOG.log(Level.INFO, "Get item failed for item <{0}>. -> {1}", new Object[] {itemURL, message});
        }
    }

    public static void main(String[] args) throws Exception {
        if (6 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <servicetype> <serviceversion> <realm> <username> <password>", FastPrintFolderStructure.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String serviceType = args[1];
            final String serviceVersion = args[2];
            final String realm = args[3];
            final String username = args[4];
            final String password = args[5];

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


                        // !!
                        // The MAM Connectivity Toolkit Connector does always embed all direct items of a folder. For other
                        // service types, the query parameter embed=asset must be added if necessary.
                        // E.g. resulting in => https://$apiDomain/apis/$serviceType;version=0;realm=$realm/locations/folders?embed=asset
                        // !!

                        final ItemInfo rootItem = new ItemInfo(null, null, 0, new URL(urlRootItem/*+"/25"?filter=item-type-folder"*/), true);


                        final List<ItemInfo> results = new ArrayList<>();
                        /// Traverse the folder tree and collect the results in the passed list:
                        final long then = System.currentTimeMillis();
                        traverse(rootItem, results, 0);
                        final StringBuilder sb = new StringBuilder();
                        try (final Formatter formatter = new Formatter(sb)) {
                            for (final ItemInfo item : results) {
                                formatter.format("%s <%s>%n", item, item.href);
                            }
                        }
                        final long took = System.currentTimeMillis() - then;
                        LOG.log(Level.INFO, "{0}elapsed: {1}", new Object[] {sb, took});
                    } else {
                        LOG.log(Level.INFO, "Resource <{0}> not found. -> {1}", new Object[] {locationsURL, PlatformTools.getContent(getLocationsConnection)});
                    }
                } catch (final Exception exception) {
                    LOG.log(Level.SEVERE, "failure", exception);
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
