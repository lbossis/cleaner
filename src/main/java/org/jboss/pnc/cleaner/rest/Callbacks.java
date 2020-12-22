/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2019 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.cleaner.rest;

import org.eclipse.microprofile.metrics.annotation.Counted;
import org.jboss.pnc.cleaner.common.LatencyMap;
import org.jboss.pnc.cleaner.common.LatencyMiniMax;
import org.jboss.pnc.cleaner.temporaryBuilds.BuildDeleteCallbackManager;
import org.jboss.pnc.cleaner.temporaryBuilds.BuildGroupDeleteCallbackManager;
import org.jboss.pnc.dto.response.DeleteOperationResult;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;

/**
 * @author Jakub Bartecek
 */
@Path("/callbacks")
public class Callbacks {

    @Inject
    BuildDeleteCallbackManager buildDeleteCallbackManager;

    @Inject
    BuildGroupDeleteCallbackManager buildGroupDeleteCallbackManager;

    static final Map<String, LatencyMiniMax> methodLatencyMap = LatencyMap.getInstance().getMethodLatencyMap();

    @Path("/delete/builds/{buildId}")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response buildRecordDeleteCallback(
            @PathParam("buildId") String buildId,
            DeleteOperationResult deleteOperation) {
        buildDeleteCallbackManager.callback(buildId, deleteOperation);
        return Response.ok().build();
    }

    @Path("/delete/group-builds/{buildId}")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response buildGroupRecordDeleteCallback(
            @PathParam("buildId") String buildId,
            DeleteOperationResult deleteOperation) {
        buildGroupDeleteCallbackManager.callback(buildId, deleteOperation);
        return Response.ok().build();
    }

    @Path("/latency/{clazz}/{method}")
    @GET
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    @Counted(name = "showLatency", description = "current low/high latencies for a given class method")
    public String showLatency(
            @org.jboss.resteasy.annotations.jaxrs.PathParam String clazz,
            @org.jboss.resteasy.annotations.jaxrs.PathParam String method) {
        String classMethod = clazz + "_" + method;
        LatencyMiniMax latencyMiniMax = methodLatencyMap.get(classMethod);
        if (latencyMiniMax != null) {
            return classMethod + " " + latencyMiniMax.toString();
        } else {
            return "Latency map key not found for method '" + classMethod + "'";
        }
    }
}
