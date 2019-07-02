package com.avid.ctms.examples.simplesearchreactiveunirest;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Formatter;
import java.util.concurrent.*;
import java.util.logging.*;

import com.avid.ctms.examples.tools.asyncunirest.*;
import com.damnhandy.uri.template.UriTemplate;
import org.json.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-10-30
 * Time: 20:14
 * Project: CTMS
 */

/**
 * This example issues a simple search for assets with Unirest using reactive programming with CompletableFuture, shows
 * pagewise request of search results and prints the results to stdout.
 */
public class SimpleSearchReactiveUnirest {
    private static final Logger LOG = Logger.getLogger(SimpleSearchReactiveUnirest.class.getName());

    private SimpleSearchReactiveUnirest() {
    }

    /**
     * Promises the results of a simple search.
     *
     * @param simpleSearchResultPageURL the full URL requesting a simple search with the specified search expression
     * @return promise, which promises delivery of the simple search's results as a list of pages as JSONObjects
     */
    private static CompletionStage<List<JSONObject>> simpleSearchAsyncReactive(URL simpleSearchResultPageURL) {
        final CompletableFuture<List<JSONObject>> promise = new CompletableFuture<>();

        try {
            PlatformToolsReactiveUnirest
                .pageThroughResultsAsync(simpleSearchResultPageURL.toString())
                    .whenComplete((result, orError) -> {
                        if (null != result) {
                            promise.complete(result);
                        } else if (null != orError) {
                            promise.completeExceptionally(orError);
                        }
                    });
        } catch (final Exception ex) {
            promise.completeExceptionally(ex);
        }

        return promise;
    }

    /**
     * Promises transformation of the passed search results to a string.
     *
     * @param pages a list of pages as JSONObjects, which represent the search results
     * @param rawSearchExpression a simple search expression
     * @return promise, which promises delivery of the simple search's result as string
     */
    private static CompletionStage<String> stringify(List<JSONObject> pages, String rawSearchExpression) {
        final CompletableFuture<String> promise = new CompletableFuture<>();

        try {
            final StringBuilder sb = new StringBuilder();
            try (final Formatter formatter = new Formatter(sb)) {
                if (pages.isEmpty()) {
                    formatter.format("No hits!");
                } else {
                    int pageNo = 0;
                    int assetNo = 0;

                    for (final JSONObject page : pages) {
                        final JSONArray foundAssets = page.getJSONArray("aa:asset");

                        if (0 < foundAssets.length()) {
                            formatter.format("Page#: %d, search expression: '%s'%n", ++pageNo, rawSearchExpression);
                            for (int i = 0; i < foundAssets.length(); ++i) {
                                final JSONObject asset = foundAssets.getJSONObject(i);
                                final String id = asset.getJSONObject("base").getString("id");
                                final String name =
                                        (asset.getJSONObject("common").has("name"))
                                                ? asset.getJSONObject("common").getString("name")
                                                : "";
                                formatter.format("\tAsset#: %d, id: %s, name: '%s'%n", ++assetNo, id, name);
                            }
                        }
                    }
                }
            }
            promise.complete(sb.toString());
        } catch (final Throwable throwable) {
            promise.completeExceptionally(throwable);
        }

        return promise;
    }

    public static void main(String[] args) throws Exception {
        if (6 != args.length || "'".equals(args[5]) || !args[5].startsWith("'") || !args[5].endsWith("'")) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <servicetype> <serviceversion> <realm> '<simplesearchexpression>'", SimpleSearchReactiveUnirest.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceType = args[2];
            final String serviceVersion = args[3];
            final String realm = args[4];
            final String rawSearchExpression = args[5].substring(1, args[5].length() - 1);

            final String registryServiceVersion = "0";
            final String defaultSimpleSearchUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/searches/simple?search={search}{&offset,limit,sort}", apiDomain, serviceType, serviceVersion, realm);

            CompletableFuture
                    .runAsync(PlatformToolsReactiveUnirest::prepare)
                    .thenCompose(o -> PlatformToolsReactiveUnirest.getAuthEndpoint(apiDomain))
                    .thenCompose(PlatformToolsReactiveUnirest::getIdentityProviders)
                    .thenCompose(response -> PlatformToolsReactiveUnirest.authorize(response, apiDomain, httpBasicAuthString))
                    .thenCompose(o -> PlatformToolsReactiveUnirest.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "search:simple-search", defaultSimpleSearchUriTemplate))
                    // .thenApply(o -> Collections.singletonList(defaultSimpleSearchUriTemplate)) // for debugging purposes
                    .thenApplyAsync(o -> {
                        URL simpleSearchFirstPageURL = null;
                        try {
                            final Optional<String> simpleSearchUriTemplateCandidate = o.stream().filter(searchUrl -> searchUrl.contains(realm)).findFirst();
                            final String simpleSearchUriTemplate = simpleSearchUriTemplateCandidate.orElse(defaultSimpleSearchUriTemplate);
                            final UriTemplate searchURITemplate = UriTemplate.fromTemplate(simpleSearchUriTemplate);

                            simpleSearchFirstPageURL = new URL(searchURITemplate.set("search", rawSearchExpression).expand());
                        } catch (final IOException e) {
                            LOG.log(Level.SEVERE, "failure", e);
                        }
                        return simpleSearchFirstPageURL;
                    })
                    .thenCompose(SimpleSearchReactiveUnirest::simpleSearchAsyncReactive)
                    .thenCompose(o -> stringify(o, rawSearchExpression))
                    .thenAccept(it -> LOG.log(Level.INFO, it))
                    .thenCompose(o -> PlatformToolsReactiveUnirest.getAuthEndpoint(apiDomain))
                    .thenCompose(PlatformToolsReactiveUnirest::getCurrentToken)
                    .thenCompose(PlatformToolsReactiveUnirest::removeToken)
                    .whenComplete((result, orError) -> {
                        if (null != orError) {
                            LOG.log(Level.SEVERE, "failure", orError);
                        }
                        PlatformToolsReactiveUnirest.unregister();
                        LOG.log(Level.INFO, "End");
                    })
                    .join();
        }
    }
}
