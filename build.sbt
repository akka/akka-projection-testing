val AkkaVersion = "2.6.10"
val AkkaPersistenceCassandraVersion = "1.0.4"
val AkkaHttpVersion = "10.2.0"
val AkkaProjectionVersion = "1.0.0"
val AkkaManagementVersion = "1.0.9"
val AkkaPersistenceJdbc = "4.0.0+57-17a7d41a" // will become 5.0

lazy val `akka-projection-testing` = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    organization := "akka.projection.testing",
    version := "1.0",
    scalaVersion := "2.13.3",
    organization := "com.typesafe.akka",
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("https://www.lightbend.com/")),
    startYear := Some(2020),
    homepage := Some(url("https://akka.io")),
    licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    scalacOptions in Compile ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in Compile ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    resolvers += Resolver.bintrayRepo("akka", "snapshots"),
    libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
        "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
        "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
        "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
        "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
        "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion,
        "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,
        "com.lightbend.akka" %% "akka-persistence-jdbc" % AkkaPersistenceJdbc,
        "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
        "com.lightbend.akka" %% "akka-projection-cassandra" % AkkaProjectionVersion,
        "com.lightbend.akka" %% "akka-projection-jdbc" % AkkaProjectionVersion,
        "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion,
        "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion,
        "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
        "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % AkkaManagementVersion,
        "com.zaxxer" % "HikariCP" % "3.4.5",
        "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
        "ch.qos.logback" % "logback-classic" % "1.2.3",
        "org.postgresql" % "postgresql" % "42.2.14",
        "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-persistence-testkit" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
        "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test,
        "org.scalatest" %% "scalatest" % "3.1.0" % Test,
        "commons-io" % "commons-io" % "2.4" % Test
//        Cinnamon.library.cinnamonPrometheus,
//        Cinnamon.library.cinnamonPrometheusHttpServer,
//        Cinnamon.library.cinnamonAkkaTyped,
//        Cinnamon.library.cinnamonAkkaPersistence
      ),
//    cinnamon in run := true,
    Global / cancelable := false, // ctrl-c
    mainClass in (Compile, run) := Some("akka.projection.testing.Main"),
    // disable parallel tests
    parallelExecution in Test := false,
    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF"),
    logBuffered in Test := false)
  //  .enablePlugins(Cinnamon)
  .settings(
    dockerBaseImage := "adoptopenjdk:11-jre-hotspot",
    // change for your AWS account
    dockerUsername := None,
    dockerUpdateLatest := true,
    dockerRepository := Some("803424716218.dkr.ecr.us-east-1.amazonaws.com"))
  .configs(IntegrationTest)

TaskKey[Unit]("verifyCodeFmt") := {
  scalafmtCheckAll.all(ScopeFilter(inAnyProject)).result.value.toEither.left.foreach { _ =>
    throw new MessageOnlyException(
      "Unformatted Scala code found. Please run 'scalafmtAll' and commit the reformatted code")
  }
  (Compile / scalafmtSbtCheck).result.value.toEither.left.foreach { _ =>
    throw new MessageOnlyException(
      "Unformatted sbt code found. Please run 'scalafmtSbt' and commit the reformatted code")
  }
}

addCommandAlias("verifyCodeStyle", "headerCheckAll; verifyCodeFmt")
