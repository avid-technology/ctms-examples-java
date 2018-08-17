package com.avid.ctms.examples.orchestration.startprocess;

import com.avid.ctms.examples.tools.common.PlatformTools;
import com.damnhandy.uri.template.UriTemplate;
import net.sf.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.logging.*;

/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-07-12
 * Time: 12:07
 * Project: CTMS
 */

/**
 * This example starts a process and monitors its progress.
 */
public class StartProcess {
    private static final Logger LOG = Logger.getLogger(StartProcess.class.getName());

    private StartProcess() {
    }

    public static void main(String[] args) throws Exception {
        if (6 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <oauthtoken> <serviceversion> <realm> <username> <password>", StartProcess.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String baseOAuthToken = args[1];
            final String serviceVersion = args[2];
            final String realm = args[3];
            final String username = args[4];
            final String password = args[5];

            final boolean successfullyAuthorized = PlatformTools.authorize(apiDomain, baseOAuthToken, username, password);
            if (successfullyAuthorized) {
                try {
                    final String orchestrationServiceType = "avid.orchestration.ctc";

                    /// Query CTMS Registry:
                    final String registryServiceVersion = "0";
                    final String defaultProcessUriTemplate = String.format("https://%s/apis/%s;version=%s;realm=%s/processes/{id}", apiDomain, orchestrationServiceType, serviceVersion, realm);
                    final List<String> processUriTemplates = PlatformTools.findInRegistry(apiDomain, Collections.singletonList(orchestrationServiceType), registryServiceVersion, "orchestration:process", defaultProcessUriTemplate);

                    /// Create an export process:
                    final String newProcessID = UUID.randomUUID().toString();
                    final String now = PlatformTools.nowFormatted();
                    final String itemToExport = "2016050410152760101291561460050569B02260000003692B00000D0D000005";
                    final String newProcessName = String.format("New process as to %s", now).replace(" ", "_").replace(":", "_").replace("-", "_").replace("+", "_");
                    final String processDescription =
                            "{\"base\":{\"id\":\"" + newProcessID + "\",\"type\":\"MAM_EXPORT_FILE\",\"systemType\":\"interplay-mam\",\"systemID\":\"" + realm + "\"}"
                                    + ",\"common\":{\"name\":\"" + newProcessName + "\",\"creator\":\"Java_Example\",\"created\":\"" + now + "\",\"modifier\":\"Service-WorkflowEngine\",\"modified\":\"" + now + "\"}"
                                    + ",\"attachments\":[{\"base\":{\"id\":\"" + itemToExport + "\",\"type\":\"Asset\",\"systemType\":\"interplay-mam\",\"systemID\":\"" + realm + "\"}}]}";

                    /// Start the process:
                    final UriTemplate processUriTemplate = UriTemplate.fromTemplate(processUriTemplates.get(0));
                    final URL startProcessURL = new URL(processUriTemplate.expand());

                    final HttpURLConnection startProcessConnection = (HttpURLConnection) startProcessURL.openConnection();
                    startProcessConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                    startProcessConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                    startProcessConnection.setRequestMethod("POST");
                    startProcessConnection.setDoOutput(true);
                    startProcessConnection.setRequestProperty("Content-Type", "application/json");
                    startProcessConnection.setRequestProperty("Accept", "application/hal+json");

                    startProcessConnection.getOutputStream().write(processDescription.getBytes());

                    /// Monitor the process:
                    final int startProcessStatus = startProcessConnection.getResponseCode();
                    if (HttpURLConnection.HTTP_OK == startProcessStatus) {
                        // Begin monitoring the started process:
                        String rawStartedProcessResult = PlatformTools.getContent(startProcessConnection);
                        JSONObject startedProcessResult = JSONObject.fromObject(rawStartedProcessResult);
                        final String urlStartedProcess = startedProcessResult.getJSONObject("_links").getJSONObject("self").getString("href");
                        String lifecycle = startedProcessResult.getString("lifecycle");

                        LOG.log(Level.INFO, "Process: {0} - start initiated", newProcessName);
                        LOG.log(Level.INFO, "Lifecycle: {0}", lifecycle);
                        if ("pending".equals(lifecycle) || "running".equals(lifecycle)) {
                            do {
                                Thread.sleep(500);
                                final HttpURLConnection runningProcessConnection = (HttpURLConnection) new URL(urlStartedProcess).openConnection();
                                runningProcessConnection.setConnectTimeout(PlatformTools.getDefaultConnectionTimeoutms());
                                runningProcessConnection.setReadTimeout(PlatformTools.getDefaultReadTimeoutms());
                                rawStartedProcessResult = PlatformTools.getContent(runningProcessConnection);
                                startedProcessResult = JSONObject.fromObject(rawStartedProcessResult);
                                lifecycle = startedProcessResult.getString("lifecycle");

                                LOG.log(Level.INFO, "Lifecycle: {0}", lifecycle);
                            } while ("running".equals(lifecycle) || "pending".equals(lifecycle));
                        }
                    } else {
                        LOG.log(Level.INFO, "Starting process failed with {0}", PlatformTools.getContent(startProcessConnection));
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
