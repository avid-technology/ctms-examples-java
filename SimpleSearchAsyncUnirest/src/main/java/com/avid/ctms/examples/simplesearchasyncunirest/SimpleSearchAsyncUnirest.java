package com.avid.ctms.examples.simplesearchasyncunirest;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Formatter;
import java.util.function.*;
import java.util.logging.*;

import com.avid.ctms.examples.tools.asyncunirest.*;
import com.avid.ctms.examples.tools.common.*;
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
 * This example issues a simple search for assets asynchronously with Unirest, shows pagewise request of search results and prints
 * the results to stdout.
 */
public class SimpleSearchAsyncUnirest {
    private static final Logger LOG = Logger.getLogger(SimpleSearchAsyncUnirest.class.getName());

    private SimpleSearchAsyncUnirest() {
    }

    /**
     * Performs a simple search.
     *
     * @param simpleSearchResultPageURL     the full URL requesting a simple search with the specified search expression
     * @param rawSearchExpression the raw search expression
     * @param done                a "continuation" callback, which is called, if the simple search procedure ended successfully. The
     *                            callback should execute the code, which continues working with the client resulting from the
     *                            succeeded simple search.
     * @param failed              a "continuation" callback, which is called, if the simple search procedure failed.
     */
    private static void simpleSearchAsync(URL simpleSearchResultPageURL, String rawSearchExpression, Consumer<Object> done, Terminator<String, Throwable> failed) {
        try {
            PlatformToolsAsyncUnirest.pageThroughResultsAsync(
                    simpleSearchResultPageURL.toString()
                    , pages -> {
                        final StringBuilder sb = new StringBuilder();
                        try {
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
                            LOG.log(Level.INFO, sb::toString);
                            done.accept(null);
                        } catch (final Exception e) {
                            failed.terminate(e.getMessage(), e);
                        }
                    }
                    , failed::terminate);
        } catch (Exception ex) {
            failed.terminate(null, ex);
        }
    }

    public static void main(String[] args) throws Exception {
        if (7 != args.length || "'".equals(args[6]) || !args[6].startsWith("'") || !args[6].endsWith("'")) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <servicetype> <serviceversion> <realm> <username> <password> '<simplesearchexpression>'", SimpleSearchAsyncUnirest.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String serviceType = args[1];
            final String serviceVersion = args[2];
            final String realm = args[3];
            final String username = args[4];
            final String password = args[5];
            final String rawSearchExpression = args[6].substring(1, args[6].length() - 1);

            final String registryServiceVersion = "0";
            final String defaultSimpleSearchUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/searches/simple?search={search}{&offset,limit,sort}", apiDomain, serviceType, serviceVersion, realm);

            PlatformToolsAsyncUnirest.authorize(apiDomain
                    , username
                    , password
                    , o -> PlatformToolsAsyncUnirest.findInRegistry(apiDomain
                            , Collections.singletonList(serviceType)
                            , registryServiceVersion
                            , "search:simple-search"
                            , defaultSimpleSearchUriTemplate,
                            it -> {
                                final String simpleSearchUriTemplate = it.get(0);
                                // final String simpleSearchUriTemplate = defaultSimpleSearchUriTemplate; // for debugging purposes
                                final UriTemplate searchURITemplate = UriTemplate.fromTemplate(simpleSearchUriTemplate);
                                URL simpleSearchFirstPageURL = null;
                                try {
                                    simpleSearchFirstPageURL = new URL(searchURITemplate.set("search", rawSearchExpression).expand());
                                } catch (final IOException e) {
                                    e.printStackTrace();
                                }

                                simpleSearchAsync(
                             simpleSearchFirstPageURL
                            , rawSearchExpression
                            , o1 -> PlatformToolsAsyncUnirest.logout(
                            apiDomain
                            , o2 -> {
                                PlatformToolsAsyncUnirest.unregister();
                                LOG.log(Level.INFO, "End");
                            }
                            , (message, throwable) -> {
                                PlatformToolsAsyncUnirest.unregister();
                                LOG.log(Level.INFO, "Logout failed: {0}, {1}", new Object[] {message, null != throwable ? throwable.getCause() : ""});
                            })
                            , (message, throwable) -> {
                        PlatformToolsAsyncUnirest.unregister();
                        LOG.log(Level.INFO, "Simple search failed: {0}, {1}",  new Object[] {message, null != throwable ? throwable.getCause() : ""});
                    });})

                    , (message, throwable) -> {
                        //PlatformToolsAsyncUnirest.unregister();
                        LOG.log(Level.INFO, "Authorization failed: {0}, {1}",  new Object[] {message, null != throwable ? throwable.getCause() : ""});
                    }
            );
        }
    }
}
