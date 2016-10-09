[![Gitter chat](http://img.shields.io/badge/gitter-join%20chat%20%E2%86%92-brightgreen.svg)](https://gitter.im/openzipkin/zipkin) [![Build Status](https://travis-ci.org/openzipkin/zipkin-dependencies.svg?branch=master)](https://travis-ci.org/openzipkin/zipkin-dependencies) [![Download](https://api.bintray.com/packages/openzipkin/maven/zipkin-dependencies/images/download.svg) ](https://bintray.com/openzipkin/maven/zipkin-dependencies/_latestVersion)

# zipkin-dependencies

This is a Spark job that will collect spans from your datastore, analyze links between services,
and store them for later presentation in the [web UI](https://github.com/openzipkin/zipkin/tree/master/zipkin-ui) (ex. http://localhost:8080/dependency).

This job parses all traces in the current day in UTC time. This means you should schedule it to run
just prior to midnight UTC.

All Zipkin [Storage Components](https://github.com/openzipkin/zipkin/blob/master/zipkin-storage/)
are supported, including Cassandra, MySQL and Elasticsearch.

## Quick-start

The quickest way to get started is to fetch the [latest released job](https://search.maven.org/remote_content?g=io.zipkin.dependencies&a=zipkin-dependencies&v=LATEST) as a self-contained jar.
Note that Zipkin Dependencies requires minimum JRE 7. For example:

```bash
$ wget -O zipkin-dependencies.jar 'https://search.maven.org/remote_content?g=io.zipkin.dependencies&a=zipkin-dependencies&v=LATEST'
$ STORAGE_TYPE=cassandra java -jar zipkin-dependencies.jar
```

You can also start Zipkin Dependencies via [Docker](https://github.com/openzipkin/docker-zipkin-dependencies).
```bash
$ docker run --env STORAGE_TYPE=cassandra --env CASSANDRA_CONTACT_POINTS=host1,host2 openzipkin/zipkin-dependencies
```

## Usage

By default, this job parses all traces since midnight UTC. You can parse traces for a different day
via an argument in YYYY-mm-dd format, like 2016-07-16.

```bash
# ex to run the job to process yesterday's traces on OS/X
$ STORAGE_TYPE=cassandra java -jar zipkin-dependencies.jar `date -uv-1d +%F`
# or on Linux
$ STORAGE_TYPE=cassandra java -jar zipkin-dependencies.jar `date -u -d '1 day ago' +%F`
```

## Environment Variables
`zipkin-dependencies` applies configuration parameters through environment variables. At the
moment, separate binaries are made for each storage layer.

The following variables are common to all storage layers:
    * `SPARK_MASTER`: Spark master to submit the job to; Defaults to `local[*]`

### Cassandra
Cassandra is used when `STORAGE_TYPE=cassandra`. The schema is compatible with Zipkin's [Cassandra storage component](https://github.com/openzipkin/zipkin/tree/master/zipkin-storage/cassandra).

    * `CASSANDRA_KEYSPACE`: The keyspace to use. Defaults to "zipkin".
    * `CASSANDRA_CONTACT_POINTS`: Comma separated list of hosts / ip addresses part of Cassandra cluster. Defaults to localhost
    * `CASSANDRA_USERNAME` and `CASSANDRA_PASSWORD`: Cassandra authentication. Will throw an exception on startup if authentication fails

Example usage:

```bash
$ STORAGE_TYPE=cassandra CASSANDRA_USERNAME=user CASSANDRA_PASSWORD=pass java -jar zipkin-dependencies.jar
```

### MySQL Storage
MySQL is used when `STORAGE_TYPE=mysql`. The schema is compatible with Zipkin's [MySQL storage component](https://github.com/openzipkin/zipkin/tree/master/zipkin-storage/mysql).

    * `MYSQL_DB`: The database to use. Defaults to "zipkin".
    * `MYSQL_USER` and `MYSQL_PASS`: MySQL authentication, which defaults to empty string.
    * `MYSQL_HOST`: Defaults to localhost
    * `MYSQL_TCP_PORT`: Defaults to 3306
    * `MYSQL_USE_SSL`: Requires `javax.net.ssl.trustStore` and `javax.net.ssl.trustStorePassword`, defaults to false.

Example usage:

```bash
$ STORAGE_TYPE=mysql MYSQL_USER=root java -jar zipkin-dependencies.jar
```

### Elasticsearch Storage
Elasticsearch is used when `STORAGE_TYPE=elasticsearch`. The schema is compatible with Zipkin's [Elasticsearch storage component](https://github.com/openzipkin/zipkin/tree/master/zipkin-storage/elasticsearch).

    * `ES_INDEX`: The index prefix to use when generating daily index names. Defaults to zipkin.
    * `ES_HOSTS`: A comma separated list of elasticsearch hosts advertising http. Defaults to
                  localhost. Add port section if not listening on port 9200. Only one of these hosts
                  needs to be available to fetch the remaining nodes in the cluster. It is
                  recommended to set this to all the master nodes of the cluster.
    * `ES_NODES_WAN_ONLY`: Set to true to only use the values set in ES_HOSTS, for example if your
                           elasticsearch cluster is in Docker. Defaults to false

Example usage:

```bash
$ STORAGE_TYPE=elasticsearch ES_HOSTS=host1,host2 java -jar zipkin-dependencies.jar
# To override the http port, add it to the host string
$ STORAGE_TYPE=elasticsearch ES_HOSTS=host1:9201 java -jar zipkin-dependencies.jar
```

## Building locally

To build the job from source and run against a local cassandra, in Spark's standalone mode.

```bash
# Build the spark jobs
$ ./mvnw -DskipTests clean install
$ STORAGE_TYPE=cassandra java -jar ./main/target/zipkin-dependencies*.jar
```
