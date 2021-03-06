/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.engine.controller;


import ai.grakn.GraknTx;
import ai.grakn.engine.GraknConfig;
import ai.grakn.engine.GraknEngineStatus;
import ai.grakn.engine.SystemKeyspace;
import ai.grakn.engine.controller.response.Keyspace;
import ai.grakn.engine.controller.response.Keyspaces;
import ai.grakn.engine.controller.util.Requests;
import ai.grakn.exception.GraknServerException;
import ai.grakn.util.GraknVersion;
import ai.grakn.util.REST;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.dropwizard.DropwizardExports;
import io.prometheus.client.exporter.common.TextFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ai.grakn.util.REST.Request.FORMAT;
import static ai.grakn.util.REST.Request.KEYSPACE_PARAM;
import static ai.grakn.util.REST.Response.ContentType.APPLICATION_JSON;
import static org.apache.http.HttpHeaders.CACHE_CONTROL;


/**
 * <p> Controller Providing Configs for building Grakn Graphs </p>
 *
 * <p> When calling {@link ai.grakn.Grakn#session(String, String)} and using the non memory location
 * this controller is accessed. The controller provides the necessary config needed in order to
 * build a {@link GraknTx}.
 *
 * This controller also allows the retrieval of all {@link ai.grakn.Keyspace}s opened so far. </p>
 *
 * @author Filipe Peliz Pinto Teixeira
 */
public class SystemController {

    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4";
    private static final String PROMETHEUS = "prometheus";
    private static final String JSON = "json";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Logger LOG = LoggerFactory.getLogger(SystemController.class);
    private final GraknEngineStatus graknEngineStatus;
    private final MetricRegistry metricRegistry;
    private final ObjectMapper mapper;
    private final CollectorRegistry prometheusRegistry;
    private final SystemKeyspace systemKeyspace;
    private final GraknConfig config;

    public SystemController(Service spark, GraknConfig config, SystemKeyspace systemKeyspace,
                            GraknEngineStatus graknEngineStatus, MetricRegistry metricRegistry) {
        this.systemKeyspace = systemKeyspace;
        this.config = config;

        this.graknEngineStatus = graknEngineStatus;
        this.metricRegistry = metricRegistry;
        DropwizardExports prometheusMetricWrapper = new DropwizardExports(metricRegistry);
        this.prometheusRegistry = new CollectorRegistry();
        prometheusRegistry.register(prometheusMetricWrapper);

        spark.get(REST.WebPath.KB, (req, res) -> getKeyspaces(res));
        spark.get(REST.WebPath.KB_KEYSPACE, this::getKeyspace);
        spark.put(REST.WebPath.KB_KEYSPACE, this::putKeyspace);
        spark.delete(REST.WebPath.KB_KEYSPACE, this::deleteKeyspace);
        spark.get(REST.WebPath.METRICS, this::getMetrics);
        spark.get(REST.WebPath.STATUS, (req, res) -> getStatus());
        spark.get(REST.WebPath.VERSION, (req, res) -> getVersion());

        final TimeUnit rateUnit = TimeUnit.SECONDS;
        final TimeUnit durationUnit = TimeUnit.SECONDS;
        final boolean showSamples = false;
        MetricFilter filter = MetricFilter.ALL;

        this.mapper = new ObjectMapper().registerModule(
                new MetricsModule(rateUnit,
                        durationUnit,
                        showSamples,
                        filter));
    }

    @GET
    @Path(REST.WebPath.VERSION)
    private String getVersion() {
        return GraknVersion.VERSION;
    }

    @GET
    @Path(REST.WebPath.KB)
    private String getKeyspaces(Response response) throws JsonProcessingException {
        response.type(APPLICATION_JSON);
        Set<Keyspace> keyspaces = systemKeyspace.keyspaces().stream().
                map(Keyspace::of).
                collect(Collectors.toSet());
        return objectMapper.writeValueAsString(Keyspaces.of(keyspaces));
    }

    @GET
    @Path("/kb/{keyspace}")
    private String getKeyspace(Request request, Response response) throws JsonProcessingException {
        response.type(APPLICATION_JSON);
        ai.grakn.Keyspace keyspace = ai.grakn.Keyspace.of(Requests.mandatoryPathParameter(request, KEYSPACE_PARAM));

        if (systemKeyspace.containsKeyspace(keyspace)) {
            response.status(HttpServletResponse.SC_OK);
            return objectMapper.writeValueAsString(Keyspace.of(keyspace));
        } else {
            response.status(HttpServletResponse.SC_NOT_FOUND);
            return "";
        }
    }

    @PUT
    @Path("/kb/{keyspace}")
    private String putKeyspace(Request request, Response response) throws JsonProcessingException {
        ai.grakn.Keyspace keyspace = ai.grakn.Keyspace.of(Requests.mandatoryPathParameter(request, KEYSPACE_PARAM));
        systemKeyspace.openKeyspace(keyspace);
        response.status(HttpServletResponse.SC_OK);
        return objectMapper.writeValueAsString(config);
    }

    @DELETE
    @Path("/kb/{keyspace}")
    private boolean deleteKeyspace(Request request, Response response) {
        ai.grakn.Keyspace keyspace = ai.grakn.Keyspace.of(Requests.mandatoryPathParameter(request, KEYSPACE_PARAM));
        boolean deletionComplete = systemKeyspace.deleteKeyspace(keyspace);
        if (deletionComplete) {
            LOG.info("Keyspace {} deleted", keyspace);
            response.status(HttpServletResponse.SC_NO_CONTENT);
            return true;
        } else {
            throw GraknServerException.couldNotDelete(keyspace);
        }
    }

    @GET
    @Path("/status")
    private String getStatus() {
        return graknEngineStatus.isReady() ? "READY" : "INITIALIZING";
    }

    @GET
    @Path("/metrics")
    private String getMetrics(Request request, Response response) throws IOException {
        response.header(CACHE_CONTROL, "must-revalidate,no-cache,no-store");
        response.status(HttpServletResponse.SC_OK);
        Optional<String> format = Optional.ofNullable(request.queryParams(FORMAT));
        String dFormat = format.orElse(JSON);
        switch (dFormat) {
            case PROMETHEUS:
                // Prometheus format for the metrics
                response.type(PROMETHEUS_CONTENT_TYPE);
                final Writer writer1 = new StringWriter();
                TextFormat.write004(writer1, this.prometheusRegistry.metricFamilySamples());
                return writer1.toString();
            case JSON:
                // Json/Dropwizard format
                response.type(APPLICATION_JSON);
                final ObjectWriter writer = mapper.writer();
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    writer.writeValue(output, this.metricRegistry);
                    return new String(output.toByteArray(), "UTF-8");
                }
            default:
                throw GraknServerException.requestInvalidParameter(FORMAT, dFormat);
        }
    }

}
