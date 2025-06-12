# Projections testing

This project tests event sourced actors events that are then read by a projection.

It is currently support the following alternative Akka Persistence and Akka Projection setup:

* R2DBC (Postgres) as the event sourcing event store and the projection using R2DBC (Postgres)
* Cassandra as the event sourcing event store and the projection using JDBC (Postgres)
* JDBC (Postgres) as the event sourcing event store and the projection using JDBC (Postgres)

## Running a test locally

### R2DBC (Postgres)

Start a local PostgresSQL server on default port 5432:

```shell
docker compose -f docker/docker-compose-r2dbc-postgres.yml up --wait
```

Adjust the includes in `local.conf` to select R2DBC.

### Cassandra and JDBC (Postgres)

For testing with Cassandra start a local Cassandra in addition to the PostgresSQL:

```shell
docker compose -f docker/docker-compose-cassandra-jdbc-postgres.yml up --wait
```

Adjust the includes in `local.conf` to select Cassandra.

### JDBC (Postgres)

Start a local PostgresSQL server on default port 5432:

```shell
docker compose -f docker/docker-compose-jdbc-postgres.yml up --wait
```

Adjust the includes in `local.conf` to select JDBC.

### Run application

Start the application:

```shell
sbt "run 2551"
```

### Run simulations

See [simulation/README.md] for more detail on running simulations. Or for simple test runs, see below.

### Start test run

Start a test run:

```shell
curl -X POST --data '{"nrActors":1000, "messagesPerActor": 100, "concurrentActors": 100, "bytesPerEvent": 100, "timeout": 60000}' --header "content-type: application/json" http://127.0.0.1:8051/test
```

the params are:

* `nrActors` how many persistent actors to create
* `messagesPerActor` how many messages per actor, the total number of messages will be `nractors * messagesperactor`
* `concurrentActors` how many actors to have persisting events at the same time. set to the same as `nractors` to have them all created at once.
* `timeout` how long to wait for all the messages to reach the projection in seconds

the response gives back a test name and an expected event total.
the expected event total is the `nrActors` * `messagesPeractor` * `${event-processor.nr-projections}`.

```
{"expectedmessages":200000,"testname":"test-1602762703160"}
```

Multiple projections can be run to increase the load on the tagging infrastructure while not overloading the normal event log.
Each projection gets its own tag for the same reason. A real production application would have different projections use the same tag.

The test checks that every message makes it into the projection. these are stored in the `events` table. Duplicated
are detected with a primary key.

To inspect the database:

```shell
docker exec -it postgres-db psql -U postgres
```


### Retries and idempotence

Each persistent actor is responsible for persisting the number of events, one at a time, it is instructed to. This means the request to persist
the events can be retried meaning that even with failures to the messages table the test will eventually persist the right number of events.

### journal cleanup

Before every test the `messages` and `tag_views` test are truncated. Meaning when investigating failures the only messages in these tables
are from that test.

The projection table `events` is not cleaned between tests but the table is keyed by a unique test name. To see the events in that table:

```
select count(*) from events where name = 'test-1602761729929'
```

## performance test

To test performance, rather than validation of projection correctness, you can set config:

```
event-processor.read-only = on
```

Note that you can test write throughput by only starting nodes with Akka Cluster role "write-model".

## injecting failures

The projection can be configured to randomly fail at a given rate of the messages resulting in the projection being restarted from the last saved offset.
This helps tests the "exactly once" in the event of failures.

This be enabled with:

```
event-processor.projection-failure-every = 100
```

## setup

* cassandra on port 9042
* postgres on port 5432 with user and password postgres/postgres. not currently configurable see `Guardian.scala`

## starting multiple nodes

`sbt "run 2551"`

`sbt "run 2552"`

`sbt "run 2553"`

Typically, multiple nodes are required to re-create issues as while one node is failing other nodes can progress the offset.

### cinnamon

the application exposes persistence metrics via cinnamon and prometheus. the cinnamon prometheus sandbox can be used to
view the metrics in grafana.

## failure scenarios

### projection restart

A known edge case is that a projection is restarted and delayed events from before the offset are then missed.
This should only happen in when multiple nodes are writing events as delayed event should still be written in offset
order.

## Deployment to eks/gke

THIS SECTION MIGHT BE OUTDATED

### deploying to a new cluster with terraform

configure your aws client `aws configure`

```
cd terraform
terraform init
terraform plan
```

then to actually execute

```
terraform apply
```

this will create:

- vpc
- rds instance
- eks cluster
- install the metrics server into the eks cluster (requried by the operator)
- configure security groups to allow communication

the outputs printed at the end of `terraform apply` give all the information needed to configure the `kubernetes/deployment.yaml`

```
db_endpoint = "projection-testing.cgrtpi2lqrw8.us-east-2.rds.amazonaws.com:5432"
```

License
-------

This is a test project and licensed as LIGHTBEND COMMERCIAL SOFTWARE LICENSE AGREEMENT.
