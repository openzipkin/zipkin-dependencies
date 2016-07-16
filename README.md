[![Gitter chat](http://img.shields.io/badge/gitter-join%20chat%20%E2%86%92-brightgreen.svg)](https://gitter.im/openzipkin/zipkin) [![Build Status](https://travis-ci.org/openzipkin/zipkin-dependencies-spark.svg?branch=master)](https://travis-ci.org/openzipkin/zipkin-dependencies-spark) [![Download](https://api.bintray.com/packages/openzipkin/maven/zipkin-dependencies-spark/images/download.svg) ](https://bintray.com/openzipkin/maven/zipkin-dependencies-spark/_latestVersion)

# zipkin-dependencies-spark

This module is a Spark job that will collect spans from your datastore (only Cassandra is supported yet),
analyse them aggregate the data and store it for later presentation in the [web UI](https://github.com/openzipkin/zipkin/tree/master/zipkin-ui) (ex. http://localhost:8080/dependency).

## Running locally

To start a job against against cassandra listening on localhost:9042, in Spark's standalone mode.

```bash
# Build the cassandra job and also make its dependencies
$ ./mvnw -DskipTests --also-make -pl cassandra clean install
# Run the cassandra job
$ java -jar ./cassandra/target/*cassandra-*-all.jar
```

## Configuration

`zipkin-dependencies-spark` applies configuration parameters through environment variables.
Currently, only [cassandra](https://github.com/openzipkin/zipkin/blob/master/zipkin-storage/cassandra/README.md) span storage is supported.

Below are environment variable definitions.

    * `SPARK_MASTER`: Spark master to submit the job to; Defaults to `local[*]`
    * `CASSANDRA_KEYSPACE`: Keyspace zipkin schema exists in; Defaults to `zipkin`
    * `CASSANDRA_USERNAME` and `CASSANDRA_PASSWORD`: Cassandra authentication. Will throw an exception on startup if authentication fails
    * `CASSANDRA_HOST`: A host in your cassandra cluster; Defaults to `127.0.0.1`
    * `CASSANDRA_PORT`: The port of `CASSANDRA_HOST`; Defaults to `9042`

Example usage:

```bash
$ CASSANDRA_USER=user CASSANDRA_PASS=pass java -jar ./cassandra/target/*cassandra-*-all.jar
```
