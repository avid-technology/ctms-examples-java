package com.avid.ctms.examples.orchestration.queryprocesses;

import com.avid.ctms.examples.tools.common.AuthorizationResponse;
import com.avid.ctms.examples.tools.common.PlatformTools;
import com.damnhandy.uri.template.UriTemplate;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.json.*;

import javax.ws.rs.core.HttpHeaders;
import java.net.HttpURLConnection;
import java.net.URL;
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
 * This example queries process instances, shows pagewise request of query results and prints the results to stdout.
 */
public class QueryProcesses {
    private static final Logger LOG = Logger.getLogger(QueryProcesses.class.getName());

    private QueryProcesses() {
    }

    public static void main(String[] args) throws Exception {
        if (5 != args.length || "'".equals(args[4]) || !args[4].startsWith("'") || !args[4].endsWith("'")) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <serviceversion> <realm> '<simplesearchexpression>'", QueryProcesses.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceVersion = args[2];
            final String realm = args[3];
            final String rawSearchExpression = args[4].substring(1, args[4].length() - 1);

            final AuthorizationResponse authorizationResponse = PlatformTools.authorize(apiDomain, httpBasicAuthString);
            if (authorizationResponse.getLoginResponse().map(HttpResponse::isSuccess).orElse(false)) {
                try {
                    final String orchestrationServiceType = "avid.orchestration.ctc";

                    /// Query CTMS Registry:
                    final String registryServiceVersion = "0";
                    final String processQueryUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/process-queries/{id}{?offset,limit,sort}", apiDomain, orchestrationServiceType, serviceVersion, realm);
                    final List<String> processQueryUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(orchestrationServiceType), registryServiceVersion, "orchestration:process-query", processQueryUriTemplate);


                    /// Doing the process query and write the results to stdout:
                    final UriTemplate processQueryURITemplate = UriTemplate.fromTemplate(processQueryUriTemplates.get(0));
                    final URL processQueryURL = new URL(processQueryURITemplate.expand());
                    // Create and send the process query's description:
                    final String queryExpression = String.format("<query version='1.0'><search><quick>%s</quick></search></query>", rawSearchExpression);
                    final String queryContent = new JSONObject().accumulate("query", queryExpression).toString();
                    HttpResponse<String> processQueryResponse
                            = Unirest.post(processQueryURL.toString())
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(queryContent)
                            .asString();

                    final int processQueryStatus = processQueryResponse.getStatus();
                    if (HttpURLConnection.HTTP_OK == processQueryStatus) {
                        int assetNo = 0;
                        int pageNo = 0;
                        // Page through the result:
                        final StringBuilder sb = new StringBuilder();
                        try (final Formatter formatter = new Formatter(sb)) {
                            do {
                                final String rawProcessQueryPageResult = processQueryResponse.getBody();
                                final JSONObject processQueryPageResult = new JSONObject(rawProcessQueryPageResult);
                                final JSONObject embeddedResults = (JSONObject) processQueryPageResult.opt("_embedded");
                                // Do we have results:
                                if (null != embeddedResults) {
                                    final JSONArray foundProcessInstances = embeddedResults.getJSONArray("orchestration:process");

                                    if (!foundProcessInstances.isEmpty()) {
                                        formatter.format("Page#: %d, process query expression: '%s'%n", ++pageNo, rawSearchExpression);
                                        for (final Object item : foundProcessInstances) {
                                            final JSONObject asset = (JSONObject) item;
                                            final String id = asset.getJSONObject("base").getString("id");
                                            final String name = null != asset.getJSONObject("common").opt("name") ? asset.getJSONObject("common").getString("name") : "";

                                            formatter.format("\tProcessItem#: %d, id: %s, name: '%s'%n", ++assetNo, id, name);
                                        }
                                    }
                                } else {
                                    LOG.log(Level.INFO, "No results found for search expression {0}.", rawSearchExpression);
                                }

                                // If we have more results, follow the next link and get the next page:
                                final JSONObject links = processQueryPageResult.optJSONObject("_links");
                                final JSONObject linkToNextPage = null != links ? links.optJSONObject("next") : null;
                                if (null != linkToNextPage) {
                                    processQueryResponse = Unirest.get(linkToNextPage.getString("href")).asString();
                                } else {
                                    processQueryResponse = null;
                                }
                            } while (null != processQueryResponse);

                            LOG.log(Level.INFO, sb::toString);
                        }
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
