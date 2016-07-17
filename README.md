[![Gitter chat](http://img.shields.io/badge/gitter-join%20chat%20%E2%86%92-brightgreen.svg)](https://gitter.im/openzipkin/zipkin) [![Build Status](https://travis-ci.org/openzipkin/zipkin-dependencies-spark.svg?branch=master)](https://travis-ci.org/openzipkin/zipkin-dependencies-spark) [![Download](https://api.bintray.com/packages/openzipkin/maven/zipkin-dependencies-spark/images/download.svg) ](https://bintray.com/openzipkin/maven/zipkin-dependencies-spark/_latestVersion)

# zipkin-dependencies-spark

This is a Spark job that will collect spans from your datastore, analyze links between services,
and store them for later presentation in the [web UI](https://github.com/openzipkin/zipkin/tree/master/zipkin-ui) (ex. http://localhost:8080/dependency).

This job parses all traces in the current day in UTC time. This means you should schedule it to run
just prior to midnight UTC.

Currently, Zipkin [Cassandra](https://github.com/openzipkin/zipkin/blob/master/zipkin-storage/cassandra/README.md). 
Elasticsearch and MySQL will be added shortly.

## Running locally

To start a job against against a local datastore, in Spark's standalone mode.

```bash
# Build the spark jobs
$ ./mvnw -DskipTests clean install
# Run the Cassandra job
$ java -jar ./cassandra/target/zipkin-dependencies*-all.jar
```

## Environment Variables
`zipkin-dependencies-spark` applies configuration parameters through environment variables. At the
moment, separate binaries are made for each storage layer.

The following variables are common to all storage layers:
    * `SPARK_MASTER`: Spark master to submit the job to; Defaults to `local[*]`

### Cassandra
The cassandra binary is compatible with Zipkin's [Cassandra storage component](https://github.com/openzipkin/zipkin/tree/master/zipkin-storage/cassandra).

    * `CASSANDRA_KEYSPACE`: The keyspace to use. Defaults to "zipkin".
    * `CASSANDRA_USERNAME` and `CASSANDRA_PASSWORD`: Cassandra authentication. Will throw an exception on startup if authentication fails
    * `CASSANDRA_HOST`: A host in your cassandra cluster; Defaults to `127.0.0.1`
    * `CASSANDRA_PORT`: The port of `CASSANDRA_HOST`; Defaults to `9042`

Example usage:

```bash
$ CASSANDRA_USER=user CASSANDRA_PASS=pass java -jar ./cassandra/target/zipkin-dependencies*-all.jar
```
