[![Gitter chat](http://img.shields.io/badge/gitter-join%20chat%20%E2%86%92-brightgreen.svg)](https://gitter.im/openzipkin/zipkin)
[![Build Status](https://travis-ci.org/openzipkin/zipkin-dependencies.svg?branch=master)](https://travis-ci.org/openzipkin/zipkin-dependencies)
[![Maven Central](https://img.shields.io/maven-central/v/io.zipkin.dependencies/zipkin-dependencies.svg)](https://search.maven.org/search?q=g:io.zipkin.dependencies%20AND%20a:zipkin-dependencies)

# zipkin-dependencies

Zipkin Dependencies collects spans from storage, analyzes links between services, and stores them for later presentation in the [web UI](https://github.com/openzipkin/zipkin/tree/master/zipkin-lens) (ex. http://localhost:8080/dependency).

This process is implemented as an Apache Spark job. This job parses all traces in the current day in UTC time. This means you should schedule it to run just prior to midnight UTC.

All Zipkin [Storage Components](https://github.com/openzipkin/zipkin/blob/master/zipkin-storage/)
are supported, including Cassandra, MySQL and Elasticsearch.

## Versions

* `STORAGE_TYPE=cassandra3` : requires Cassandra 3.11.3+; tested against the latest patch of 4.0
* `STORAGE_TYPE=mysql` : requires MySQL 5.6+; tested against MySQL 10.11
* `STORAGE_TYPE=elasticsearch` : requires Elasticsearch 7+; tested against last minor release of 7.x and 8.x

## Quick-start

Until [SPARK-43831](https://issues.apache.org/jira/browse/SPARK-43831), Zipkin Dependencies requires Java 11 to run.

The quickest way to get started is to fetch the [latest released job](https://search.maven.org/remote_content?g=io.zipkin.dependencies&a=zipkin-dependencies&v=LATEST) as a self-contained jar. For example:

```bash
$ curl -sSL https://zipkin.io/quickstart.sh | bash -s io.zipkin.dependencies:zipkin-dependencies:LATEST zipkin-dependencies.jar
$ STORAGE_TYPE=cassandra3 java -jar zipkin-dependencies.jar
```

You can also start Zipkin Dependencies via [Docker](https://github.com/openzipkin/docker-zipkin-dependencies).
```bash
$ docker run --env STORAGE_TYPE=cassandra3 --env CASSANDRA_CONTACT_POINTS=host1,host2 openzipkin/zipkin-dependencies
```

For a more complete example, take a look at the [docker-compose example](docker/examples/README.md).

## Usage

By default, this job parses all traces since midnight UTC. You can parse traces for a different day
via an argument in YYYY-mm-dd format, like 2016-07-16.

```bash
# ex to run the job to process yesterday's traces on OS/X
$ STORAGE_TYPE=cassandra3 java -jar zipkin-dependencies.jar `date -uv-1d +%F`
# or on Linux
$ STORAGE_TYPE=cassandra3 java -jar zipkin-dependencies.jar `date -u -d '1 day ago' +%F`
```

## Environment Variables
`zipkin-dependencies` applies configuration parameters through environment variables.

The following variables are common to all storage layers:

    * `SPARK_MASTER`: Spark master to submit the job to; Defaults to `local[*]`
    * `ZIPKIN_LOG_LEVEL`: Log level for zipkin-related status; Defaults to INFO (use DEBUG for details)
    * `SPARK_CONF`: Extend more spark configuration with value in properties format and separated with comma. Such as `spark.executor.heartbeatInterval=600000,spark.network.timeout=600000`

### Cassandra
Cassandra is used when `STORAGE_TYPE=cassandra` or `STORAGE_TYPE=cassandra3`.
* `cassandra` is compatible with Zipkin's [Legacy Cassandra storage component](https://github.com/openzipkin/zipkin/tree/master/zipkin-storage/cassandra).
* `cassandra3` is compatible with Zipkin's [Cassandra v3 storage component](https://github.com/openzipkin/zipkin/tree/master/zipkin-storage/zipkin2_cassandra).

Here are the variables that apply

    * `CASSANDRA_KEYSPACE`: The keyspace to use. Defaults to "zipkin".
    * `CASSANDRA_CONTACT_POINTS`: Comma separated list of hosts / ip addresses part of Cassandra cluster. Defaults to localhost
    * `CASSANDRA_LOCAL_DC`: The local DC to connect to (other nodes will be ignored)
    * `CASSANDRA_USERNAME` and `CASSANDRA_PASSWORD`: Cassandra authentication. Will throw an exception on startup if authentication fails
    * `CASSANDRA_USE_SSL`: Requires `javax.net.ssl.trustStore` and `javax.net.ssl.trustStorePassword`, defaults to false.
    * `STRICT_TRACE_ID`: When false, dependency linking only looks at 64 bits of a trace ID, defaults to true.

Example usage:

```bash
$ STORAGE_TYPE=cassandra3 CASSANDRA_USERNAME=user CASSANDRA_PASSWORD=pass java -jar zipkin-dependencies.jar
```

### MySQL Storage
MySQL is used when `STORAGE_TYPE=mysql`. The schema is compatible with Zipkin's [MySQL storage component](https://github.com/openzipkin/zipkin/tree/master/zipkin-storage/mysql-v1).

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
    * `ES_DATE_SEPARATOR`: The separator used when generating dates in index.
                           Defaults to '-' so the queried index look like zipkin-yyyy-DD-mm
                           Could for example be changed to '.' to give zipkin-yyyy.MM.dd
    * `ES_HOSTS`: A comma separated list of elasticsearch hosts advertising http. Defaults to
                  localhost. Add port section if not listening on port 9200. Only one of these hosts
                  needs to be available to fetch the remaining nodes in the cluster. It is
                  recommended to set this to all the master nodes of the cluster. Use url format for
                  SSL. For example, "https://yourhost:8888"
    * `ES_NODES_WAN_ONLY`: Set to true to only use the values set in ES_HOSTS, for example if your
                           elasticsearch cluster is in Docker. Defaults to false
    * `ES_USERNAME` and `ES_PASSWORD`: Elasticsearch basic authentication. Use when X-Pack security
                                       (formerly Shield) is in place. By default no username or
                                       password is provided to elasticsearch.

Example usage:

```bash
$ STORAGE_TYPE=elasticsearch ES_HOSTS=host1,host2 java -jar zipkin-dependencies.jar
# To override the http port, add it to the host string
$ STORAGE_TYPE=elasticsearch ES_HOSTS=host1:9201 java -jar zipkin-dependencies.jar
```

#### Custom certificates

When using an https endpoint in `ES_HOSTS`, you can use the following standard properties to
customize the certificates used for the connection:

* javax.net.ssl.keyStore
* javax.net.ssl.keyStorePassword
* javax.net.ssl.trustStore
* javax.net.ssl.trustStorePassword

## Building locally

To build the job from source and run against a local cassandra, in Spark's standalone mode.

```bash
# Build the spark jobs
$ ./mvnw -T1C -q --batch-mode -DskipTests -Denforcer.fail=false package
$ STORAGE_TYPE=cassandra java -jar ./main/target/zipkin-dependencies*.jar
```

## Running in a Spark cluster

The jar file produced by this build can also run against spark directly. Before anything
else, make sure you are running the same version of spark as used here.

You can use the following command to display what this project is built against:
```bash
$ SPARK_VERSION=$(./mvnw help:evaluate -Dexpression=spark.version -q -DforceStdout)
$ echo $SPARK_VERSION
3.3.4
```

Once you've verified your setup is on the correct version, set the `SPARK_MASTER` variable:

For example, if you are connecting to spark running on the same host:
```bash
$ STORAGE_TYPE=cassandra3 SPARK_MASTER=spark://$HOSTNAME:7077 java -jar zipkin-dependencies.jar
```

Note that the Zipkin team focuses on tracing, not Spark support. If you have Spark cluster related
troubleshooting questions, please use their [support tools](https://spark.apache.org/community.html).

## Troubleshooting

When troubleshooting, always set `ZIPKIN_LOG_LEVEL=DEBUG` as this output
is important when figuring out why a trace didn't result in a link.

If you set `SPARK_MASTER` to something besides local, remember that log
output also ends up in `stderr` of the workers.

By default, this job uses the value of system property `java.io.tmpdir` as location to store temporary data.
If you're getting `java.io.IOException: No space left on device` while processing large sets
of trace data, you can specify a different location that has enough space available using `-Djava.io.tmpdir=/other/location`.

## Artifacts
All artifacts publish to the group ID "io.zipkin.dependencies". We use a common
release version for all components.

### Library Releases
Releases are at [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.zipkin.dependencies%22)

### Library Snapshots
Snapshots are uploaded to [Sonatype](https://oss.sonatype.org/content/repositories/snapshots) after
commits to master.

### Docker Images
Released versions of zipkin-dependencies are published to Docker Hub as `openzipkin/zipkin-dependencies`
and GitHub Container Registry as `ghcr.io/openzipkin/zipkin-dependencies`.

See [docker](./docker) for details.
