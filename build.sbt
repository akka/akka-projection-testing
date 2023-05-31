val AkkaVersion = "2.8.4"
val AkkaPersistenceCassandraVersion = "1.1.1"
val AkkaHttpVersion = "10.5.2"
val AkkaProjectionVersion = "1.4.2"
val AkkaManagementVersion = "1.4.1"
val AkkaPersistenceJdbc = "5.2.1"
val AkkaPersistenceR2dbc = "1.1.2"

ThisBuild / dynverSeparator := "-"

lazy val `akka-projection-testing` = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    organization := "akka.projection.testing",
    scalaVersion := "2.13.11",
    organization := "com.typesafe.akka",
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("https://www.lightbend.com/")),
    startYear := Some(2020),
    homepage := Some(url("https://akka.io")),
    licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    headerLicense := Some(
      HeaderLicense.Custom("""Copyright (C) 2020 - 2023 Lightbend Inc. <https://www.lightbend.com>""")
    ),
    scalacOptions in Compile ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in Compile ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
        "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
        "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
        "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
        "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
        "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion,
        "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,
        "com.lightbend.akka" %% "akka-persistence-jdbc" % AkkaPersistenceJdbc,
        "com.lightbend.akka" %% "akka-persistence-r2dbc" % AkkaPersistenceR2dbc,
        "com.lightbend.akka" %% "akka-projection-r2dbc" % AkkaProjectionVersion,
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
        // FIXME strange, something brings in slf4j 2.0.0-alpha
        "org.slf4j" % "slf4j-api" % "2.0.6",
        //"ch.qos.logback" % "logback-classic" % "1.2.11",
        "ch.qos.logback" % "logback-classic" % "1.4.5", // for slf4j 2.0
        "org.postgresql" % "postgresql" % "42.2.24",
        "org.hdrhistogram" % "HdrHistogram" % "2.1.12",
        "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-persistence-testkit" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
        "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test,
        "org.scalatest" %% "scalatest" % "3.2.7" % Test,
        "commons-io" % "commons-io" % "2.4" % Test
//        Cinnamon.library.cinnamonPrometheus,
//        Cinnamon.library.cinnamonPrometheusHttpServer,
//        Cinnamon.library.cinnamonAkkaTyped,
//        Cinnamon.library.cinnamonAkkaPersistence
      ),
//    cinnamon in run := true,
    run / fork := true,
    // pass along config selection to forked jvm
    run / javaOptions ++= sys.props
        .get("config.resource")
        .fold(Seq.empty[String])(res => Seq(s"-Dconfig.resource=$res")),
    Global / cancelable := false, // ctrl-c
    mainClass in (Compile, run) := Some("akka.projection.testing.Main"),
    // disable parallel tests
    parallelExecution in Test := false,
    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF"),
    logBuffered in Test := false)
  //  .enablePlugins(Cinnamon)
  .settings(
    dockerBaseImage := "eclipse-temurin:17.0.3_7-jre-jammy",
    dockerUsername := sys.props.get("docker.username"),
    dockerRepository := sys.props.get("docker.registry"),
    dockerUpdateLatest := true)
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
