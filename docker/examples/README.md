# Zipkin Dependencies Docker Examples

This project is configured to run docker containers using
[docker-compose](https://docs.docker.com/compose/). Note that the default
configuration requires docker-compose 2.4+.

To start the default docker-compose configuration, run:

```bash
# choose a specific tag, 'latest' for the last released or 'master' for last built.
$ export TAG=latest
# choose from cassandra3, elasticsearch or mysql
$ STORAGE_TYPE=elasticsearch
# start the example setup
$ docker-compose -f docker-compose.yml -f docker-compose-${STORAGE_TYPE}.yml up
```

This starts zipkin, the corresponding storage and makes an example request.
After that, it runs the dependencies job on-demand.

You can then use the following two services:
* Zipkin's UI is reachable at http://localhost:9411/zipkin/dependency
* An example traced service at http://localhost:8081

If you make more requests to the example traced service, you can run the
dependencies job in another tab like so, and that will overwrite the day's
data:

```bash
$ docker-compose -f docker-compose.yml -f docker-compose-${STORAGE_TYPE}.yml start dependencies
```

Finally, when done, CTRL-C and remove the example setup via `docker-compose down` like so:

```bash
$ docker-compose -f docker-compose.yml -f docker-compose-${STORAGE_TYPE}.yml down
```
