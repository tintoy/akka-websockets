name          := "akka-websockets-demo"
organization  := "io.tintoy"
version       := "0.1"

scalaVersion  := "2.11.7"

libraryDependencies ++= {
  val akkaVersion             = "2.4.0"
  val akkaHttpVersion         = "2.0-M1"
  val slf4JVersion            = "1.7.12"
  val configFrameworkVersion  = "1.2.0"
  val scalaTestVersion        = "2.2.4"

  Seq(
    "com.typesafe.akka"       %%  "akka-actor"                        % akkaVersion,
    "com.typesafe.akka"       %%  "akka-http-experimental"            % akkaHttpVersion,
    "com.typesafe.akka"       %%  "akka-http-spray-json-experimental" % akkaHttpVersion,
    "com.typesafe.akka"       %%  "akka-slf4j"                        % akkaVersion,
    "com.typesafe.akka"       %%  "akka-testkit"                      % akkaVersion,

    "org.slf4j"               %   "slf4j-api"                         % slf4JVersion,
    "org.slf4j"               %   "slf4j-simple"                      % slf4JVersion,

    "com.typesafe"            %   "config"                            % configFrameworkVersion,

    "org.scalatest"           %% "scalatest"                          % scalaTestVersion % "test",

    // Explicit dependencies to suppress SBT warnings.
    "org.scala-lang"          %   "scala-reflect"                     % "2.11.7",
    "org.scala-lang.modules"  %%  "scala-xml"                         % "1.0.4"
  )
}

// Enable caching for dependency resolution (still PAINFULLY slow)
updateOptions in Global :=
  (updateOptions in Global).value
    .withCachedResolution(true)
