/*
 *  Copyright 2023 Datastrato Pvt Ltd.
 *  This software is licensed under the Apache License version 2.
 */
package com.datastrato.aurora.server;

import com.datastrato.gravitino.GravitinoEnv;
import com.datastrato.gravitino.server.authentication.ServerAuthenticator;
import com.datastrato.gravitino.server.web.JettyServer;
import com.datastrato.gravitino.server.web.JettyServerConfig;
import java.io.File;
import java.util.Properties;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergRESTServer extends ResourceConfig {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergRESTServer.class);

  public static final String CONF_FILE = "gravitino.conf";

  public static final String WEBSERVER_CONF_PREFIX = "gravitino.server.webserver.";

  public static final String SERVER_NAME = "Gravitino-webserver";

  private final ServerConfig serverConfig;

  private final JettyServer server;

  private final GravitinoEnv gravitinoEnv;

  public IcebergRESTServer(ServerConfig config) {
    serverConfig = config;
    server = new JettyServer();
    gravitinoEnv = GravitinoEnv.getInstance();
  }

  public void initialize() {
    gravitinoEnv.initialize(serverConfig);

    JettyServerConfig jettyServerConfig =
        JettyServerConfig.fromConfig(serverConfig, WEBSERVER_CONF_PREFIX);
    server.initialize(jettyServerConfig, SERVER_NAME, true /* shouldEnableUI */);

    ServerAuthenticator.getInstance().initialize(serverConfig);

    // initialize Jersey REST API resources.
    initializeRestApi();
  }

  private void initializeRestApi() {}

  public void start() throws Exception {
    gravitinoEnv.start();
    server.start();
  }

  public void join() {
    server.join();
  }

  public void stop() {
    server.stop();
    gravitinoEnv.shutdown();
  }

  public static void main(String[] args) {
    LOG.info("Starting Iceberg REST Server");
    String confPath = System.getenv("GRAVITINO_TEST") == null ? "" : args[0];
    ServerConfig serverConfig = loadConfig(confPath);
    IcebergRESTServer server = new IcebergRESTServer(serverConfig);
    server.initialize();

    try {
      // Instantiates GravitinoServer
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
