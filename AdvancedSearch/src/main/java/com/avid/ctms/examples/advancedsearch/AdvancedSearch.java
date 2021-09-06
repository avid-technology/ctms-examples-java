package com.avid.ctms.examples.advancedsearch;

import com.avid.ctms.examples.tools.common.AuthorizationResponse;
import com.avid.ctms.examples.tools.common.PlatformTools;
import com.damnhandy.uri.template.UriTemplate;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.json.*;

import javax.ws.rs.core.HttpHeaders;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

/**
 * Copyright 2016-2021 by Avid Technology, Inc.
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
        if (6 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <servicetype> <serviceversion> <realm> <advancedsearchdescriptionfilename>", AdvancedSearch.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceType = args[2];
            final String serviceVersion = args[3];
            final String realm = args[4];
            final Path advancedSearchDescriptionFilePath = Paths.get(args[5]);

            if (advancedSearchDescriptionFilePath.toFile().exists()) {
                final AuthorizationResponse authorizationResponse = PlatformTools.authorize(apiDomain, httpBasicAuthString);
                if (authorizationResponse.getLoginResponse().map(HttpResponse::isSuccess).orElse(false)) {
                    try {
                        /// Query CTMS Registry:
                        final String registryServiceVersion = "0";
                        final String defaultAdvancedSearchUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/searches", apiDomain, serviceType, serviceVersion, realm);
                        final List<String> advancedSearchUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "search:searches", defaultAdvancedSearchUriTemplate);

                        /// Check, whether advanced search is supported:
                        URL searchesResourceURL = new URL(advancedSearchUriTemplates.get(0));

                        final HttpResponse<String> response = Unirest.get(searchesResourceURL.toString()).asString();

                        final int searchesStatus = response.getStatus();
                        if (HttpURLConnection.HTTP_OK == searchesStatus) {
                            final String rawSearchesResult = response.getBody();
                            final JSONObject searchesResult = new JSONObject(rawSearchesResult);
                            final Object advancedSearchLinkObject = searchesResult.getJSONObject("_links").get("search:advanced-search");
                            // Is advanced search supported?
                            if (null != advancedSearchLinkObject) {
                                /// Doing the advanced search and write the results to stdout:
                                final String searchURLTemplate = ((JSONObject) advancedSearchLinkObject).getString("href");
                                final UriTemplate searchURITemplate = UriTemplate.fromTemplate(searchURLTemplate);
                                final URL advancedSearchResultPageURL = new URL(searchURITemplate.expand());

                                // Create and send the process query's description:
                                final String advancedSearchDescription = removeUTF8BOM(new String(Files.readAllBytes(advancedSearchDescriptionFilePath), StandardCharsets.UTF_8));

                                HttpResponse<String> advancedSearchResponse
                                        = Unirest.post(advancedSearchResultPageURL.toString())
                                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                        .body(advancedSearchDescription)
                                        .asString();

                                final int advancedSearchStatus = advancedSearchResponse.getStatus();
                                if (HttpURLConnection.HTTP_OK == advancedSearchStatus) {
                                    int assetNo = 0;
                                    int pageNo = 0;
                                    // Page through the result:
                                    final StringBuilder sb = new StringBuilder();
                                    try (final Formatter formatter = new Formatter(sb)) {
                                        do {
                                            final String rawProcessQueryPageResult = advancedSearchResponse.getBody();
                                            final JSONObject processQueryPageResult = new JSONObject(rawProcessQueryPageResult);
                                            final JSONObject embeddedResults = (JSONObject) processQueryPageResult.get("_embedded");
                                            // Do we have results:
                                            // If we have more results, follow the next link and get the next page:
                                            final JSONObject linkToNextPage = (JSONObject) processQueryPageResult.getJSONObject("_links").opt("next");
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
                                                advancedSearchResponse = Unirest.get(linkToNextPage.getString("href")).asString();
                                            } else {
                                                advancedSearchResponse = null;
                                            }
                                        } while (null != advancedSearchResponse);
                                    }

                                    LOG.log(Level.INFO, sb::toString);
                                }
                            }
                        } else {
                            LOG.log(Level.INFO, "Failure accessing service. - {0}", response.getStatusText());
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
