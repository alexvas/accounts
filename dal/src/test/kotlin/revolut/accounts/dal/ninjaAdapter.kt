package revolut.accounts.dal

import com.ninja_squad.dbsetup.DbSetup
import com.ninja_squad.dbsetup.DbSetupTracker
import com.ninja_squad.dbsetup.bind.Binder
import com.ninja_squad.dbsetup.bind.DefaultBinderConfiguration
import com.ninja_squad.dbsetup.destination.DataSourceDestination
import com.ninja_squad.dbsetup.operation.CompositeOperation
import com.ninja_squad.dbsetup.operation.Operation
import com.zaxxer.hikari.HikariDataSource
import org.postgresql.util.PGobject
import java.sql.ParameterMetaData
import java.sql.PreparedStatement
import java.util.*


object PostgresBinderConfiguration : DefaultBinderConfiguration() {

    /**
     * Adds support for Postgres type placeholders.
     */
    override fun getBinder(metadata: ParameterMetaData?, param: Int): Binder {
        return BinderWrapper(super.getBinder(metadata, param))
    }

    /**
     * Allows the use of custom types in Postgres.
     *
     *
     * Append the type to the value using the ::objectType notation to
     * attempt to set the [PGobject] type appropriately
     *
     * For example, given a table FOO with a column VAL of type jsonb
     * <pre>
     * Insert ins = Insert.into("FOO")
     * .columns("VAL")
     * .values("{\"somekey\": \"somevalue\"}"::jsonb")
     * .build();
    </pre> *
     */
    private class BinderWrapper(private val delegate: Binder) : Binder {

        override fun bind(statement: PreparedStatement, paramIndex: Int, value: Any?) {
            if (value == null) {
                delegate.bind(statement, paramIndex, null)
                return
            }

            if (value is Array<*> && value.isArrayOf<UUID>()) {
                statement.setArray(paramIndex, statement.connection.createArrayOf("UUID", value))
                return
            }

            val chunks = value.toString()
                    .splitToSequence("::")
                    .map { it.trim() }
                    .toList()

            if (chunks.size == 1) {
                delegate.bind(statement, paramIndex, value)
                return
            }

            val bareValue = chunks[0]
            val type = chunks[1]

            val obj = PGobject()
            obj.type = type
            obj.value = bareValue

            statement.setObject(paramIndex, obj)
        }

    }
}

class NinjaAdapter(private val dataSource: HikariDataSource) {
    private val dbSetupTracker = DbSetupTracker()

    // call in @BeforeAll test method
    fun prepare(vararg initDb: Operation): NinjaAdapter {
        val dbSetup = DbSetup(DataSourceDestination(dataSource), adapt(initDb), PostgresBinderConfiguration)
        dbSetupTracker.launchIfNecessary(dbSetup)
        return this
    }

    private fun adapt(initDb: Array<out Operation>) =
            if (initDb.size == 1) initDb[0] else CompositeOperation.sequenceOf(initDb.toList())

    @Suppress("unused")
    fun replenish(vararg additional: Operation): NinjaAdapter {
        DbSetup(DataSourceDestination(dataSource), adapt(additional), PostgresBinderConfiguration).launch()
        return this
    }

    fun skipNextLaunch(): NinjaAdapter {
        dbSetupTracker.skipNextLaunch()
        return this
    }

    fun close() = dataSource.close()

}
