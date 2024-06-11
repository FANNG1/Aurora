/*
 *  Copyright 2023 Datastrato Pvt Ltd.
 *  This software is licensed under the Apache License version 2.
 */
package com.datastrato.aurora.config;

import com.datastrato.aurora.web.metrics.IcebergMetricsManager;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class IcebergServerConfig extends Config {

  public static final ConfigEntry<Integer> SERVER_SHUTDOWN_TIMEOUT =
      new ConfigBuilder("server.shutdown.timeout")
          .doc("The stop idle timeout(millis) of the Iceberg REST server")
          .version("0.1.0")
          .intConf()
          .checkValue(value -> value > 0, ConfigConstants.POSITIVE_NUMBER_ERROR_MSG)
          .createWithDefault(3 * 1000);

  public static final ConfigEntry<String> CATALOG_BACKEND =
      new ConfigBuilder("catalog-backend")
          .doc("Catalog backend of Gravitino Iceberg catalog")
          .version(ConfigConstants.VERSION_0_1_0)
          .stringConf()
          .createWithDefault("memory");

  public static final ConfigEntry<String> CATALOG_WAREHOUSE =
      new ConfigBuilder("warehouse")
          .doc("Warehouse directory of catalog")
          .version(ConfigConstants.VERSION_0_1_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG)
          .create();

  public static final ConfigEntry<String> CATALOG_URI =
      new ConfigBuilder("uri")
          .doc("The uri config of the Iceberg catalog")
          .version(ConfigConstants.VERSION_0_1_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG)
          .create();

  public static final ConfigEntry<String> JDBC_USER =
      new ConfigBuilder("jdbc-user")
          .doc("The username of the Jdbc connection")
          .version(ConfigConstants.VERSION_0_1_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG)
          .create();

  public static final ConfigEntry<String> JDBC_PASSWORD =
      new ConfigBuilder("jdbc-passwd")
          .doc("The password of the Jdbc connection")
          .version(ConfigConstants.VERSION_0_1_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG)
          .create();

  public static final ConfigEntry<String> JDBC_DRIVER =
      new ConfigBuilder("jdbc-driver")
          .doc("The driver of the Jdbc connection")
          .version(ConfigConstants.VERSION_0_1_0)
          .stringConf()
          .checkValue(StringUtils::isNotBlank, ConfigConstants.NOT_BLANK_ERROR_MSG)
          .create();

  public static final ConfigEntry<Boolean> JDBC_INIT_TABLES =
      new ConfigBuilder("initial")
          .doc("Whether to initialize meta tables when create Jdbc catalog")
          .version(ConfigConstants.VERSION_0_1_0)
          .booleanConf()
          .createWithDefault(true);

  public static final ConfigEntry<String> ICEBERG_METRICS_STORE =
      new ConfigBuilder(IcebergMetricsManager.ICEBERG_METRICS_STORE)
          .doc("The store to save Iceberg metrics")
          .version(ConfigConstants.VERSION_0_1_0)
          .stringConf()
          .create();

  public static final ConfigEntry<Integer> ICEBERG_METRICS_STORE_RETAIN_DAYS =
      new ConfigBuilder(IcebergMetricsManager.ICEBERG_METRICS_STORE_RETAIN_DAYS)
          .doc(
              "The retain days of Iceberg metrics, the value not greater than 0 means retain forever")
          .version(ConfigConstants.VERSION_0_1_0)
          .intConf()
          .createWithDefault(-1);

  public static final ConfigEntry<Integer> ICEBERG_METRICS_QUEUE_CAPACITY =
      new ConfigBuilder(IcebergMetricsManager.ICEBERG_METRICS_QUEUE_CAPACITY)
          .doc("The capacity for Iceberg metrics queues, should greater than 0")
          .version(ConfigConstants.VERSION_0_1_0)
          .intConf()
          .checkValue(value -> value > 0, ConfigConstants.POSITIVE_NUMBER_ERROR_MSG)
          .createWithDefault(1000);

  public String getJdbcDriver() {
    return get(JDBC_DRIVER);
  }

  public IcebergServerConfig(Map<String, String> properties) {
    super(false);
    loadFromMap(properties, k -> true);
  }

  public IcebergServerConfig() {
    super(false);
  }
}
