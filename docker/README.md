## zipkin-dependencies Docker image

This repository contains the Docker build definition and release process for `zipkin-dependencies`.

The zipkin dependencies job pre-aggregates data such that `http://your_host:9411/dependency` shows links
between services.

Automatically built images are available on Docker Hub as [openzipkin/zipkin-dependencies](https://hub.docker.com/r/openzipkin/zipkin-dependencies/).

## Running

### On-demand
To process all spans since midnight UTC, run the default entrypoint of this image pointed at your storage backend.

```bash
$ docker run --env STORAGE_TYPE=cassandra --env CASSANDRA_CONTACT_POINTS=host1,host2 openzipkin/zipkin-dependencies
```

### Cron
To process spans since midnight every hour, and all spans each day, change the entrypoint to cron.

```bash
$ docker run ... openzipkin/zipkin-dependencies sh -c 'crond -f'
```

## Configuration
Configuration is via environment variables, defined by [zipkin-dependencies](https://github.com/openzipkin/zipkin-dependencies/blob/master/README.md).

In docker, the following can also be set:

    * `JAVA_OPTS`: Use to set java arguments, such as heap size or trust store location.

## Building

To build a zipkin-dependencies Docker image from source, in the top level of the repository, run:

```bash
$ docker build -t openzipkin/zipkin-dependencies:test -f docker/Dockerfile .
```

To build from a published version, run this instead:

```bash
$ docker build --build-arg RELEASE_VERSION=2.4.3 -t openzipkin/zipkin-dependencies:test -f docker/Dockerfile .
```
