val AkkaVersion = "2.10.6"
val AkkaHttpVersion = "10.7.1"
val AkkaProjectionVersion = "1.6.13"
val AkkaManagementVersion = "1.6.2"
val AkkaPersistenceR2dbcVersion = "1.3.7"
val AkkaPersistenceDynamoDBVersion = "2.0.6"
val AkkaPersistenceJdbcVersion = "5.5.2"
val AkkaPersistenceCassandraVersion = "1.3.2"

ThisBuild / dynverSeparator := "-"

lazy val `akka-projection-testing` = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin, Insights)
  .settings(
    organization := "akka.projection.testing",
    scalaVersion := "2.13.15",
    organization := "com.typesafe.akka",
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("https://www.lightbend.com/")),
    startYear := Some(2020),
    homepage := Some(url("https://akka.io")),
    licenses := {
      val tagOrBranch =
        if (isSnapshot.value) "main"
        else "v" + version.value
      Seq(
        "LIGHTBEND COMMERCIAL SOFTWARE LICENSE AGREEMENT" ->
          url(s"https://raw.githubusercontent.com/akka/akka-projection-testing/${tagOrBranch}/LICENSE"))
    },
    headerLicense := Some(
      HeaderLicense.Custom("""Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>""")),
    Compile / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    resolvers += "Akka library repository".at("https://repo.akka.io/maven"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
      "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
      "com.lightbend.akka" %% "akka-persistence-r2dbc" % AkkaPersistenceR2dbcVersion,
      "com.lightbend.akka" %% "akka-projection-r2dbc" % AkkaProjectionVersion,
      "com.lightbend.akka" %% "akka-persistence-dynamodb" % AkkaPersistenceDynamoDBVersion,
      "com.lightbend.akka" %% "akka-projection-dynamodb" % AkkaProjectionVersion,
      "com.lightbend.akka" %% "akka-persistence-jdbc" % AkkaPersistenceJdbcVersion,
      "com.lightbend.akka" %% "akka-projection-jdbc" % AkkaProjectionVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion,
      "com.lightbend.akka" %% "akka-projection-cassandra" % AkkaProjectionVersion,
      "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % AkkaManagementVersion,
      "com.zaxxer" % "HikariCP" % "6.2.1",
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.5.12",
      "org.postgresql" % "postgresql" % "42.7.4",
      "org.hdrhistogram" % "HdrHistogram" % "2.2.2",
      "org.apache.commons" % "commons-rng-simple" % "1.6",
      "org.apache.commons" % "commons-statistics-distribution" % "1.1",
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
      "com.typesafe.akka" %% "akka-persistence-testkit" % AkkaVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
      "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "commons-io" % "commons-io" % "2.18.0" % Test),
    run / fork := true,
    // pass along config selection to forked jvm
    run / javaOptions ++= sys.props
      .get("config.resource")
      .fold(Seq.empty[String])(res => Seq(s"-Dconfig.resource=$res")),
    Global / cancelable := false, // ctrl-c
    Compile / run / mainClass := Some("akka.projection.testing.Main"),
    // disable parallel tests
    Test / parallelExecution := false,
    // show full stack traces and test case durations
    Test / testOptions += Tests.Argument("-oDF"),
    Test / logBuffered := false)
  //  .enablePlugins(Cinnamon)
  .settings(
    dockerBaseImage := "eclipse-temurin:21.0.7_6-jre-noble",
    dockerUsername := sys.props.get("docker.username"),
    dockerRepository := sys.props.get("docker.registry"),
    dockerUpdateLatest := true,
    dockerBuildxPlatforms := {
      // regular build with local platform by default, but support `DOCKER_PLATFORM=linux/amd64`
      sys.env.get("DOCKER_PLATFORM").fold(dockerBuildxPlatforms.value)(platform => Seq(platform))
    },
    dockerBuildCommand := {
      // add buildx for Docker/publishLocal as well, if buildx platforms defined
      val platforms = dockerBuildxPlatforms.value
      if (platforms.isEmpty) dockerBuildCommand.value
      else {
        dockerExecCommand.value ++
        Seq("buildx", "build", s"--platform=${platforms.mkString(",")}") ++
        dockerBuildOptions.value ++
        Seq(".")
      }
    })

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
