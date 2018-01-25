package com.avid.ctms.examples.orchestration.queryprocesses;

import com.avid.ctms.examples.tools.common.PlatformTools;
import com.damnhandy.uri.template.UriTemplate;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
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
 * This example queries process instances, shows pagewise request of query results and prints the results to stdout.
 */
public class QueryProcesses {
    private static final Logger LOG = Logger.getLogger(QueryProcesses.class.getName());

    private QueryProcesses() {
    }

    public static void main(String[] args) throws Exception {
        if (6 != args.length || "'".equals(args[5]) || !args[5].startsWith("'") || !args[5].endsWith("'")) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <serviceversion> <realm> <username> <password> '<simplesearchexpression>'", QueryProcesses.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String serviceVersion = args[1];
            final String realm = args[2];
            final String username = args[3];
            final String password = args[4];
            final String rawSearchExpression = args[5].substring(1, args[5].length() - 1);

            final boolean successfullyAuthorized = PlatformTools.authorize(apiDomain, username, password);
            if (successfullyAuthorized) {
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
                    HttpURLConnection processQueryResultPageConnection = (HttpURLConnection) processQueryURL.openConnection();
                    processQueryResultPageConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                    processQueryResultPageConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                    processQueryResultPageConnection.setRequestMethod("POST");
                    processQueryResultPageConnection.setDoOutput(true);
                    processQueryResultPageConnection.setRequestProperty("Content-Type", "application/json");
                    processQueryResultPageConnection.setRequestProperty("Accept", "application/hal+json");
                    processQueryResultPageConnection.getOutputStream().write(queryContent.getBytes());

                    final int processQueryStatus = processQueryResultPageConnection.getResponseCode();
                    if (HttpURLConnection.HTTP_OK == processQueryStatus) {
                        int assetNo = 0;
                        int pageNo = 0;
                        // Page through the result:
                        final StringBuilder sb = new StringBuilder();
                        try (final Formatter formatter = new Formatter(sb)) {
                            do {
                                final String rawProcessQueryPageResult = PlatformTools.getContent(processQueryResultPageConnection);
                                final JSONObject processQueryPageResult = JSONObject.fromObject(rawProcessQueryPageResult);
                                final JSONObject embeddedResults = (JSONObject) processQueryPageResult.get("_embedded");
                                // Do we have results:
                                if (null != embeddedResults) {
                                    final JSONArray foundProcessInstances = embeddedResults.getJSONArray("orchestration:process");

                                    if (!foundProcessInstances.isEmpty()) {
                                        formatter.format("Page#: %d, process query expression: '%s'%n", ++pageNo, rawSearchExpression);
                                        for (final Object item : foundProcessInstances) {
                                            final JSONObject asset = (JSONObject) item;
                                            final String id = asset.getJSONObject("base").getString("id");
                                            final String name = null != asset.getJSONObject("common").get("name") ? asset.getJSONObject("common").getString("name") : "";

                                            formatter.format("\tProcessItem#: %d, id: %s, name: '%s'%n", ++assetNo, id, name);
                                        }
                                    }
                                } else {
                                    LOG.log(Level.INFO, "No results found for search expression {0}.", rawSearchExpression);
                                }

                                // If we have more results, follow the next link and get the next page:
                                final JSONObject linkToNextPage = (JSONObject) processQueryPageResult.getJSONObject("_links").get("next");
                                if (null != linkToNextPage) {
                                    processQueryResultPageConnection = (HttpURLConnection) new URL(linkToNextPage.getString("href")).openConnection();
                                    processQueryResultPageConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                                    processQueryResultPageConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                                } else {
                                    processQueryResultPageConnection = null;
                                }
                            } while (null != processQueryResultPageConnection);

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
