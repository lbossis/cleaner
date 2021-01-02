/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.cleaner.auth;

import io.prometheus.client.Counter;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.util.BasicAuthHelper;
import org.keycloak.util.JsonSerialization;

import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.jboss.pnc.cleaner.auth.keycloakutil.util.HttpUtil.APPLICATION_FORM_URL_ENCODED;
import static org.jboss.pnc.cleaner.auth.keycloakutil.util.HttpUtil.doPost;
import static org.jboss.pnc.cleaner.auth.keycloakutil.util.HttpUtil.setSslRequired;
import static org.jboss.pnc.cleaner.auth.keycloakutil.util.HttpUtil.urlencode;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
class KeycloakClient {

    static final Counter exceptionsTotal = Counter.build()
            .name("KeycloakClient_Exceptions_Total")
            .help("Errors and Warnings counting metric")
            .labelNames("severity")
            .register();

    static AccessTokenResponse getAuthTokensBySecret(
            String server,
            String realm,
            String clientId,
            String secret,
            boolean sslRequired) {
        return getAuthTokensBySecret(server, realm, null, null, clientId, secret, sslRequired);
    }

    static AccessTokenResponse getAuthTokensBySecret(
            String server,
            String realm,
            String user,
            String password,
            String clientId,
            String secret,
            boolean sslRequired) {
        StringBuilder body = new StringBuilder();
        try {
            if (user != null) {
                if (password == null) {
                    exceptionsTotal.labels("error").inc();
                    throw new RuntimeException("No password specified");
                }

                body.append("client_id=")
                        .append(urlencode(clientId))
                        .append("&grant_type=password")
                        .append("&username=")
                        .append(urlencode(user))
                        .append("&password=")
                        .append(urlencode(password));
            } else {
                body.append("grant_type=client_credentials");
            }

            setSslRequired(sslRequired);
            InputStream result = doPost(
                    server + "/realms/" + realm + "/protocol/openid-connect/token",
                    APPLICATION_FORM_URL_ENCODED,
                    APPLICATION_JSON,
                    body.toString(),
                    BasicAuthHelper.createHeader(clientId, secret));
            return JsonSerialization.readValue(result, AccessTokenResponse.class);

        } catch (UnsupportedEncodingException e) {
            exceptionsTotal.labels("error").inc();
            throw new RuntimeException("Unexpected error: ", e);
        } catch (IOException e) {
            exceptionsTotal.labels("error").inc();
            throw new RuntimeException("Error receiving response: ", e);
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Gauge(name = "KeycloakClient_Err_Count", unit = MetricUnits.NONE, description = "Errors count")
    public int showCurrentErrCount() {
        return (int) exceptionsTotal.labels("error").get();
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Gauge(name = "KeycloakClient_Warn_Count", unit = MetricUnits.NONE, description = "Warnings count")
    public int showCurrentWarnCount() {
        return (int) exceptionsTotal.labels("warning").get();
    }
}
