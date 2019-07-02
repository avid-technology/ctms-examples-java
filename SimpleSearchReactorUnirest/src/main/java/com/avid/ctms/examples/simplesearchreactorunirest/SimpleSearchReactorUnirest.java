package com.avid.ctms.examples.simplesearchreactorunirest;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.avid.ctms.examples.tools.reactor.PlatformToolsReactor;
import com.damnhandy.uri.template.UriTemplate;
import org.json.*;
import reactor.core.publisher.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Project: CTMS
 */

/**
 * This example issues a simple search for assets using Reactor. Reactor features reactive
 * streaming, which supports dealing with data as an series of events. Then data can be consumed, as those are pushed to
 * the subscriber, rather than being pulled from a consumer. The benefit of working like so is that the client needs not
 * to wait and block until results are returned from the service, instead the client get notified from the event source.
 */
public class SimpleSearchReactorUnirest {
    private static final Logger LOG = Logger.getLogger(SimpleSearchReactorUnirest.class.getName());

    private SimpleSearchReactorUnirest() {
    }

    static {
        // Expensive!
        //Hooks.onOperator(op -> op.operatorStacktrace());
    }

    /**
     * Promises the results of a simple search.
     *
     * @param simpleSearchResultPageURL the full URL requesting a simple search with the specified search expression
     * @return Flux, which promises delivery of the simple search's results as a list of pages as JSONObjects
     */
    private static Flux<JSONObject> simpleSearchResultsInPages(URL simpleSearchResultPageURL) {
        return Flux.create(sink -> {
            try {
                PlatformToolsReactor.pageThroughResultsAsync(simpleSearchResultPageURL.toString(), sink);
            } catch (final Exception ex) {
                sink.error(ex);
            }
        });
    }

    private static Flux<JSONObject> simpleSearchResultsInPages(String simpleSearchUriTemplate, String rawSearchExpression, int pageSize) {
        final UriTemplate searchURITemplate = UriTemplate.fromTemplate(simpleSearchUriTemplate);
        URL simpleSearchFirstPageURL = null;
        try {
            final int effectivePageSize = 0 <= pageSize ? pageSize : 50;
            simpleSearchFirstPageURL
                    = new URL(searchURITemplate
                    .set("search", rawSearchExpression)
                    .set("limit", effectivePageSize)
                    .expand());
        } catch (final IOException e) {
            LOG.log(Level.SEVERE, "failure", e);
        }
        return simpleSearchResultsInPages(simpleSearchFirstPageURL);
    }

    private static Collector<JSONObject, PlatformToolsReactor.Triple<Integer, Integer, ArrayList<String>>, PlatformToolsReactor.Triple<Integer, Integer, ArrayList<String>>> collectPages() {
        final BiConsumer<PlatformToolsReactor.Triple<Integer, Integer, ArrayList<String>>, JSONObject> consumer = (PlatformToolsReactor.Triple<Integer, Integer, ArrayList<String>> lines, JSONObject page) -> {
            lines.third.add(String.format("Page#: %s", ++lines.first));

            itemizePage(page)
                    .collect(
                            () -> new PlatformToolsReactor.Pair<>(lines.second, lines.third),
                            (PlatformToolsReactor.Pair<Integer, ArrayList<String>> lines2, JSONObject item) -> {
                                final String id = item.getJSONObject("base").getString("id");
                                final String name =
                                        (item.getJSONObject("common").has("name"))
                                                ? item.getJSONObject("common").getString("name")
                                                : "";
                                lines2.second.add(String.format("\tAsset#: %s id: %s, name: '%s'", ++lines2.first, id, name));
                                lines.second = lines2.first;
                            })
                    .subscribe();
        };

        return Collector.of(
                () -> new PlatformToolsReactor.Triple<>(0, 0, new ArrayList<>())
                , consumer
                , (lhs, rhs) -> lhs
        );
    }

    private static Flux<JSONObject> itemizePage(JSONObject page) {
        return Flux.create(sink -> {
            try {
                final JSONArray foundAssets = page.getJSONArray("aa:asset");
                for (int i = 0; i < foundAssets.length(); ++i) {
                    final JSONObject asset = foundAssets.getJSONObject(i);
                    sink.next(asset);
                }

                sink.complete();
            } catch (final Exception e) {
                sink.error(e);
            }
        });
    }

    private static void logout(String apiDomain) {
        PlatformToolsReactor.getAuthEndpoint(apiDomain)
                .flatMap(PlatformToolsReactor::getCurrentToken)
                .flatMap(PlatformToolsReactor::removeToken)
                .doOnTerminate(PlatformToolsReactor::unregister)
                .subscribe(it -> LOG.log(Level.INFO, "End"));
    }


    public static void main(String[] args) throws Exception {
        if (6 != args.length || "'".equals(args[5]) || !args[5].startsWith("'") || !args[5].endsWith("'")) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <servicetype> <serviceversion> <realm> '<simplesearchexpression>'", SimpleSearchReactorUnirest.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceType = args[2];
            final String serviceVersion = args[3];
            final String realm = args[4];
            final String rawSearchExpression = args[5].substring(1, args[5].length() - 1);

            final String registryServiceVersion = "0";
            final String defaultSimpleSearchUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/searches/simple?search={search}{&offset,limit,sort}", apiDomain, serviceType, serviceVersion, realm);

            // flatMap -> thenCompose

            PlatformToolsReactor.prepare();
            final Flux<JSONObject> thePages = PlatformToolsReactor.getAuthEndpoint(apiDomain)
                .flatMap(PlatformToolsReactor::getIdentityProviders)
                .flatMap(identityProviders -> PlatformToolsReactor.authorize(identityProviders, apiDomain, httpBasicAuthString))
                .flatMap(pass -> PlatformToolsReactor.findInRegistry(apiDomain, Collections.singletonList(serviceType), registryServiceVersion, "search:simple-search", defaultSimpleSearchUriTemplate))
                .map(o -> {
                    final Optional<String> simpleSearchUriTemplateCandidate = o.stream().filter(searchUrl -> searchUrl.contains(realm)).findFirst();
                    final String simpleSearchUriTemplate = simpleSearchUriTemplateCandidate.orElse(defaultSimpleSearchUriTemplate);
                    return simpleSearchUriTemplate;
                })
                .flatMapMany(simpleSearchUriTemplate -> simpleSearchResultsInPages(simpleSearchUriTemplate, rawSearchExpression, /*pageSize*/ 50));

            thePages
                    .collect(collectPages())
                    .map(lines -> lines.third.stream().collect(Collectors.joining(String.format("%n"))))
                    .doOnTerminate(() -> logout(apiDomain))
                    .doOnError(oops -> LOG.log(Level.WARNING, "Error", oops))
                    .subscribe(it -> {
                        if (!it.isEmpty()) {
                            LOG.log(Level.INFO, "last page");
                            LOG.log(Level.INFO, "{0}", String.format("%n%s", it));
                        } else {
                            LOG.log(Level.INFO, "No hits!");
                        }
                    });
 /*
            Flux<JSONObject> firstThree = thePages.take(3);
            firstThree.collect(collectPages())
                    .map(lines -> lines.third.stream().collect(Collectors.joining(String.format("%n"))))
                    .subscribe(it -> {
                        LOG.log(Level.INFO, "took3");
                        LOG.log(Level.INFO, "{0}", String.format("%n%s", it));
                    }
                    );


            Flux<JSONObject> lastTwo = thePages.skip(3).take(2);
            lastTwo.collect(collectPages())
                    .map(lines -> lines.third.stream().collect(Collectors.joining(String.format("%n"))))
                    .subscribe(it -> {
                        LOG.log(Level.INFO, "skipped3took2");
                        LOG.log(Level.INFO, "{0}", String.format("%n%s", it));
                    });
            */


                //    .take(3)
                //.collect(collectPages())
                //.map(lines -> lines.third.stream().collect(Collectors.joining(String.format("%n"))))
                //.doOnTerminate((eitherResult, orError) -> logout(apiDomain))
                //.doOnError(oops -> LOG.log(Level.WARNING, "Error", oops))
                //.subscribe(it -> LOG.log(Level.INFO, "{0}", String.format("%n%s", it)));
        }
    }
}
