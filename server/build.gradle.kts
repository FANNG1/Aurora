/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

dependencies {
  implementation(libs.bundles.jetty)
  implementation(libs.bundles.jersey)
  implementation(libs.bundles.log4j)
  implementation(libs.bundles.gravitino)
  implementation(libs.bundles.metrics)
  implementation(libs.bundles.prometheus)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.jackson.annotations)
  implementation(libs.jackson.datatype.jdk8)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.jackson.databind)

  // As of Java 9 or newer, the javax.activation package (needed by the jetty server) is no longer part of the JDK. It was removed because it was part of the
  // JavaBeans Activation Framework (JAF) which has been removed from Java SE. So we need to add it as a dependency. For more,
  // please see: https://stackoverflow.com/questions/46493613/what-is-the-replacement-for-javax-activation-package-in-java-9
  implementation(libs.sun.activation)
  implementation(libs.bundles.iceberg)
  implementation(libs.bundles.jetty)
  implementation(libs.bundles.jersey)
  implementation(libs.bundles.log4j)
  implementation(libs.commons.collections4)
  implementation(libs.commons.io)
  implementation(libs.commons.lang3)
  implementation(libs.guava)
  implementation(libs.hive2.metastore) {
    exclude("co.cask.tephra")
    exclude("com.github.spotbugs")
    exclude("com.google.code.findbugs", "jsr305")
    exclude("com.tdunning", "json")
    exclude("javax.transaction", "transaction-api")
    exclude("org.apache.avro", "avro")
    exclude("org.apache.hbase")
    exclude("org.apache.hadoop", "hadoop-yarn-api")
    exclude("org.apache.hadoop", "hadoop-yarn-server-applicationhistoryservice")
    exclude("org.apache.hadoop", "hadoop-yarn-server-common")
    exclude("org.apache.hadoop", "hadoop-yarn-server-resourcemanager")
    exclude("org.apache.hadoop", "hadoop-yarn-server-web-proxy")
    exclude("org.apache.logging.log4j")
    exclude("org.apache.parquet", "parquet-hadoop-bundle")
    exclude("org.apache.zookeeper")
    exclude("org.eclipse.jetty.aggregate", "jetty-all")
    exclude("org.eclipse.jetty.orbit", "javax.servlet")
    exclude("org.pentaho") // missing dependency
    exclude("org.slf4j", "slf4j-log4j12")
    exclude("com.zaxxer", "HikariCP")
    exclude("com.sun.jersey", "jersey-server")
  }
  implementation(libs.iceberg.hive.metastore)
  implementation(libs.jackson.annotations)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jdk8)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.sqlite.jdbc)

  annotationProcessor(libs.lombok)

  compileOnly(libs.lombok)

  implementation(libs.hadoop2.common) {
    exclude("com.github.spotbugs")
  }
  implementation(libs.hadoop2.hdfs)
  implementation(libs.hadoop2.mapreduce.client.core)

  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)
  testAnnotationProcessor(libs.lombok)
  testCompileOnly(libs.lombok)

  testImplementation(libs.commons.io)
  testImplementation(libs.jersey.test.framework.core) {
    exclude(group = "org.junit.jupiter")
  }
  testImplementation(libs.jersey.test.framework.provider.jetty) {
    exclude(group = "org.junit.jupiter")
  }

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.mockito.core)
  testImplementation(libs.testcontainers)

  testImplementation("org.apache.iceberg:iceberg-spark-runtime-2.12_3.4:1.4.3")
  // testImplementation("org.apache.spark:spark-hive_2.12:3.4.1")
  testImplementation("org.apache.spark:spark-sql_2.12:3.4.1") {
    exclude("org.apache.avro")
    exclude("org.apache.hadoop")
    exclude("org.apache.zookeeper")
    exclude("io.dropwizard.metrics")
    exclude("org.rocksdb")
  }

  testRuntimeOnly(libs.junit.jupiter.engine)
}
