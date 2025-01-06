/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.jdbc

import java.sql.Connection
import javax.sql.DataSource

import akka.japi.function
import akka.projection.jdbc.JdbcSession

class HikariJdbcSession(source: DataSource) extends JdbcSession {

  private val connection = source.getConnection

  override def withConnection[Result](func: function.Function[Connection, Result]): Result =
    func(connection)

  override def commit(): Unit = connection.commit()

  override def rollback(): Unit = connection.rollback()

  override def close(): Unit = connection.close()
}
