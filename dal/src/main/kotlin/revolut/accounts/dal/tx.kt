package revolut.accounts.dal

import org.jooq.ConnectionProvider
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderNameStyle
import org.jooq.conf.SettingsTools
import org.jooq.impl.DSL


private val settings = SettingsTools.defaultSettings()
        .withRenderNameStyle(RenderNameStyle.AS_IS)
        .withRenderCatalog(false)
        .withRenderSchema(false)!!

internal fun <T> ConnectionProvider.tx(block: DSLContext.() -> T): T {
    val sqlConn = acquire()
    val ctx = DSL.using(sqlConn, SQLDialect.POSTGRES, settings)
    return try {
        ctx.transactionResult { configuration -> configuration.dsl().block() }
    } finally {
        release(sqlConn)
    }
}
