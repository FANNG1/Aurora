/*
 *  Copyright 2023 Datastrato Pvt Ltd.
 *  This software is licensed under the Apache License version 2.
 */
package com.datastrato.aurora.server;

import com.datastrato.aurora.iceberg.IcebergConfig;
import com.datastrato.aurora.iceberg.IcebergTableOps;
import com.datastrato.aurora.web.IcebergExceptionMapper;
import com.datastrato.aurora.web.IcebergObjectMapperProvider;
import com.datastrato.aurora.web.metrics.IcebergMetricsManager;
import com.datastrato.gravitino.metrics.MetricsSystem;
import com.datastrato.gravitino.metrics.source.JVMMetricsSource;
import com.datastrato.gravitino.metrics.source.MetricsSource;
import com.datastrato.gravitino.server.web.HttpServerMetricsSource;
import com.datastrato.gravitino.server.web.JettyServer;
import com.datastrato.gravitino.server.web.JettyServerConfig;
import java.io.File;
import java.util.Properties;
import javax.servlet.Servlet;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergRESTServer extends ResourceConfig {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergRESTServer.class);

  public static final String CONF_FILE = "aurora.conf";

  private final ServerConfig serverConfig;

  private final JettyServer server;

  private final MetricsSystem metricsSystem;

  public static final String SERVICE_NAME = "iceberg-rest";
  public static final String ICEBERG_SPEC = "/iceberg/*";

  private IcebergTableOps icebergTableOps;
  private IcebergMetricsManager icebergMetricsManager;

  public IcebergRESTServer(ServerConfig config) {
    this.serverConfig = config;
    this.server = new JettyServer();
    this.metricsSystem = new MetricsSystem();
  }

  public void initialize(IcebergConfig icebergConfig) {
    JettyServerConfig serverConfig = JettyServerConfig.fromConfig(icebergConfig);
    server.initialize(serverConfig, SERVICE_NAME, false /* shouldEnableUI */);

    metricsSystem.register(new JVMMetricsSource());

    ResourceConfig config = new ResourceConfig();
    config.packages("com.datastrato.aurora.web.rest");

    config.register(IcebergObjectMapperProvider.class).register(JacksonFeature.class);
    config.register(IcebergExceptionMapper.class);
    HttpServerMetricsSource httpServerMetricsSource =
        new HttpServerMetricsSource(MetricsSource.ICEBERG_REST_SERVER_METRIC_NAME, config, server);
    metricsSystem.register(httpServerMetricsSource);

    icebergTableOps = new IcebergTableOps(icebergConfig);
    icebergMetricsManager = new IcebergMetricsManager(icebergConfig);
    config.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(icebergTableOps).to(IcebergTableOps.class).ranked(1);
            bind(icebergMetricsManager).to(IcebergMetricsManager.class).ranked(1);
          }
        });

    Servlet servlet = new ServletContainer(config);
    server.addServlet(servlet, ICEBERG_SPEC);
    server.addCustomFilters(ICEBERG_SPEC);
    server.addSystemFilters(ICEBERG_SPEC);
  }

  public void start() {
    icebergMetricsManager.start();
    if (server != null) {
      try {
        server.start();
        LOG.info("Iceberg REST service started");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public void join() {
    server.join();
  }

  public void stop() throws Exception {
    if (server != null) {
      server.stop();
      LOG.info("Iceberg REST service stopped");
    }
    if (icebergTableOps != null) {
      icebergTableOps.close();
    }
    if (icebergMetricsManager != null) {
      icebergMetricsManager.close();
    }
  }

  public static void main(String[] args) {
    LOG.info("Starting Iceberg REST Server");
    String confPath = System.getenv("GRAVITINO_TEST") == null ? "" : args[0];
    ServerConfig serverConfig = loadConfig(confPath);
    IcebergRESTServer server = new IcebergRESTServer(serverConfig);
    IcebergConfig config = new IcebergConfig();
    server.initialize(config);

    try {
      server.start();
    } catch (Exception e) {
      LOG.error("Error while running jettyServer", e);
      System.exit(-1);
    }
    LOG.info("Done, Gravitino server started.");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    // Register some clean-up tasks that need to be done before shutting down
                    Thread.sleep(server.serverConfig.get(ServerConfig.SERVER_SHUTDOWN_TIMEOUT));
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("Interrupted exception:", e);
                  } catch (Exception e) {
                    LOG.error("Error while running clean-up tasks in shutdown hook", e);
                  }
                }));

    server.join();

    LOG.info("Shutting down Gravitino Server ... ");
    try {
      server.stop();
      LOG.info("Gravitino Server has shut down.");
    } catch (Exception e) {
      LOG.error("Error while stopping Gravitino Server", e);
    }
  }

  static ServerConfig loadConfig(String confPath) {
    ServerConfig serverConfig = new ServerConfig();
    try {
      if (confPath.isEmpty()) {
        // Load default conf
        serverConfig.loadFromFile(CONF_FILE);
      } else {
        Properties properties = serverConfig.loadPropertiesFromFile(new File(confPath));
        serverConfig.loadFromProperties(properties);
      }
    } catch (Exception exception) {
      throw new IllegalArgumentException("Failed to load conf from file " + confPath, exception);
    }
    return serverConfig;
  }
}
