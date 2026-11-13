package uk.noammm.kav.data

// Where live locations come from. KavLive asks whichever one the rider picked (Source.live) and keeps its own guards
// round it; each provider only fetches and reads its answer.
interface LiveProvider {
    // Only vehicles serving stops near the place asked about, not every vehicle there.
    val nearbyOnly: Boolean

    fun follow(routes: List<Int>): KavLive.Answer
    fun area(box: DoubleArray): KavLive.Area
    fun line(number: String, codes: List<Int>): KavLive.Area
    fun stops(codes: Collection<Int>): KavLive.Board
    fun shape(route: Int): List<Pair<Double, Double>>

    // A route's road already known, without asking: empty when it isn't.
    fun shapeNow(route: Int): List<Pair<Double, Double>> = emptyList()
}

// Bus Nearby and EGG, HopOn's two apps, which answer by stop.
class HopOnLive private constructor(private val app: Source) : LiveProvider {
    override val nearbyOnly get() = true
    override fun follow(routes: List<Int>) = BusNearby.follow(routes, app)
    override fun area(box: DoubleArray) = BusNearby.area(box, app)
    override fun line(number: String, codes: List<Int>) = BusNearby.line(number, app, codes)
    override fun stops(codes: Collection<Int>) = BusNearby.stops(codes, app)
    override fun shape(route: Int) = BusNearby.shape(route, app)

    companion object {
        val BUS_NEARBY = HopOnLive(Source.BUS_NEARBY)
        val EGG = HopOnLive(Source.EGG)
    }
}

// The provider a choice of live locations asks: Kav's server or the rider's own (KavLive.Server, at KavLive.server),
// or one of HopOn's apps. Google gives no live locations (Source.live is false), so it never gets here.
val Source.liveProvider: LiveProvider
    get() = when (this) {
        Source.BUS_NEARBY -> HopOnLive.BUS_NEARBY
        Source.EGG -> HopOnLive.EGG
        else -> KavLive.Server
    }
