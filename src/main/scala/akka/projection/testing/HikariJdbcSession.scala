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

import java.sql.Connection

import akka.japi.function
import akka.projection.jdbc.JdbcSession
import javax.sql.DataSource

class HikariJdbcSession(source: DataSource) extends JdbcSession {

  private val connection = source.getConnection

  override def withConnection[Result](func: function.Function[Connection, Result]): Result =
    func(connection)

  override def commit(): Unit = connection.commit()

  override def rollback(): Unit = connection.rollback()

  override def close(): Unit = connection.close()
}
