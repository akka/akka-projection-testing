# Projections testing

This project tests event sourced actors events tagging events that are then read by a projection.

It is currently set up with Cassandra as the event sourcing event store and the projection using JDBC to 
provide exactly once delivery to the projection.

## Running a test

Start the application:

```
 sbt "run 2551"
```

Start a test run:

```
curl -X POST --data '{"name":"","nrActors":1000, "messagesPerActor": 100, "concurrentActors": 100, "bytesPerEvent": 100, "timeout": 60000}' --header "content-type: application/json" http://127.0.0.1:8051/test
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

multiple projecions are run to increase the load on the tagging infrastructure while not overloading the normal event log.
each projection gets its own tag for the same reason. a real production application would have different projections use the same tag.

the test checks that every message makes it into the projection. these are stored in the `events` table. duplicated 
are detected with a primary key.


### retries and idempotence

each persistent actor is responsible for persisting the number of events, one at a time, it is instructed to. this means the request to persist
the events can be retried meaning that even with failures to the messages table the test will eventually persist the right number of events.

### journal cleanup

Before every test the `messages` and `tag_views` test are truncated. Meaning when investigating failures the only messages in these tables
are from that test.

the projection table `events` is not cleaned between tests but the table is keyed by a unique test name. to see the events in that table:

```
select count(*) from events where name = 'test-1602761729929'
```

## injecting failures

the projection will fail roughly 2% of the messages resulting in the projection being restarted from the last saved offset.
this helps tests the "exactly once" in the event of failures.

this can be changed with `test.projection-failure-every`

## setup

* cassandra on port 9042
* postgres on port 5432 with user and password postgres/postgres. not currently configurable see `guardian.scala`

## starting multiple nodes

`sbt "run 2551"`

`sbt "run 2552"`

`sbt "run 2553"`

typically, multiple nodes are required to re-create issues as while one node is failing other nodes can progress the offset.

### cinnamon

the application exposes persistence metrics via cinnamon and prometheus. the cinnamon prometheus sandbox can be used to 
view the metrics in grafana.

## failure scenarios

### projection restart

a known edge case is that a projection is restarted and delayed events from before the offset are then missed.
this should only happen in when multiple nodes are writing events as delayed event should still be written in offset 
order.


## deployment to eks/gke

the akka platform operator can be used to deploy this application to eks for testing. 

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

the outputs printed at the end of `terraform apply` give all the information needed to configure an akka microservice e.g. 

```
db_endpoint = "projection-testing.cgrtpi2lqrw8.us-east-2.rds.amazonaws.com:5432"
ecr_repository = "803424716218.dkr.ecr.us-east-2.amazonaws.com/akka-projection-testing"
```

update build.sbt with the ecr repo for your aws account and publish with `sbt docker:push`

then follow instructions on (ommitting eks setup / security group setup / metrics server) https://developer.lightbend.com/docs/akka-platform-guide/deployment/aws-install.html
or deploy the operator manually if you have access to it.

create the jdbc secret, putting in your db endpoint:

```
kubectl create secret generic projection-testing-jdbc-secret --from-literal=username=postgres --from-literal=password=postgres --from-literal=connectionurl="jdbc:postgresql://kubectl create secret generic shopping-cart-service-jdbc-secret --from-literal=username=postgres --from-literal=password=tiger --from-literal=connectionurl="jdbc:postgresql://shopping-cart.c46wxwryhegl.eu-central-1.rds.amazonaws.com:5432/postgres?rewritebatchedinserts=true"
:5432/postgres?rewritebatchedinserts=true"

```

deploy the cr!

```
kubectl apply -f kubernetes/akka-microservice.yaml
```





