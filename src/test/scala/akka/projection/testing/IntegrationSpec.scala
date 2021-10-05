/*
 * Copyright 2020 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.projection.testing

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.unmarshalling._
import akka.management.cluster.{ ClusterHttpManagementJsonProtocol, ClusterMembers }
import akka.projection.testing.TestRoutes.{ RunTest, _ }
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Milliseconds, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.Try

class IntegrationSpec
    extends AnyWordSpec
    with Eventually
    with BeforeAndAfterAll
    with ScalaFutures
    with ClusterHttpManagementJsonProtocol
    with Matchers {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(120, Seconds), interval = Span(500, Milliseconds))
  implicit val testSystem: ActorSystem = ActorSystem()
  implicit val ec: ExecutionContextExecutor = testSystem.dispatcher
  private var systems: Set[ActorSystem] = Set(testSystem)

  "End to end test" should {
    "work" in {
      systems += Main.startNode(2551, 8051, 9001, 8551).toClassic
      validateAllMembersUp(8551, 1)
      systems += Main.startNode(2552, 8052, 9002, 8552).toClassic
      validateAllMembersUp(8552, 2)

      val test = RunTest("", 100, 1, 1, 100, 10000)
      val response = Unmarshal(Http().singleRequest(Post("http://127.0.0.1:8051/test", test)).futureValue.entity)
        .to[Response]
        .futureValue

      eventually {
        val result = Unmarshal(
          Http()
            .singleRequest(HttpRequest(uri = s"http://127.0.0.1:8051/test/${response.testName}"))
            .futureValue
            .entity).to[String].futureValue
        println("got result: " + result)
        result shouldEqual "pass"
      }
    }
  }

  def validateAllMembersUp(port: Int, nr: Int): Unit = {
    eventually {
      val members = Http()
        .singleRequest(HttpRequest(uri = s"http://127.0.0.1:$port/cluster/members"))
        .flatMap { response =>
          response.status match {
            case StatusCodes.OK =>
              Unmarshal(response).to[ClusterMembers]
            case other =>
              throw new RuntimeException(
                s"Unexpected status code: $other with body ${response.entity.toStrict(100.millis).futureValue}")
          }
        }
        .futureValue
      members.members.size shouldEqual nr
      members.members.map(_.status.toLowerCase()) shouldEqual Set("up")
    }
  }

  override protected def afterAll(): Unit = {
    systems.foreach { system =>
      Try(TestKit.shutdownActorSystem(system))
    }
  }
}
