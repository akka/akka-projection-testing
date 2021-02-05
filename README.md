# Projections testing

This project tests event sourced actors events tagging events that are then read by a projection.

It is currently set up with Cassandra as the event sourcing event store and the projection using JDBC to 
provide exactly once delivery to the projection.

## Running a test

```
curl  -X POST  -v --data '{"name":"","nrActors":20000, "messagesPerActor": 1, "concurrentActors": 20000, "timeout": 60000}' --header "Content-Type: application/json"
```

The params are:

* `nrActors` How many persistent actors to create
* `messagesPerActor` How many messages per actor, the total number of messages will be `nrActors * messagesPerActor`
* `concurrentActors` How many actors to have persisting events at the same time. Set to the same as `nrActors` to have them all created at once.
* `timeout` How long to wait for all the messages to reach the projection in seconds

The response gives back a test name and an expected event total.
The expected event total is the `nrActos` * `messagesPerActor` * `${event-processor.nr-projections}`.

```
{"expectedMessages":200000,"testName":"test-1602762703160"}
```

Multiple projecions are run to increase the load on the tagging infrastructure while not overloading the normal event log.
Each projection gets its own tag for the same reason. A real production application would have different projections use the same tag.

The test checks that every message makes it into the projection. These are stored in the `events` table. Duplicated 
are detected with a primary key.


### Retries and idempotence

Each persistent actor is responsible for persisting the number of events, one at a time, it is instructed to. This means the request to persist
the events can be retried meaning that even with failures to the messages table the test will eventually persist the right number of events.

### Journal cleanup

Before every test the `messages` and `tag_views` test are truncated. Meaning when investigating failures the only messages in these tables
are from that test.

The projection table `events` is not cleaned between tests but the table is keyed by a unique test name. To see the events in that table:

`select count(*) from events where name = 'test-1602761729929'`

## Injecting failures

The projection will fail roughly 2% of the messages resulting in the projection being restarted from the last saved offset.
This helps tests the "exactly once" in the event of failures.

This can be changed with `test.projection-failure-every`

## Setup

* Cassandra on port 9042
* Postgres on port 5432 with user and password docker/docker. Not currently configurable see `Guardian.scala`

## Starting multiple nodes

`sbt "run 2551"`

`sbt "run 2552"`

`sbt "run 2553"`

Typically, multiple nodes are required to re-create issues as while one node is failing other nodes can progress the offset.

### Cinnamon

The application exposes Persistence metrics via cinnamon and prometheus. The cinnamon prometheus sandbox can be used to 
view the metrics in Grafana.

## Failure scenarios

### Projection restart

A known edge case is that a projection is restarted and delayed events from before the offset are then missed.
This should only happen in when multiple nodes are writing events as delayed event should still be written in offset 
order.


## Deployment to EKS/GKE

The Akka platform operator can be used to deploy this application to EKS for testing. 

### Deploying to a new cluster with terraform

Configure you aws client `aws configure`

```
cd terraform
terraform init
terraform plan
```

Then to actually execute

```
terraform apply
```

This will create:

- VPC
- RDS instance
- EKS cluster
- Install the metrics server into the EKS cluster (requried by the operator)
- Configure security groups to allow communication

The outputs printed at the end of `terraform apply` give all the information needed to configure an Akka Microservice e.g. 

```
db_endpoint = "projection-testing.cgrtpi2lqrw8.us-east-2.rds.amazonaws.com:5432"
ecr_repository = "803424716218.dkr.ecr.us-east-2.amazonaws.com/akka-projection-testing"
```

Update build.sbt with the ecr repo for your AWS account and publish with `sbt docker:push`

Then follow instructions on (ommitting EKS setup / security group setup / metrics server) https://developer.lightbend.com/docs/akka-platform-guide/deployment/aws-install.html
Or deploy the operator manually if you have access to it.

Create the JDBC secret, putting in your db endpoint:

```
kubectl create secret generic projection-testing-jdbc-secret --from-literal=username=postgres --from-literal=password=postgres --from-literal=connectionUrl="jdbc:postgresql://kubectl create secret generic shopping-cart-service-jdbc-secret --from-literal=username=postgres --from-literal=password=tiger --from-literal=connectionUrl="jdbc:postgresql://shopping-cart.c46wxwryhegl.eu-central-1.rds.amazonaws.com:5432/postgres?reWriteBatchedInserts=true"
:5432/postgres?reWriteBatchedInserts=true"

```

Deploy the CR!

```
kubectl apply -f kubernetes/akka-microservice.yaml
```





