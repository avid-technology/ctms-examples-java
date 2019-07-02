package com.avid.ctms.examples.simplesearch;

import java.net.*;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

import com.avid.ctms.examples.tools.common.AuthorizationResponse;
import com.avid.ctms.examples.tools.common.PlatformTools;
import com.damnhandy.uri.template.UriTemplate;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
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
        if (6 != args.length || "'".equals(args[5]) || !args[5].startsWith("'") || !args[5].endsWith("'")) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <servicetype> <serviceversion> <realm> '<simplesearchexpression>'", SimpleSearch.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceType = args[2];
            final String serviceVersion = args[3];
            final String realm = args[4];
            final String rawSearchExpression = args[5].substring(1, args[5].length() - 1);

            final AuthorizationResponse authorizationResponse = PlatformTools.authorize(apiDomain, httpBasicAuthString);
            if (authorizationResponse.getLoginResponse().map(HttpResponse::isSuccess).orElse(false)) {
                try {
                    /// Query CTMS Registry:
                    final String registryServiceVersion = "0";
                    final String defaultSimpleSearchUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/searches/simple?search={search}{&offset,limit,sort}", apiDomain, serviceType, serviceVersion, realm);
                    final List<String> simpleSearchUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "search:simple-search", defaultSimpleSearchUriTemplate);

                    /// Prepare simple search request:
                    final Optional<String> simpleSearchUriTemplateCandidate = simpleSearchUriTemplates.stream().filter(searchUrl -> searchUrl.contains(realm)).findFirst();
                    final UriTemplate searchURITemplate = UriTemplate.fromTemplate(simpleSearchUriTemplateCandidate.orElse(defaultSimpleSearchUriTemplate));
                    URL simpleSearchResultPageURL = new URL(searchURITemplate.set("search", rawSearchExpression).expand());

                    /// Issue the simple search and page through the result:
                    int assetNo = 0;
                    int pageNo = 0;
                    final StringBuilder sb = new StringBuilder();
                    try (final Formatter formatter = new Formatter(sb)) {
                        do {
                            final HttpResponse<String> response = Unirest.get(simpleSearchResultPageURL.toString()).asString();
                            final int simpleSearchStatus = response.getStatus();
                            if (HttpURLConnection.HTTP_OK == simpleSearchStatus) {
                                final String rawSimpleSearchPageResult = response.getBody();

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
                                LOG.log(Level.INFO, "Simple search failed for search expression {0}. - {1}", new Object[]{rawSearchExpression, response.getStatusText()});
                                simpleSearchResultPageURL = null;
                            }
                        } while (null != simpleSearchResultPageURL);
                    }
                    LOG.log(Level.INFO, 0 != sb.length() ? sb.toString() : "No hits!");
                } catch (final Exception e) {
                    LOG.log(Level.INFO, "failure", e);
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
