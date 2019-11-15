package com.avid.ctms.examples.queryaggregatedattributes;

import com.avid.ctms.examples.tools.common.AuthorizationResponse;
import com.avid.ctms.examples.tools.common.PlatformTools;
import com.damnhandy.uri.template.*;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.Formatter;
import java.util.logging.*;

/**
 * Copyright 2013-2019 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2017-06-20
 * Time: 8:31
 * Project: CTMS
 */

/**
 * This example enumerates the aggregated attributes.
 */
public class QueryAggregatedAttributes {
    private static final Logger LOG = Logger.getLogger(QueryAggregatedAttributes.class.getName());

    private QueryAggregatedAttributes() {
    }

    public static void main(String[] args) throws Exception {
        if (3 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <httpbasicauthstring> <serviceversion>", QueryAggregatedAttributes.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String httpBasicAuthString = args[1];
            final String serviceVersion = args[2];

            // Specify an IETF BCP 47 language tag, such as "en-US":
            final String lang = ""; // "" represents the default language, which is "en"

            final AuthorizationResponse authorizationResponse = PlatformTools.authorize(apiDomain, httpBasicAuthString);
            if (authorizationResponse.getLoginResponse().map(HttpResponse::isSuccess).orElse(false)) {
                try {
                    final String dataModelAggregatorServiceType = "avid.ctms.datamodel.aggregator";

                    /// Query CTMS Registry:
                    final String registryServiceVersion = "0";
                    final String defaultAggregatedDataModelUriTemplate = String.format("https://%s/apis/%s;version=%s/aggregateddatamodel{?lang}", apiDomain, dataModelAggregatorServiceType, serviceVersion);
                    final List<String> aggregatedDataModelUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(dataModelAggregatorServiceType), registryServiceVersion, "datamodel:aggregated-model", defaultAggregatedDataModelUriTemplate);

                    /// Prepare simple search request:
                    final UriTemplate aggregatedDataModeUriTemplate = UriTemplate.fromTemplate(aggregatedDataModelUriTemplates.get(0));

                    /// Check, whether the service registry is available:
                    final URL aggregatedDataModelResourceURL = new URL(aggregatedDataModeUriTemplate.set("lang", lang).expand());

                    final HttpResponse<String> response
                            = Unirest.get(aggregatedDataModelResourceURL.toString()).asString();

                    final int aggregatedDataModelStatus = response.getStatus();
                    if (HttpURLConnection.HTTP_OK == aggregatedDataModelStatus) {
                        final String rawAggregatedDataModelResult = response.getBody();
                        final JSONObject aggregatedDataModelResult = JSONObject.fromObject(rawAggregatedDataModelResult);

                        final JSONObject attributes = aggregatedDataModelResult.getJSONObject("attributes");
                        if (null != attributes && !attributes.isNullObject()) {
                            final StringBuilder sb = new StringBuilder();
                            try (final Formatter formatter = new Formatter(sb)) {
                                final Object customAttributes = attributes.get("custom");
                                if (null != customAttributes) {
                                    final List customAttributeList = flatten(customAttributes);
                                    formatter.format("%ncustom attributes:%n");
                                    int nCustomAttributes = 0;
                                    for (final Object attribute : customAttributeList) {
                                        formatter.format("%s%n%s%n", ++nCustomAttributes, attribute);
                                    }
                                }

                                final Object commonAttributes = attributes.get("common");
                                if (null != commonAttributes) {
                                    final List commonAttributeList = flatten(commonAttributes);
                                    formatter.format("%ncommon attributes:%n");
                                    int nCommonAttributes = 0;
                                    for (final Object attribute : commonAttributeList) {
                                        formatter.format("%s%n%s%n", ++nCommonAttributes, attribute);
                                    }
                                }

                                final String resultingOutput = sb.toString();
                                LOG.log(Level.INFO, resultingOutput);
                            }
                        } else {
                            LOG.log(Level.INFO, "No attributes found.");
                        }
                    } else {
                        LOG.log(Level.SEVERE, "Problem accessing <{0}> - {1}", new Object[]{aggregatedDataModelResourceURL, response.getStatusText()});
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

    private static List<Object> flatten(Object customAttributes) {
        ArrayList<Object> attributeList = new ArrayList<>();
        if (customAttributes instanceof JSONArray) {
            final JSONArray array = (JSONArray) customAttributes;
            attributeList.ensureCapacity(array.size());
            for (final Object attribute : array) {
                attributeList.add(attribute);
            }
        } else {
            attributeList.add(customAttributes);
        }
        return attributeList;
    }
}
