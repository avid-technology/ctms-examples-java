package com.avid.ctms.examples.advancedsearch;

import com.avid.ctms.examples.tools.common.PlatformTools;
import com.damnhandy.uri.template.UriTemplate;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-07-12
 * Time: 10:37
 * Project: CTMS
 */

/**
 * This example issues an advanced search for assets, shows pagewise request of search results and prints the results to stdout.
 */
public class AdvancedSearch {
    private static final Logger LOG = Logger.getLogger(AdvancedSearch.class.getName());

    private AdvancedSearch() {
    }

    private static String removeUTF8BOM(String s) {
        return s.startsWith("\uFEFF")
                ? s.substring(1)
                : s;
    }

    public static void main(String[] args) throws Exception {
        if (8 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <oauthtoken> <servicetype> <serviceversion> <realm> <username> <password> <advancedsearchdescriptionfilename>", AdvancedSearch.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String baseOAuthToken = args[1];
            final String serviceType = args[2];
            final String serviceVersion = args[3];
            final String realm = args[4];
            final String username = args[5];
            final String password = args[6];
            final Path advancedSearchDescriptionFilePath = Paths.get(args[7]);

            if (advancedSearchDescriptionFilePath.toFile().exists()) {
                final boolean successfullyAuthorized = PlatformTools.authorize(apiDomain, baseOAuthToken, username, password);
                if (successfullyAuthorized) {
                    try {
                        /// Query CTMS Registry:
                        final String registryServiceVersion = "0";
                        final String defaultAdvancedSearchUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/searches", apiDomain, serviceType, serviceVersion, realm);
                        final List<String> advancedSearchUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "search:searches", defaultAdvancedSearchUriTemplate);

                        /// Check, whether advanced search is supported:
                        URL searchesResourceURL = new URL(advancedSearchUriTemplates.get(0));
                        final HttpURLConnection searchesResourceConnection = (HttpURLConnection) searchesResourceURL.openConnection();
                        searchesResourceConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                        searchesResourceConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                        searchesResourceConnection.setRequestProperty("Accept", "application/hal+json");

                        final int searchesStatus = searchesResourceConnection.getResponseCode();
                        if (HttpURLConnection.HTTP_OK == searchesStatus) {
                            final String rawSearchesResult = PlatformTools.getContent(searchesResourceConnection);
                            final JSONObject searchesResult = JSONObject.fromObject(rawSearchesResult);
                            final Object advancedSearchLinkObject = searchesResult.getJSONObject("_links").get("search:advanced-search");
                            // Is advanced search supported?
                            if (null != advancedSearchLinkObject) {
                                /// Doing the advanced search and write the results to stdout:
                                final String searchURLTemplate = ((JSONObject) advancedSearchLinkObject).getString("href");
                                final UriTemplate searchURITemplate = UriTemplate.fromTemplate(searchURLTemplate);
                                final URL advancedSearchResultPageURL = new URL(searchURITemplate.expand());

                                // Create and send the process query's description:
                                final String advancedSearchDescription = removeUTF8BOM(new String(Files.readAllBytes(advancedSearchDescriptionFilePath), Charset.forName("UTF-8")));
                                HttpURLConnection advancedSearchPageConnection = (HttpURLConnection) advancedSearchResultPageURL.openConnection();
                                advancedSearchPageConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                                advancedSearchPageConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                                advancedSearchPageConnection.setRequestMethod("POST");
                                advancedSearchPageConnection.setDoOutput(true);
                                advancedSearchPageConnection.setRequestProperty("Content-Type", "application/json");
                                advancedSearchPageConnection.setRequestProperty("Accept", "application/hal+json");
                                advancedSearchPageConnection.getOutputStream().write(advancedSearchDescription.getBytes());

                                final int advancedSearchStatus = advancedSearchPageConnection.getResponseCode();
                                if (HttpURLConnection.HTTP_OK == advancedSearchStatus) {
                                    int assetNo = 0;
                                    int pageNo = 0;
                                    // Page through the result:
                                    final StringBuilder sb = new StringBuilder();
                                    try (final Formatter formatter = new Formatter(sb)) {
                                        do {
                                            final String rawProcessQueryPageResult = PlatformTools.getContent(advancedSearchPageConnection);
                                            final JSONObject processQueryPageResult = JSONObject.fromObject(rawProcessQueryPageResult);
                                            final JSONObject embeddedResults = (JSONObject) processQueryPageResult.get("_embedded");
                                            // Do we have results:
                                            // If we have more results, follow the next link and get the next page:
                                            final JSONObject linkToNextPage = (JSONObject) processQueryPageResult.getJSONObject("_links").get("next");
                                            // Do we have results:
                                            if (null != embeddedResults) {
                                                final JSONArray foundAssets = embeddedResults.getJSONArray("aa:asset");

                                                if (!foundAssets.isEmpty()) {
                                                    formatter.format("Page#: %d, search description from file '%s'%n", ++pageNo, advancedSearchDescriptionFilePath);
                                                    for (final Object item : foundAssets) {
                                                        final JSONObject asset = (JSONObject) item;
                                                        final String id = asset.getJSONObject("base").getString("id");
                                                        final String name = null != asset.getJSONObject("common").get("name") ? asset.getJSONObject("common").getString("name") : "";

                                                        formatter.format("\tAsset#: %d, id: %s, name: '%s'%n", ++assetNo, id, name);
                                                    }
                                                }
                                            } else {
                                                LOG.log(Level.INFO, "No results found for search description from file {0}.", advancedSearchDescriptionFilePath);
                                            }

                                            if (null != linkToNextPage) {
                                                advancedSearchPageConnection = (HttpURLConnection) new URL(linkToNextPage.getString("href")).openConnection();
                                                advancedSearchPageConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                                                advancedSearchPageConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                                            } else {
                                                advancedSearchPageConnection = null;
                                            }
                                        } while (null != advancedSearchPageConnection);
                                    }

                                    LOG.log(Level.INFO, sb::toString);
                                }
                            }
                        } else {
                            LOG.log(Level.INFO, "Failure accessing service. -> {0}", PlatformTools.getContent(searchesResourceConnection));
                        }
                    } catch (final Exception exception) {
                        LOG.log(Level.SEVERE, "failure", exception);
                    } finally {
                        PlatformTools.logout(apiDomain);
                    }
                } else {
                    LOG.log(Level.INFO, "Authorization failed.");
                }
            } else {
                LOG.log(Level.INFO, "File {0} not found.", advancedSearchDescriptionFilePath);
            }

            LOG.log(Level.INFO, "End");
        }
    }
}
