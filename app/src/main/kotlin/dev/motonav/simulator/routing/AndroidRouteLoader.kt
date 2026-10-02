package dev.motonav.simulator.routing

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Owns blocking provider work so activities only issue commands and render returned results. */
class AndroidRouteLoader(
    context: Context,
    apiKey: String,
    usageLedger: UsageLedger,
    revisions: RouteRevisionSource,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val providerResult = runCatching {
        GoogleRoutesProvider(
            apiKey,
            AndroidIdentity.from(context),
            UrlConnectionHttpClient(),
            usageLedger,
            GoogleRouteParser(revisions),
        )
    }

    fun load(
        stops: List<RouteStop>,
        cancellation: RoutingCancellation,
        callback: (Result<RoadRoute>) -> Unit,
    ) {
        val provider = providerResult.getOrElse { error ->
            callback(Result.failure(error))
            return
        }
        executor.execute {
            val result = runCatching { provider.computeRoute(stops, cancellation) }
            mainHandler.post { callback(result) }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
