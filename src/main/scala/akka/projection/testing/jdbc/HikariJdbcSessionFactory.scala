/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.jdbc

import javax.sql.DataSource

class HikariJdbcSessionFactory(val dataSource: DataSource) {
  def newSession(): HikariJdbcSession = {
    new HikariJdbcSession(dataSource)
  }
}
