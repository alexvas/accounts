package revolut.accounts.dal

private const val POSTGRES = "postgres"

internal class TestDeps {
    val deps = Deps()
    val ninjaAdapter = NinjaAdapter(deps.ds)
}

