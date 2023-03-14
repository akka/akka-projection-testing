/*
 * Copyright (C) 2020 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import javax.sql.DataSource

class HikariJdbcSessionFactory(val dataSource: DataSource) {
  def newSession(): HikariJdbcSession = {
    new HikariJdbcSession(dataSource)
  }
}
