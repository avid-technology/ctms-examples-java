package com.avid.ctms.examples.fastprintfolderstructure;

import java.net.*;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

import com.avid.ctms.examples.tools.common.AuthorizationResponse;
import com.avid.ctms.examples.tools.common.ItemInfo;
import com.avid.ctms.examples.tools.common.PlatformTools;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.json.*;


/**
 * Copyright 2016-2021 by Avid Technology, Inc.
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



        final HttpResponse<String> response = Unirest.get(itemURL.toString()).asString();

        final int itemStatus = response.getStatus();
        if (HttpURLConnection.HTTP_OK == itemStatus) {
            final String rawItemPageResults = response.getBody();
            final JSONObject itemResult = new JSONObject(rawItemPageResults);
            final ItemInfo newItem = new ItemInfo(itemResult, depth);
            results.add(newItem);
            /**/ System.out.printf("%s <%s>%n", newItem, newItem.href);


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

                                final Collection<ItemInfo> itemPage = new ArrayList<>(items.length());
                                for (final Object item : items) {
                                    final JSONObject folderItem = (JSONObject) item;
                                    itemPage.add(new ItemInfo(folderItem, depth + 1));
                                }
                                children.addAll(itemPage);

//                                if (itemPage.stream().anyMatch(it -> null != it.id && it.id.contains("1130.11354"))) {
//                                    break;
//                                }
                                if (itemPage.stream().anyMatch(it -> null != it.name && it.name.contains("Draft"))) {
                                    break;
                                }
                            } else {
                                children.add(new ItemInfo((JSONObject) itemsObject, depth + 1));
                            }
                        }

                        final JSONObject linkToNextPage = (JSONObject) collection.getJSONObject("_links").opt("next");
                        if (null != linkToNextPage) {

                            final HttpResponse<String> page = Unirest.get(linkToNextPage.getString("href").replace(" ", "%20")).asString();
                            final int itemNextPageStatus = page.getStatus();
                            if (HttpURLConnection.HTTP_OK == itemNextPageStatus) {
                                final String rawNextItemPageResults = page.getBody();
                                collection = new JSONObject(rawNextItemPageResults);
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

            //for (final ItemInfo item : children.stream().filter(it -> null != it.name && it.name.contains("Personal")).toArray(ItemInfo[]::new)) {
            for (final ItemInfo item : children) {
                if (item.hasChildren) {
                    traverse(item, results, depth + 1);
                }
            }

            for (final ItemInfo item : children) {
                if (!item.hasChildren) {
                    results.add(item);
                    /**/ System.out.printf("%s <%s>%n", item, item.href);
                }
            }
        } else {
            final String message = response.getStatusText();
            LOG.log(Level.INFO, "Get item failed for item <{0}>. -> {1}", new Object[] {itemURL, message});
        }
    }

    public static void main(String[] args) throws Exception {
        if (5 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <servicetype> <serviceversion> <realm>", FastPrintFolderStructure.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceType = args[2];
            final String serviceVersion = args[3];
            final String realm = args[4];

            final AuthorizationResponse authorizationResponse = PlatformTools.authorize(apiDomain, httpBasicAuthString);
            if (authorizationResponse.getLoginResponse().map(HttpResponse::isSuccess).orElse(false)) {
                try {
                    /// Query CTMS Registry:
                    final String registryServiceVersion = "0";
                    final String defaultLocationsUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/locations", apiDomain, serviceType, serviceVersion, realm);
                    final List<String> locationsUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "loc:locations", defaultLocationsUriTemplate);

                    /// Check presence of the locations resource and continue with HATEOAS:
                    final String urlLocation = locationsUriTemplates.stream().filter(it -> it.contains("7D5A08FA-5D15-4BEB-8C41-FA85406FBBEC")).findFirst().get();
                    final HttpResponse<String> response = Unirest.get(urlLocation).asString();

                    final int locationsStatus = response.getStatus();
                    if (HttpURLConnection.HTTP_OK == locationsStatus) {
                        /// Get the root folder item:
                        final String rawLocationsResults = response.getBody();
                        final JSONObject locationsResults = new JSONObject(rawLocationsResults);
                        final String urlRootItem = locationsResults.getJSONObject("_links").getJSONObject("loc:root-item").getString("href");


                        // !!
                        // The MAM Connectivity Toolkit Connector does always embed all direct items of a folder. For other
                        // service types, the query parameter embed=asset must be added if necessary.
                        // E.g. resulting in => https://$apiDomain/apis/$serviceType;version=0;realm=$realm/locations/folders?embed=asset
                        // !!

                        //https://kl-sm-ics/apis/avid.mam.assets.access;version=9999;realm=18046458-EE19-4F42-80F3-47C8A977C688/locations/items/1131?offset=0&limit=100
                        //final ItemInfo rootItem = new ItemInfo(null, null, 0, new URL(urlRootItem/*+"/25"?filter=item-type-folder"*/), true);
                        final ItemInfo rootItem = new ItemInfo(null, null, 0, new URL(urlRootItem), true);


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
                        LOG.log(Level.INFO, "Resource <{0}> not found. - {1}", new Object[] {urlLocation, response.getStatusText()});
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
