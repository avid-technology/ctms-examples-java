package com.avid.ctms.examples.filecheckin;

import java.net.*;
import java.util.*;
import java.util.logging.*;

import com.avid.ctms.examples.tools.common.*;
import com.avid.ctms.examples.tools.unirest.PlatformToolsUnirest;
import com.mashape.unirest.http.*;
import org.json.*;


/**
 * Copyright 2013-2017 by Avid Technology, Inc.
 * User: nludwig
 * Date: 2016-11-16
 * Time: 07:36
 * Project: CTMS
 */

/**
 * This example issues a file check in procedure, monitors its progress and modifies the metadata of the created asset
 * (i.e. sets MAM attribute "comment" to the current timestamp).
 */
public class FileCheckIn {
    private static final Logger LOG = Logger.getLogger(FileCheckIn.class.getName());

    private FileCheckIn() {
    }

    public static void main(String[] args) throws Exception {
        if (6 != args.length) {
            LOG.log(Level.INFO, "Usage: {0} <apidomain> <serviceversion> <realm> <username> <password> <sourcepath>", FileCheckIn.class.getSimpleName());
        } else {
            final String apiDomain = args[0];
            final String serviceVersion = args[1];
            final String realm = args[2];
            final String username = args[3];
            final String password = args[4];

            final boolean successfullyAuthorized = PlatformToolsUnirest.authorize(apiDomain, username, password);
            if (successfullyAuthorized) {
                try {
                    final String fileCheckInUriTemplate = String.format("https://%s/apis/avid.mam.assets.access;version=%s;realm=%s/mam/file-check-in", apiDomain, serviceVersion, realm);

                    //final String sourcePath = "\\\\\\\\nas4\\\\MAMSTORE\\\\mam-b\\\\MediaAssetManager\\\\Terminator.jpg";
                    final String sourcePath = args[5];
                    final String body = String.format(
                            "{"
                                    + "\"file\" : \"%s\","
                                    + "\"assetType\" : \"EPISODE\", "
                                    + "\"usage\" : \"browse\", "
                                    + "\"fileType\" : \"image\", "
                                    + "\"carrier\" : \"PROXY\", "
                                    + "\"params\" : {"
                                    + "\"videoAnalysis\" : true, "
                                    + "\"qualityControl\" : true"
                                    + "}"
                                    + "}", sourcePath);

                    final HttpResponse<JsonNode> fileCheckInResponse =
                            Unirest.post(fileCheckInUriTemplate)
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/hal+json")
                                    .body(body)
                                    .asJson();

                    final int fileCheckInStatus = fileCheckInResponse.getStatus();
                    if (HttpURLConnection.HTTP_OK == fileCheckInStatus) {
                        // Monitor the check in procedure using HATEOAS:
                        JsonNode runningFileCheckInProcessDataResult = fileCheckInResponse.getBody();
                        final String id = runningFileCheckInProcessDataResult.getObject().getString("id");
                        String lifeCycle = runningFileCheckInProcessDataResult.getObject().getString("lifecycle");
                        while (!Objects.equals(lifeCycle, "finished")
                                && !Objects.equals(lifeCycle, "failed")
                                && !Objects.equals(lifeCycle, "error")) {
                            LOG.log(Level.INFO, "{0}: {1}", new Object[] {id, lifeCycle});
                            Thread.sleep(500);

                            final HttpResponse<JsonNode> fileCheckInStatusResponse =
                                    Unirest.get(runningFileCheckInProcessDataResult.getObject().getJSONObject("_links").getJSONObject("self").getString("href"))
                                            .header("Accept", "application/hal+json")
                                            .asJson();

                            runningFileCheckInProcessDataResult = fileCheckInStatusResponse.getBody();
                            lifeCycle = runningFileCheckInProcessDataResult.getObject().getString("lifecycle");
                        }

                        // After the monitoring procedure finished, we'll update the just created asset:
                        if (Objects.equals(lifeCycle, "finished")) {
                            final JSONObject embedded = runningFileCheckInProcessDataResult.getObject().getJSONObject("_embedded");
                            if (null != embedded && !JSONObject.NULL.equals(embedded)) {
                                // Check if the embedded asset is present in the response, older versions of that service might not support this.
                                final JSONObject createdAsset = embedded.getJSONObject("aa:asset");
                                if (null != createdAsset && !JSONObject.NULL.equals(createdAsset)) {
                                    final String updateAssetLink = createdAsset.getJSONObject("_links").getJSONObject("ma:update-asset").getString("href");
                                    final String createdAssetsID = embedded.getJSONObject("aa:asset").getJSONObject("base").getString("id");
                                    final String assetPatchDescriptionAXF = String.format(
                                            "{"
                                                    + "\"objects\" : ["
                                                    + "{"
                                                    + "\"assetId\" : \"%s\", " // It is required to specify the assetId.
                                                    + "\"attributes\" : ["
                                                    + "{"
                                                    + "\"name\" : \"COMMENT\","
                                                    + "\"value\" : \"'%s' - updated on %s\""
                                                    + "}"
                                                    + "]"
                                                    + "}"
                                                    + "]"
                                                    + "}", createdAssetsID, "comment", new Date());

                                    final HttpResponse<JsonNode> assetUpdateResponse =
                                            Unirest.patch(updateAssetLink)
                                                    .header("Content-Type", "application/vnd.com.avid.mam.axf+json")
                                                    .body(assetPatchDescriptionAXF)
                                                    .asJson();

                                    final int assetUpdateStatus = assetUpdateResponse.getStatus();
                                    if (2 != assetUpdateStatus / 100) {
                                        LOG.log(Level.INFO, "Update metadata failed with: {0}", assetUpdateStatus);
                                    }
                                }
                            } else {
                                LOG.log(Level.INFO, "This version of MAMAssetsCTC doesn't support getting the file check in status along with the id of the created asset.");
                            }
                        } else {
                            LOG.log(Level.INFO, "File check in failed with: {0}", lifeCycle);
                        }
                    } else {
                        LOG.log(Level.INFO, "File check in not supported! -> {0}", fileCheckInResponse.getBody());
                    }
                } catch (final Exception exception) {
                    LOG.log(Level.SEVERE, "failure", exception);
                } finally {
                    PlatformToolsUnirest.logout(apiDomain);
                }
            } else {
                LOG.log(Level.INFO, "Authorization failed.");
            }
        }

        LOG.log(Level.INFO, "End");
    }
}