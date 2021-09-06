package com.avid.ctms.examples.queryserviceregistry;

import com.avid.ctms.examples.tools.common.AuthorizationResponse;
import com.avid.ctms.examples.tools.common.PlatformTools;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.json.*;


import java.net.HttpURLConnection;
import java.util.Formatter;
import java.util.logging.*;

/**
 * Copyright 2016-2021 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-07-04
 * Time: 13:41
 * Project: CTMS
 */

/**
 * This example enumerates the entries in the service registry and writes the results to stdout.
 */
public class QueryServiceRegistry {
    private static final Logger LOG = Logger.getLogger(QueryServiceRegistry.class.getName());

    private QueryServiceRegistry() {
    }

    public static void main(String[] args) throws Exception {
        if (3 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <serviceversion>", QueryServiceRegistry.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceVersion = args[2];

            final AuthorizationResponse authorizationResponse = PlatformTools.authorize(apiDomain, httpBasicAuthString);
            if (authorizationResponse.getLoginResponse().map(HttpResponse::isSuccess).orElse(false)) {
                try {
                    final String registryServiceType = "avid.ctms.registry";
                    /// Check, whether the service registry is available:

                    final String urlServiceRootsResource = String.format("https://%s/apis/%s;version=%s/serviceroots", apiDomain, registryServiceType, serviceVersion);
                    final HttpResponse<String> response = Unirest.get(urlServiceRootsResource).asString();
                    final int serviceRootsStatus = response.getStatus();
                    if (HttpURLConnection.HTTP_OK == serviceRootsStatus) {
                        /// Doing the registry lookup and write the results to stdout:
                        final String rawServiceRootsResult = response.getBody();
                        final JSONObject serviceRootsResult = new JSONObject(rawServiceRootsResult);

                        final StringBuilder sb = new StringBuilder();
                        try (final Formatter formatter = new Formatter(sb)) {
                            final JSONObject resources = serviceRootsResult.optJSONObject("resources");
                            if (null != resources) {
                                for (final Object name : resources.names()) {
                                    formatter.format("Resource: \"%s\"%n", name);
                                    final Object resourcesObject = serviceRootsResult.getJSONObject("resources").get((String) name);
                                    int index = 1;
                                    if (resourcesObject instanceof JSONArray) {
                                        for (final Object singleLinkObject : (JSONArray) resourcesObject) {
                                            final String serviceHref = ((JSONObject) singleLinkObject).optString("href");
                                            formatter.format("\t%d. At service <%s>%n", index++, serviceHref);
                                        }
                                    } else {
                                        final String serviceHref = ((JSONObject) resourcesObject).optString("href");
                                        formatter.format("\t1. At service <%s>%n", serviceHref);
                                    }
                                }
                                LOG.log(Level.INFO, sb::toString);
                            } else {
                                LOG.log(Level.INFO, "No services registered.");
                            }
                        }
                    } else {
                        LOG.log(Level.INFO, "Problem accessing <{0}> - {1}", new Object[]{urlServiceRootsResource, response.getStatusText()});
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
