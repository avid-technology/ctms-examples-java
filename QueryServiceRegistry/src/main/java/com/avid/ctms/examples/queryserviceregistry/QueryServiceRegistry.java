package com.avid.ctms.examples.queryserviceregistry;

import com.avid.ctms.examples.tools.common.PlatformTools;
import com.mashape.unirest.http.exceptions.UnirestException;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
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

    public static void main(String[] args) throws NoSuchAlgorithmException, KeyManagementException, IOException, KeyStoreException, UnirestException {
        if (5 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <oauthtoken> <serviceversion> <username> <password>", QueryServiceRegistry.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String baseOAuthToken = args[1];
            final String serviceVersion = args[2];
            final String username = args[3];
            final String password = args[4];

            final String authorizationToken = PlatformTools.authorize(apiDomain, baseOAuthToken, username, password);

            if (authorizationToken != null) {
                try {
                    final long then = System.currentTimeMillis();

                    final String registryServiceType = "avid.ctms.registry";
                    /// Check, whether the service registry is available:
                    final URL serviceRootsResourceURL = new URL(String.format("https://%s/apis/%s;version=%s/serviceroots", apiDomain, registryServiceType, serviceVersion));
                    final HttpURLConnection serviceRootsResourceConnection = (HttpURLConnection) serviceRootsResourceURL.openConnection();
                    serviceRootsResourceConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                    serviceRootsResourceConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                    serviceRootsResourceConnection.setRequestProperty("Accept", "application/hal+json");
                    serviceRootsResourceConnection.setRequestProperty("Authorization", authorizationToken);

                    final int serviceRootsStatus = serviceRootsResourceConnection.getResponseCode();
                    System.out.println("Took: "+(System.currentTimeMillis() - then));


                    if (HttpURLConnection.HTTP_OK == serviceRootsStatus) {
                        /// Doing the registry lookup and write the results to stdout:
                        final String rawServiceRootsResult = PlatformTools.getContent(serviceRootsResourceConnection);
                        final JSONObject serviceRootsResult = JSONObject.fromObject(rawServiceRootsResult);

                        final StringBuilder sb = new StringBuilder();
                        try (final Formatter formatter = new Formatter(sb)) {
                            final JSONObject resources = serviceRootsResult.getJSONObject("resources");
                            if (null != resources) {
                                for (final Object name : resources.names()) {
                                    formatter.format("Resource: \"%s\"%n", name);
                                    final Object resourcesObject = serviceRootsResult.getJSONObject("resources").get(name);
                                    int index = 1;
                                    if (resourcesObject instanceof JSONArray) {
                                        for (final Object singleLinkObject : (JSONArray) resourcesObject) {
                                            final String serviceHref = ((JSONObject) singleLinkObject).getString("href");
                                            formatter.format("\t%d. At service <%s>%n", index++, serviceHref);
                                        }
                                    } else {
                                        final String serviceHref = ((JSONObject) resourcesObject).getString("href");
                                        formatter.format("\t1. At service <%s>%n", serviceHref);
                                    }
                                }
                                LOG.log(Level.INFO, sb::toString);
                            } else {
                                LOG.log(Level.INFO, "No services registered.");
                            }
                        }
                    } else {
                        LOG.log(Level.INFO, "Problem accessing <{0}>: {1}", new Object[] {serviceRootsResourceURL, PlatformTools.getContent(serviceRootsResourceConnection)});
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
