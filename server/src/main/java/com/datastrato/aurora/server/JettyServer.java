/*
 *  Copyright 2024 Datastrato Pvt Ltd.
 *  This software is licensed under the Apache License version 2.
 */
package com.datastrato.aurora.server;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.servlets.MetricsServlet;
import com.datastrato.aurora.config.IcebergServerConfig;
import com.datastrato.aurora.config.JettyServerConfig;
import com.datastrato.aurora.metrics.MetricsSystem;
import com.google.common.base.Preconditions;
import java.net.BindException;
import java.security.PrivilegedAction;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JettyServer {

  private static final Logger LOG = LoggerFactory.getLogger(JettyServer.class);

  private static final String HTTPS = "https";
  private static final String HTTP_PROTOCOL = "http/1.1";

  private Server server;

  private ServletContextHandler servletContextHandler;

  private JettyServerConfig serverConfig;

  private String serverName;

  public JettyServer() {}

  public synchronized void initialize(
      IcebergServerConfig icebergServerConfig, String serverName, MetricsSystem metricsSystem) {
    this.serverConfig = JettyServerConfig.fromConfig(icebergServerConfig);
    this.serverName = serverName;

    ThreadPool threadPool =
        createThreadPool(
            serverConfig.getMinThreads(),
            serverConfig.getMaxThreads(),
            serverConfig.getThreadPoolWorkQueueSize());

    // Create and config Jetty Server
    server = new Server(threadPool);
    server.setStopAtShutdown(true);
    server.setStopTimeout(serverConfig.getStopTimeout());

    // Set error handler for Jetty Server
    ErrorHandler errorHandler = new ErrorHandler();
    errorHandler.setShowStacks(true);
    errorHandler.setServer(server);
    server.addBean(errorHandler);

    if (serverConfig.isEnableHttps()) {
      // Create and set Https ServerConnector
      Preconditions.checkArgument(
          StringUtils.isNotBlank(serverConfig.getKeyStorePath()),
          "If enables https, must set keyStorePath");
      Preconditions.checkArgument(
          StringUtils.isNotBlank(serverConfig.getKeyStorePassword()),
          "If enables https, must set keyStorePassword");
      Preconditions.checkArgument(
          StringUtils.isNotBlank(serverConfig.getManagerPassword()),
          "If enables https, must set managerPassword");
      if (serverConfig.isEnableClientAuth()) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(serverConfig.getTrustStorePath()),
            "If enables the authentication of the client, must set trustStorePath");
        Preconditions.checkArgument(
            StringUtils.isNotBlank(serverConfig.getTrustStorePassword()),
            "If enables the authentication of the client, must set trustStorePassword");
      }
      ServerConnector httpsConnector =
          createHttpsServerConnector(
              server,
              serverConfig.getRequestHeaderSize(),
              serverConfig.getResponseHeaderSize(),
              serverConfig.getHost(),
              serverConfig.getHttpsPort(),
              serverConfig.getIdleTimeout(),
              serverConfig.getKeyStorePath(),
              serverConfig.getKeyStorePassword(),
              serverConfig.getManagerPassword(),
              serverConfig.getKeyStoreType(),
              serverConfig.getTlsProtocol(),
              serverConfig.getSupportedAlgorithms(),
              serverConfig.isEnableClientAuth(),
              serverConfig.getTrustStorePath(),
              serverConfig.getTrustStorePassword(),
              serverConfig.getTrustStoreType());
      server.addConnector(httpsConnector);
    } else {
      // Create and set Http ServerConnector
      ServerConnector httpConnector =
          createHttpServerConnector(
              server,
              serverConfig.getRequestHeaderSize(),
              serverConfig.getResponseHeaderSize(),
              serverConfig.getHost(),
              serverConfig.getHttpPort(),
              serverConfig.getIdleTimeout());
      server.addConnector(httpConnector);
    }

    initializeBasicServletContextHandler();

    if (metricsSystem != null) {
      MetricRegistry metricRegistry = metricsSystem.getMetricRegistry();
      servletContextHandler.setAttribute(
          "com.codahale.metrics.servlets.MetricsServlet.registry", metricRegistry);
      servletContextHandler.addServlet(MetricsServlet.class, "/metrics");

      servletContextHandler.addServlet(
          new ServletHolder(metricsSystem.getPrometheusServlet()), "/prometheus/metrics");
    }

    HandlerCollection handlers = new HandlerCollection();
    handlers.addHandler(servletContextHandler);
    server.setHandler(handlers);
  }

  public synchronized void start() throws RuntimeException {
    try {
      server.start();
    } catch (BindException e) {
      LOG.error(
          "Failed to start {} web server on host {} port {}, which is already in use.",
          serverName,
          serverConfig.getHost(),
          serverConfig.getHttpPort(),
          e);
      throw new RuntimeException("Failed to start " + serverName + " web server.", e);

    } catch (Exception e) {
      LOG.error("Failed to start {} web server.", serverName, e);
      throw new RuntimeException("Failed to start " + serverName + " web server.", e);
    }

    if (!serverConfig.isEnableHttps()) {
      LOG.warn("Users would better use HTTPS to avoid token data leak.");
    }

    LOG.info(
        "{} web server started on host {} port {}.", serverName, serverConfig.getHost(), getPort());
  }

  public synchronized void join() {
    try {
      server.join();
    } catch (InterruptedException e) {
      LOG.info("Interrupted while {} web server is joining.", serverName);
      Thread.currentThread().interrupt();
    }
  }

  public synchronized void stop() {
    if (server != null) {
      try {
        // Referring from Spark's implementation to avoid the issues.
        ThreadPool threadPool = server.getThreadPool();
        if (threadPool instanceof QueuedThreadPool) {
          ((QueuedThreadPool) threadPool).setStopTimeout(0);
        }

        server.stop();

        if (threadPool instanceof LifeCycle) {
          ((LifeCycle) threadPool).stop();
        }

        LOG.info(
            "{} web server stopped on host {} port {}.",
            serverName,
            serverConfig.getHost(),
            getPort());
      } catch (Exception e) {
        // Swallow the exception.
        LOG.warn("Failed to stop {} web server.", serverName, e);
      }

      server = null;
    }
  }

  public void addServlet(Servlet servlet, String pathSpec) {
    servletContextHandler.addServlet(new ServletHolder(servlet), pathSpec);
  }

  public void addFilter(Filter filter, String pathSpec) {
    servletContextHandler.addFilter(
        new FilterHolder(filter), pathSpec, EnumSet.allOf(DispatcherType.class));
  }

  private void initializeBasicServletContextHandler() {
    servletContextHandler = new ServletContextHandler();
    servletContextHandler.setContextPath("/");
    servletContextHandler.addServlet(DefaultServlet.class, "/");
  }

  private ServerConnector createHttpServerConnector(
      Server server,
      int reqHeaderSize,
      int respHeaderSize,
      String host,
      int port,
      int idleTimeout) {
    HttpConfiguration httpConfig = new HttpConfiguration();
    httpConfig.setRequestHeaderSize(reqHeaderSize);
    httpConfig.setResponseHeaderSize(respHeaderSize);
    httpConfig.setSendServerVersion(true);
    httpConfig.setIdleTimeout(idleTimeout);

    HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfig);
    ServerConnector connector =
        createServerConnector(server, new ConnectionFactory[] {httpConnectionFactory});
    connector.setHost(host);
    connector.setPort(port);
    connector.setReuseAddress(true);

    return connector;
  }

  private int getPort() {
    if (serverConfig.isEnableHttps()) {
      return serverConfig.getHttpsPort();
    } else {
      return serverConfig.getHttpPort();
    }
  }

  @SuppressWarnings("deprecation")
  private ServerConnector createHttpsServerConnector(
      Server server,
      int reqHeaderSize,
      int respHeaderSize,
      String host,
      int port,
      int idleTimeout,
      String keyStorePath,
      String keyStorePassword,
      String keyManagerPassword,
      String keyStoreType,
      Optional<String> tlsProtocol,
      Set<String> supportedAlgorithms,
      boolean isEnableClientAuth,
      String trustStorePath,
      String trustStorePassword,
      String trustStoreType) {
    HttpConfiguration httpConfig = new HttpConfiguration();
    httpConfig.setSecureScheme(HTTPS);
    httpConfig.setRequestHeaderSize(reqHeaderSize);
    httpConfig.setResponseHeaderSize(respHeaderSize);
    httpConfig.setSendServerVersion(true);
    httpConfig.setIdleTimeout(idleTimeout);
    httpConfig.setSecurePort(port);

    SslContextFactory sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(keyStorePath);
    sslContextFactory.setKeyStorePassword(keyStorePassword);
    sslContextFactory.setKeyManagerPassword(keyManagerPassword);
    sslContextFactory.setKeyStoreType(keyStoreType);
    tlsProtocol.ifPresent(sslContextFactory::setProtocol);
    if (!supportedAlgorithms.isEmpty()) {
      sslContextFactory.setIncludeCipherSuites(supportedAlgorithms.toArray(new String[0]));
    }
    if (isEnableClientAuth) {
      sslContextFactory.setNeedClientAuth(true);
      sslContextFactory.setTrustStorePath(trustStorePath);
      sslContextFactory.setTrustStorePassword(trustStorePassword);
      sslContextFactory.setTrustStoreType(trustStoreType);
    }
    SecureRequestCustomizer src = new SecureRequestCustomizer();
    httpConfig.addCustomizer(src);
    HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfig);
    SslConnectionFactory sslConnectionFactory =
        new SslConnectionFactory(sslContextFactory, HTTP_PROTOCOL);
    ServerConnector connector =
        createServerConnector(
            server, new ConnectionFactory[] {sslConnectionFactory, httpConnectionFactory});
    connector.setHost(host);
    connector.setPort(port);
    connector.setReuseAddress(true);
    return connector;
  }

  private ServerConnector createServerConnector(
      Server server, ConnectionFactory[] connectionFactories) {
    Scheduler serverExecutor =
        new ScheduledExecutorScheduler(serverName + "-webserver-JettyScheduler", true);

    return new ServerConnector(server, null, serverExecutor, null, -1, -1, connectionFactories);
  }

  @SuppressWarnings("removal")
  private ThreadPool createThreadPool(int minThreads, int maxThreads, int threadPoolWorkQueueSize) {

    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    QueuedThreadPool threadPool =
        new QueuedThreadPool(
            maxThreads, minThreads, 60000, new LinkedBlockingQueue(threadPoolWorkQueueSize)) {

          @Override
          public Thread newThread(Runnable runnable) {
            return java.security.AccessController.doPrivileged(
                new PrivilegedAction<Thread>() {
                  @Override
                  public Thread run() {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setPriority(getThreadsPriority());
                    thread.setName(getName() + "-" + thread.getId());
                    thread.setUncaughtExceptionHandler(
                        (t, throwable) -> {
                          LOG.error("{} uncaught exception:", t.getName(), throwable);
                        });
                    thread.setContextClassLoader(classLoader);
                    return thread;
                  }
                });
          }
        };
    threadPool.setName(serverName);
    return threadPool;
  }

  public ThreadPool getThreadPool() {
    return server.getThreadPool();
  }

  public void addCustomFilters(String pathSpec) {
    for (String filterName : serverConfig.getCustomFilters()) {
      if (StringUtils.isBlank(filterName)) {
        continue;
      }
      FilterHolder filterHolder = new FilterHolder();
      filterHolder.setClassName(filterName);
      for (Map.Entry<String, String> entry :
          serverConfig.getAllWithPrefix(String.format("%s.param.", filterName)).entrySet()) {
        filterHolder.setInitParameter(entry.getKey(), entry.getValue());
      }
      servletContextHandler.addFilter(filterHolder, pathSpec, EnumSet.allOf(DispatcherType.class));
    }
  }
}
