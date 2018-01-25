package com.avid.ctms.examples.simplesearch;

import java.net.*;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

import com.avid.ctms.examples.tools.common.PlatformTools;
import com.damnhandy.uri.template.UriTemplate;
import net.sf.json.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-06-17
 * Time: 08:14
 * Project: CTMS
 */

/**
 * This example issues a simple search for assets, shows pagewise request of search results and prints the results to stdout.
 */
public class SimpleSearch {
    private static final Logger LOG = Logger.getLogger(SimpleSearch.class.getName());

    private SimpleSearch() {
    }

    public static void main(String[] args) throws Exception {
        if (7 != args.length || "'".equals(args[6]) || !args[6].startsWith("'") || !args[6].endsWith("'")) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <servicetype> <serviceversion> <realm> <username> <password> '<simplesearchexpression>'", SimpleSearch.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String serviceType = args[1];
            final String serviceVersion = args[2];
            final String realm = args[3];
            final String username = args[4];
            final String password = args[5];
            final String rawSearchExpression = args[6].substring(1, args[6].length() - 1);

            final boolean successfullyAuthorized = PlatformTools.authorize(apiDomain, username, password);
            if (successfullyAuthorized) {
                try {
                    /// Query CTMS Registry:
                    final String registryServiceVersion = "0";
                    final String defaultSimpleSearchUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/searches/simple?search={search}{&offset,limit,sort}", apiDomain, serviceType, serviceVersion, realm);
                    final List<String> simpleSearchUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "search:simple-search", defaultSimpleSearchUriTemplate);
                    // final List<String> simpleSearchUriTemplates = Collections.singletonList(defaultSimpleSearchUriTemplate); // for debugging purposes

                    /// Prepare simple search request:
                    final UriTemplate searchURITemplate = UriTemplate.fromTemplate(simpleSearchUriTemplates.get(0));
                    URL simpleSearchResultPageURL = new URL(searchURITemplate.set("search", rawSearchExpression).expand());

                    /// Issue the simple search and page through the result:
                    int assetNo = 0;
                    int pageNo = 0;
                    final StringBuilder sb = new StringBuilder();
                    try (final Formatter formatter = new Formatter(sb)) {
                        do {
                            final HttpURLConnection simpleSearchResultPageConnection = (HttpURLConnection) simpleSearchResultPageURL.openConnection();
                            simpleSearchResultPageConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                            simpleSearchResultPageConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                            simpleSearchResultPageConnection.setRequestProperty("Accept", "application/hal+json");

                            final int simpleSearchStatus = simpleSearchResultPageConnection.getResponseCode();
                            if (HttpURLConnection.HTTP_OK == simpleSearchStatus) {
                                final String rawSimpleSearchPageResult = PlatformTools.getContent(simpleSearchResultPageConnection);

                                final JSONObject simpleSearchPageResult = JSONObject.fromObject(rawSimpleSearchPageResult);
                                final JSONObject embeddedResults = (JSONObject) simpleSearchPageResult.get("_embedded");
                                // Do we have results:
                                if (null != embeddedResults) {
                                    final JSONArray foundAssets = embeddedResults.getJSONArray("aa:asset");

                                    if (!foundAssets.isEmpty()) {
                                        formatter.format("Page#: %d, search expression: '%s'%n", ++pageNo, rawSearchExpression);
                                        for (final Object item : foundAssets) {
                                            final JSONObject asset = (JSONObject) item;
                                            final String id = asset.getJSONObject("base").getString("id");
                                            final String name =
                                                    (asset.getJSONObject("common").has("name"))
                                                        ? asset.getJSONObject("common").getString("name")
                                                        : "";

                                            formatter.format("\tAsset#: %d, id: %s, name: '%s'%n", ++assetNo, id, name);
                                        }
                                    }
                                } else {
                                    LOG.log(Level.INFO, "No results found for search expression {0}.", rawSearchExpression);
                                }

                                // If we have more results, follow the next link and get the next page:
                                final JSONObject linkToNextPage = (JSONObject) simpleSearchPageResult.getJSONObject("_links").get("next");
                                simpleSearchResultPageURL
                                        = null != linkToNextPage
                                        ? new URL(linkToNextPage.getString("href"))
                                        : null;
                            } else {
                                LOG.log(Level.INFO, "Simple search failed for search expression {0}. -> {1}", new Object[] {rawSearchExpression, PlatformTools.getContent(simpleSearchResultPageConnection)});
                                simpleSearchResultPageURL = null;
                            }
                        } while (null != simpleSearchResultPageURL);
                    }
                    LOG.log(Level.INFO, 0 != sb.length() ? sb.toString() : "No hits!");
                } catch (final Throwable throwable) {
                    LOG.log(Level.INFO, "failure", throwable);
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
