package revolut.accounts.dal

import com.opentable.db.postgres.embedded.EmbeddedPostgres
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import liquibase.Contexts
import liquibase.Liquibase
import liquibase.database.core.PostgresDatabase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.FileSystemResourceAccessor
import org.jooq.ConnectionProvider
import revolut.accounts.common.Db
import revolut.accounts.common.DbInitializer
import java.sql.Connection
import javax.sql.DataSource

private const val POSTGRES = "postgres"

internal class HikariConnectionProvider(
        private val ds: DataSource
) : ConnectionProvider {

    override fun acquire(): Connection = ds.connection // get connection from pool

    override fun release(connection: Connection?) {
        connection?.close() // hikari proxy return sql connection to the pool
    }
}

// ragged-DI
internal object Deps {
    val db: Db
    val dbInitializer: DbInitializer

    init {
        val embeddedPostgres = EmbeddedPostgres.builder().start()
        val postgresJdbcUrl = embeddedPostgres.getJdbcUrl(POSTGRES, POSTGRES)
        val liqDb = PostgresDatabase().set("jdbcUrl", postgresJdbcUrl)
        embeddedPostgres.postgresDatabase.connection.use { conn ->
            liqDb.connection = JdbcConnection(conn)
            Liquibase(
                    "src/main/liquibase/changelog.xml",
                    FileSystemResourceAccessor(),
                    liqDb
            ).update(Contexts())
        }

//      val postgresJdbcUrl = "jdbc:postgresql://localhost:5432/revolut?user=revolut"
        val ds = HikariDataSource(
                HikariConfig().also { hc -> hc.jdbcUrl = postgresJdbcUrl }
        )
        val connectionProvider = HikariConnectionProvider(ds)
        db = DbImpl(connectionProvider)
        dbInitializer = DbInitializerImpl(connectionProvider)
    }
}

